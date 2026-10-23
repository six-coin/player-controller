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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第 (5) 步的**第二步**：用 mine simple 把目标区域挖空。
 *
 * <p>**和补粘液块走同一层**：站在目标区域**上面那一层**（走位层）挖，不下到区域里面去。
 * 实测从高两格挖完全没问题（mine simple 的触及距离够），而且这一层是清过的、不会有阻挡，
 * 所以**不做任何 snap 检查**，直接飞。
 *
 * <ol>
 *   <li>把 mine simple 的范围设成目标区域（{@link MineManager#startTransient}，不写 mine.json）；</li>
 *   <li>snap 到走位层的 1 1；</li>
 *   <li>挖到「附近没东西可挖了」就挪窝：挑**还能挖到东西的、离当前位置最远**的那一格
 *       （走位层 16×16 都是空气，随便挑）；</li>
 *   <li>目标区域里没有 mine simple 会挖的方块了 → 停掉 mine simple。</li>
 * </ol>
 *
 * <p>**并行**做工具耐久检查：每
 * {@code mine_complex_tool_durability_check_interval_ticks} tick 看一次手上那件工具，
 * 耐久低于 {@value #LOW_DURABILITY} 就把它和背包里同种类的备用工具换过来。
 */
class SingleStageStage2 extends SingleStageStep {

    /** 手上工具耐久低于这个值就换。 */
    static final int LOW_DURABILITY = 100;

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

    /**
     * 「站在这一格还能挖到」的水平半径（格）。
     *
     * <p>人站在走位层，眼睛比区域底面高 3 格多，触及 4.5 格 → 水平能覆盖半径 3 左右。
     * 用来判断某个走位格还值不值得去。
     */
    private static final int REACH = 3;

    private enum Phase {
        /** 挖，挖不动了就挪窝。 */
        MINE,
        /** 收尾。 */
        END
    }

    private final int iteration;
    private final int walkLayer;

    private Phase phase = Phase.MINE;
    @Nullable
    private Action current;
    private Phase afterChild = Phase.MINE;

    private int quietTicks;
    private int settledTicks;
    private int sinceDurabilityCheck;
    private int moves;
    private int stuck;
    private int lastRemaining = -1;
    private boolean mineStarted;
    private boolean warnedNoBlocks;

    SingleStageStage2(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.walkLayer = ctx.walkLayer(iteration);
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
        startChild(new SingleStageSnapAction(ctx, ctx.routePoint(walkLayer, 1, 1)), Phase.MINE);
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
                fail(done.failureReason());
                return;
            }
            phase = afterChild;
            settledTicks = 0;
            quietTicks = 0;
            return;
        }

        switch (phase) {
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
        List<BlockPos> remaining = remainingBlocks(mc);
        int count = remaining.size();
        if (count >= lastRemaining && lastRemaining >= 0) {
            stuck++;
        } else {
            stuck = 0;
        }
        lastRemaining = count;

        if (stuck >= STUCK_LIMIT) {
            ChatUtils.error(phase() + "：连着挪了几次窝都没挖掉新方块，先停下（区域里还剩 "
                + count + " 个方块）");
            phase = Phase.END;
            return;
        }

        if (++moves > MAX_MOVES) {
            ChatUtils.error(phase() + "：挪了 " + MAX_MOVES + " 次窝还没挖完，先停下（还剩 "
                + count + " 个方块）");
            phase = Phase.END;
            return;
        }

        BlockPos next = farthestUseful(mc, remaining);
        if (next == null) {
            if (count > 0) {
                ChatUtils.error(phase() + "：走位层上已经没有能挖到东西的位置了，先停下（区域里还剩 "
                    + count + " 个方块）");
            }
            phase = Phase.END;
            return;
        }
        ChatUtils.debug(phase() + "：挪到 " + next.toShortString() + "（还剩 " + count + " 个方块）");
        startChild(new SingleStageSnapAction(ctx, next), Phase.MINE);
    }

    /**
     * 走位层里「还能挖到东西、而且离当前位置最远」的那一格。
     *
     * <p>走位层是清过的（全是空气），所以不做 snap 检查，直接按距离挑。
     *
     * @param remaining 区域里还没挖掉的方块
     * @return 没有就返回 null（说明剩下的方块从走位层哪个位置都够不着了）
     */
    @Nullable
    private BlockPos farthestUseful(MinecraftClient mc, List<BlockPos> remaining) {
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null || remaining.isEmpty()) return null;

        record Stand(BlockPos pos, double distance) {
        }

        List<Stand> candidates = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos stand = ctx.routePoint(walkLayer, x, z);
                if (stand.equals(here)) continue;
                if (!useful(stand, remaining)) continue;
                candidates.add(new Stand(stand, here.getSquaredDistance(stand)));
            }
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(Stand::distance).reversed());
        return candidates.get(0).pos();
    }

    /** 站在 {@code stand} 还能挖到 {@code remaining} 里的方块吗。 */
    private static boolean useful(BlockPos stand, List<BlockPos> remaining) {
        for (BlockPos pos : remaining) {
            int dx = pos.getX() - stand.getX();
            int dz = pos.getZ() - stand.getZ();
            if (dx * dx + dz * dz <= REACH * REACH) return true;
        }
        return false;
    }

    /** 区域里还没挖掉的方块（mine simple 眼里的「有东西」：非空气、非流体）。 */
    private List<BlockPos> remainingBlocks(MinecraftClient mc) {
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos pos : ctx.regionCells(iteration)) {
            if (breakable(mc.world.getBlockState(pos))) left.add(pos);
        }
        return left;
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
