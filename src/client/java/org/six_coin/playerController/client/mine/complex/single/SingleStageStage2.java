package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.SnapUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 第 (5) 步的**第二步**：用 mine simple 把目标区域挖空。
 *
 * <p><b>不再蛇形走位</b>，改成「挖到没得挖了就挪到最远的那一侧」：
 * <ol>
 *   <li>把 mine simple 的范围设成目标区域（{@link MineManager#startTransient}，不写 mine.json）；</li>
 *   <li>snap 到目标区域**上面那一层**的 1 1，等下面那两格被挖掉；</li>
 *   <li>snap 进目标区域**最下面那层**的 1 1；</li>
 *   <li>之后每到一个位置，就等「附近没东西可挖了」，然后挪到
 *       <b>目标区域内可达的最远一侧</b>：上下两格都是空气（能站人）、而且离当前位置笛卡尔距离最远、
 *       还得 snap 得过去。一直挖到目标区域清空为止；</li>
 *   <li>结束停掉 mine simple。</li>
 * </ol>
 *
 * <p>**并行**做工具耐久检查：每
 * {@code mine_complex_tool_durability_check_interval_ticks} tick 看一次手上那件工具，
 * 耐久低于 {@value #LOW_DURABILITY} 就把它和背包里同种类的备用工具换过来。
 */
class SingleStageStage2 extends SingleStageStep {

    /** 手上工具耐久低于这个值就换。 */
    static final int LOW_DURABILITY = 100;

    /** 等「下面两格被挖掉」最多等多久。 */
    private static final int MAX_DIG_WAIT_TICKS = 20 * 120;

    /**
     * 连续这么多 tick 没东西可挖，才算「这一格挖完了」。
     *
     * <p>1 = 一发现没得挖就准备挪窝，中间不留等待间隔。
     */
    private static final int QUIET_TICKS = 1;

    /** 落地之后至少待这么多 tick 再判断「挖完了」（给 mine simple 一点启动时间）。 */
    private static final int SETTLE_TICKS = 5;

    /** 一次挖掘阶段最多挪几次窝。 */
    private static final int MAX_MOVES = 64;

    /** 连着几次挪窝都没挖掉新方块，就认为卡住了。 */
    private static final int STUCK_LIMIT = 3;

    private enum Phase {
        /** 等下面两格被挖掉。 */
        WAIT_DOWN,
        /** 挖，挖不动了就挪窝。 */
        MINE,
        /** 收尾。 */
        END
    }

    private final int iteration;
    private final int bottom;
    private final int above;

    private Phase phase = Phase.WAIT_DOWN;
    @Nullable
    private Action current;
    private Phase afterChild = Phase.MINE;

    private int waitTicks;
    private int quietTicks;
    private int settledTicks;
    private int sinceDurabilityCheck;
    private int moves;
    private int stuck;
    private int lastRemaining = -1;

    /** 挪不过去的格子（snap 试过失败），后面挑位置时直接跳过。 */
    private final Set<Long> unreachable = new HashSet<>();
    /** 当前这个子动作是不是「挪到挖掘点」（是的话失败可以跳过，不用整段停掉）。 */
    private boolean movingToMine;
    /** 正在挪去的那个挖掘点。 */
    @Nullable
    private BlockPos moveTarget;
    private boolean mineStarted;
    private boolean warnedNoBlocks;

    SingleStageStage2(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.bottom = ctx.regionBottom(iteration);
        this.above = ctx.walkLayer(iteration);
    }

    @Override
    protected String phase() {
        return "第 " + (iteration + 1) + "/" + SingleStageCtx.ITERATIONS + " 层：挖目标区域";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }
        MineManager.get().startTransient(ctx.region(iteration));
        mineStarted = true;
        ChatUtils.info(phase() + "：mine simple 范围设为 " + ctx.region(iteration).describe());
        startChild(new SingleStageSnapAction(ctx, ctx.routePoint(above, 1, 1)), Phase.WAIT_DOWN);
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        watchDurability(mc.player);
        if (isFinished()) return;

        if (current != null) {
            current.update();
            if (!current.isFinished()) return;

            Action done = current;
            current = null;
            done.cleanup();
            if (done.failureReason() != null) {
                if (movingToMine && moveTarget != null) {
                    // 这个挖掘点走不过去：记下来，重新挑一个（不整段失败）
                    unreachable.add(moveTarget.asLong());
                    ChatUtils.debug(phase() + "：跳过走不到的 " + moveTarget.toShortString()
                        + "（" + done.failureReason() + "）");
                    movingToMine = false;
                    moveTarget = null;
                    phase = Phase.MINE;
                    settledTicks = 0;
                    quietTicks = 0;
                    return;
                }
                fail(done.failureReason());
                return;
            }
            phase = afterChild;
            movingToMine = false;
            moveTarget = null;
            settledTicks = 0;
            quietTicks = 0;
            return;
        }

        switch (phase) {
            case WAIT_DOWN -> {
                if (belowCleared(mc)) {
                    startChild(new SingleStageSnapAction(ctx, ctx.routePoint(bottom, 1, 1)),
                        Phase.MINE);
                    return;
                }
                if (++waitTicks > MAX_DIG_WAIT_TICKS) {
                    fail("等了 " + waitTicks + " tick，1 1 下面那两格还是没挖掉（mine simple 卡住了？）");
                }
            }
            case MINE -> tickMine(mc);
            case END -> {
                stopMining();
                finish();
            }
        }
    }

    @Override
    protected void onEnd() {
        if (current != null) {
            current.cleanup();
            current = null;
        }
        stopMining();
    }

    private void startChild(Action action, Phase next) {
        current = action;
        afterChild = next;
        action.start();
    }

    private void stopMining() {
        if (mineStarted) {
            MineManager.get().stop();
            mineStarted = false;
        }
    }

    // ------------------------------------------------------------------

    private void tickMine(MinecraftClient mc) {
        if (regionCleared(mc)) {
            ChatUtils.debug(phase() + "：目标区域清空了（挪了 " + moves + " 次窝）");
            phase = Phase.END;
            return;
        }

        settledTicks++;
        if (settledTicks < SETTLE_TICKS) return;

        if (MineManager.get().state() != MineManager.State.NO_BLOCKS) {
            quietTicks = 0;
            if (!warnedNoBlocks && MineManager.get().state() == MineManager.State.NO_SUITABLE_TOOL) {
                warnedNoBlocks = true;
                ChatUtils.error("这一格附近有方块要的工具快捷栏里没有，可能会剩下方块");
            }
            return;
        }

        if (++quietTicks < QUIET_TICKS) return;
        quietTicks = 0;
        settledTicks = 0;

        // 这一轮挖完了：看看有没有进展
        int remaining = countBlocks(mc);
        if (remaining >= lastRemaining && lastRemaining >= 0) {
            stuck++;
        } else {
            stuck = 0;
        }
        lastRemaining = remaining;

        if (stuck >= STUCK_LIMIT) {
            ChatUtils.error(phase() + "：连着挪了几次窝都没挖掉新方块，先停下（区域里还剩 "
                + remaining + " 个方块）");
            phase = Phase.END;
            return;
        }

        if (++moves > MAX_MOVES) {
            ChatUtils.error(phase() + "：挪了 " + MAX_MOVES + " 次窝还没挖完，先停下（还剩 "
                + remaining + " 个方块）");
            phase = Phase.END;
            return;
        }

        BlockPos next = farthestReachable(mc);
        if (next == null) {
            ChatUtils.error(phase() + "：已经没有能站着继续挖的位置了，先停下（区域里还剩 "
                + remaining + " 个方块）");
            phase = Phase.END;
            return;
        }
        ChatUtils.debug(phase() + "：挪到 " + next.toShortString() + "（还剩 " + remaining + " 个方块）");
        movingToMine = true;
        moveTarget = next;
        startChild(new SingleStageSnapAction(ctx, next), Phase.MINE);
    }

    /**
     * 目标区域内「能站人（上下两格都是空气）、snap 得过去、而且离当前位置最远」的那一格。
     *
     * @return 没有就返回 null
     */
    @Nullable
    private BlockPos farthestReachable(MinecraftClient mc) {
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) return null;

        record Stand(BlockPos pos, double distance) {
        }

        List<Stand> candidates = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos stand = ctx.routePoint(bottom, x, z);
                if (stand.equals(here)) continue;
                if (unreachable.contains(stand.asLong())) continue;
                if (!mc.world.getBlockState(stand).isAir()) continue;
                if (!mc.world.getBlockState(stand.up()).isAir()) continue;
                candidates.add(new Stand(stand, here.getSquaredDistance(stand)));
            }
        }
        candidates.sort(Comparator.comparingDouble(Stand::distance).reversed());

        for (Stand candidate : candidates) {
            if (SnapUtils.plan(mc, here, candidate.pos()) == null) continue;
            return candidate.pos();
        }
        return null;
    }

    /** 目标区域里还剩几个「mine simple 会挖」的方块（水 / 熔岩不算）。 */
    private int countBlocks(MinecraftClient mc) {
        int total = 0;
        for (BlockPos pos : ctx.regionCells(iteration)) {
            if (!breakable(mc.world.getBlockState(pos))) continue;
            total++;
        }
        return total;
    }

    private boolean regionCleared(MinecraftClient mc) {
        for (BlockPos pos : ctx.regionCells(iteration)) {
            if (breakable(mc.world.getBlockState(pos))) return false;
        }
        return true;
    }

    /** mine simple 眼里的「有东西可挖」：不是空气、不是流体。 */
    private static boolean breakable(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof FluidBlock);
    }

    /** 1 1 下面那两格是不是都空了。 */
    private boolean belowCleared(MinecraftClient mc) {
        return mc.world.getBlockState(ctx.routePoint(above - 1, 1, 1)).isAir()
            && mc.world.getBlockState(ctx.routePoint(above - 2, 1, 1)).isAir();
    }

    // ------------------------------------------------------------------
    // 工具耐久
    // ------------------------------------------------------------------

    private void watchDurability(ClientPlayerEntity player) {
        int interval = Math.max(1,
            PlayerControllerConfig.getMineComplexToolDurabilityCheckIntervalTicks());
        if (++sinceDurabilityCheck < interval) return;
        sinceDurabilityCheck = 0;

        int selected = player.getInventory().getSelectedSlot();
        if (selected < 0 || selected > 4) return;          // 只管剑镐斧铲锄这五格

        ItemStack held = player.getInventory().getStack(selected);
        if (held.isEmpty() || !held.isDamageable()) return;

        int remaining = held.getMaxDamage() - held.getDamage();
        if (remaining >= LOW_DURABILITY) return;

        int spare = SingleStageCtx.SPARE_START + selected;
        ItemStack spareStack = player.getInventory().getStack(spare);
        if (spareStack.isEmpty()) {
            ChatUtils.error("快捷栏第 " + (selected + 1) + " 格的工具只剩 " + remaining
                + " 耐久，但背包 " + (selected + 1) + " 格没有备用工具了");
            return;
        }

        if (SingleStageInventoryAction.swapNow(player, spare, selected)) {
            ChatUtils.info("工具只剩 " + remaining + " 耐久，已换成背包 " + (selected + 1) + " 格的备用工具");
        }
    }
}
