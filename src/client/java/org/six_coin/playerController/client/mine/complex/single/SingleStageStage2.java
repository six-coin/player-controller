package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 (5) 步的**第二步**：用 mine simple 把目标区域挖空。
 *
 * <p>流程：
 * <ol>
 *   <li>把 mine simple 的范围设成目标区域（{@link MineManager#startTransient}，不写 mine.json）；</li>
 *   <li>snap 到目标区域上面那一层的 14 14，等下面那两格被挖掉；</li>
 *   <li>snap 进目标区域最下面那层的 14 14，然后按路线一格一格走，
 *       每格等到「这一格附近没东西可挖了」再走下一格；</li>
 *   <li>走完停掉 mine simple。</li>
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

    /** 连续这么多 tick 没东西可挖，才算「这一格挖完了」。 */
    private static final int QUIET_TICKS = 5;

    private enum Phase {
        START,
        SNAP_ABOVE,
        WAIT_DOWN,
        SNAP_IN,
        WALK,
        END
    }

    private final int iteration;
    private final int bottom;
    private final int above;
    private final List<BlockPos> route;

    private Phase phase = Phase.START;
    @Nullable
    private Action current;

    private int waitTicks;
    private int quietTicks;
    private int cellIndex;
    private int sinceDurabilityCheck;
    private boolean mineStarted;
    private boolean warnedNoBlocks;

    SingleStageStage2(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.bottom = ctx.regionBottom(iteration);
        this.above = ctx.walkLayer(iteration);
        this.route = ctx.routeCells(bottom);
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
            }
            return;
        }

        switch (phase) {
            case START -> {
                MineManager.get().startTransient(ctx.region(iteration));
                mineStarted = true;
                ChatUtils.info(phase() + "：mine simple 范围设为 " + ctx.region(iteration).describe());
                phase = Phase.SNAP_ABOVE;
                startChild(new SingleStageSnapAction(ctx,
                    ctx.routePoint(above, 14, 14)));
            }
            case SNAP_ABOVE -> {
                phase = Phase.WAIT_DOWN;
                waitTicks = 0;
            }
            case WAIT_DOWN -> {
                if (belowCleared(mc)) {
                    phase = Phase.SNAP_IN;
                    startChild(new SingleStageSnapAction(ctx, ctx.routePoint(bottom, 14, 14)));
                    return;
                }
                if (++waitTicks > MAX_DIG_WAIT_TICKS) {
                    fail("等了 " + waitTicks + " tick，14 14 下面那两格还是没挖掉（mine simple 卡住了？）");
                }
            }
            case SNAP_IN -> {
                phase = Phase.WALK;
                cellIndex = 0;
                quietTicks = 0;
                startChild(new SingleStageSnapAction(ctx, route.get(0)));
            }
            case WALK -> tickWalk();
            case END -> {
                if (mineStarted) {
                    MineManager.get().stop();
                    mineStarted = false;
                }
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
        if (mineStarted) {
            MineManager.get().stop();
            mineStarted = false;
        }
    }

    // ------------------------------------------------------------------

    private void tickWalk() {
        if (quietTicks < QUIET_TICKS) {
            if (MineManager.get().state() == MineManager.State.NO_BLOCKS) {
                quietTicks++;
            } else {
                quietTicks = 0;
                if (!warnedNoBlocks && MineManager.get().state() == MineManager.State.NO_SUITABLE_TOOL) {
                    warnedNoBlocks = true;
                    ChatUtils.error("这一格附近有方块要的工具快捷栏里没有，可能会剩下方块");
                }
            }
            return;
        }

        quietTicks = 0;
        cellIndex++;
        if (cellIndex >= route.size()) {
            phase = Phase.END;
            return;
        }
        startChild(new SingleStageSnapAction(ctx, route.get(cellIndex)));
    }

    private void startChild(Action action) {
        current = action;
        action.start();
    }

    /** 14 14 下面那两格是不是都空了。 */
    private boolean belowCleared(MinecraftClient mc) {
        BlockPos upper = ctx.routePoint(above - 1, 14, 14);
        BlockPos lower = ctx.routePoint(above - 2, 14, 14);
        return mc.world.getBlockState(upper).isAir() && mc.world.getBlockState(lower).isAir();
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
