package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 (5) 步的**第二步**：用 mine simple 把目标区域挖空。
 *
 * <p>**和补粘液块走同一层**：站在目标区域**上面那一层**（走位层）挖，不下到区域里面去。
 * 这一层是清过的、不会有阻挡，所以**不做任何 snap 检查**，直接飞。
 *
 * <p>走位是**固定的 12 个点**，按顺序走，每个点挖到「附近没东西可挖了」就去下一个：
 * <pre>
 * 2 2 → 2 6 → 2 9 → 2 13 → 8 13 → 8 9 → 8 6 → 8 2 → 13 2 → 13 6 → 13 9 → 13 13
 * </pre>
 * 起点 2 2 和终点 13 13 都能直接从临时站立点 snap 过去，不需要跳板。
 *
 * <p>**并行**做工具耐久检查：每
 * {@code mine_complex_tool_durability_check_interval_ticks} tick 看一次手上那件工具，
 * 耐久低于 {@value #LOW_DURABILITY} 就把它和背包里同种类的备用工具换过来。
 */
class SingleStageStage2 extends SingleStageStep {

    /** 手上工具耐久低于这个值就换。 */
    static final int LOW_DURABILITY = 100;

    /** 走位点（相对目标子区块的 x / z，都在走位层上）。 */
    private static final int[][] WAYPOINTS = {
        {2, 2}, {2, 6}, {2, 9}, {2, 13},
        {8, 13}, {8, 9}, {8, 6}, {8, 2},
        {13, 2}, {13, 6}, {13, 9}, {13, 13}};

    /**
     * 连续这么多 tick 没东西可挖，才算「这一格挖完了」。
     *
     * <p>1 = 一发现没得挖就走下一个点，中间不留等待间隔。
     */
    private static final int QUIET_TICKS = 1;

    /** 落地之后至少待这么多 tick 再判断「挖完了」（给 mine simple 一点启动时间）。 */
    private static final int SETTLE_TICKS = 5;

    private enum Phase {
        /** 挖，挖不动了就去下一个点。 */
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
    private int index;
    private boolean mineStarted;
    private boolean warnedNoBlocks;

    SingleStageStage2(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.walkLayer = ctx.walkLayer(iteration);
    }

    @Override
    protected String phase() {
        return "第 " + (iteration + 1) + "/" + ctx.levels() + " 层：挖目标区域";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }
        // 重力方块保护：上面还压着重力方块的那个先不挖（先挖最上面那个，落下来再挖）
        MineManager.get().startTransient(ctx.region(iteration), true);
        mineStarted = true;
        ChatUtils.info(phase() + "：mine simple 范围设为 " + ctx.region(iteration).describe());
        index = 0;
        startChild(new SingleStageSnapAction(ctx, waypoint(0)), Phase.MINE);
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
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

    private void tickMine(Minecraft mc) {
        if (regionCleared(mc)) {
            ChatUtils.debug(phase() + "：目标区域清空了（走到第 " + (index + 1) + " 个点）");
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

        // 这个点挖完了：去下一个
        index++;
        if (index >= WAYPOINTS.length) {
            int left = remainingBlocks(mc).size();
            if (left > 0) {
                ChatUtils.error(phase() + "：12 个点都走完了，区域里还剩 " + left + " 个方块");
            }
            phase = Phase.END;
            return;
        }

        BlockPos next = waypoint(index);
        ChatUtils.debug(phase() + "：去第 " + (index + 1) + " 个点 " + next.toShortString());
        startChild(new SingleStageSnapAction(ctx, next), Phase.MINE);
    }

    /** 第 {@code i} 个走位点（走位层上）。 */
    private BlockPos waypoint(int i) {
        return ctx.routePoint(walkLayer, WAYPOINTS[i][0], WAYPOINTS[i][1]);
    }

    /** 区域里还没挖掉的方块（mine simple 眼里的「有东西」：非空气、非流体）。 */
    private List<BlockPos> remainingBlocks(Minecraft mc) {
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos pos : ctx.regionCells(iteration)) {
            if (breakable(mc.level.getBlockState(pos))) left.add(pos);
        }
        return left;
    }

    private boolean regionCleared(Minecraft mc) {
        for (BlockPos pos : ctx.regionCells(iteration)) {
            if (breakable(mc.level.getBlockState(pos))) return false;
        }
        return true;
    }

    /** mine simple 眼里的「有东西可挖」：不是空气、不是流体。 */
    private static boolean breakable(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    // ------------------------------------------------------------------
    // 工具耐久
    // ------------------------------------------------------------------

    private void watchDurability(LocalPlayer player) {
        int interval = Math.max(1,
            PlayerControllerConfig.getMineComplexToolDurabilityCheckIntervalTicks());
        if (++sinceDurabilityCheck < interval) return;
        sinceDurabilityCheck = 0;

        int selected = player.getInventory().getSelectedSlot();
        if (selected < 0 || selected > 4) return;          // 只管剑镐斧铲锄这五格

        ItemStack held = player.getInventory().getItem(selected);
        if (held.isEmpty() || !held.isDamageableItem()) return;

        int remaining = held.getMaxDamage() - held.getDamageValue();
        if (remaining >= LOW_DURABILITY) return;

        int spare = SingleStageCtx.SPARE_START + selected;
        ItemStack spareStack = player.getInventory().getItem(spare);
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
