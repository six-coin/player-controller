package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /pc mine debug_chunk_miner <item_collector_list>} 的执行体：挖掉**当前子区块正下方**那个子区块。
 *
 * <p><b>这是个临时的调试功能</b>（以后会删），所以整体结构就是「按需求的顺序把几段串起来」：
 * <pre>
 * 检查 (1)(2) → 搭工作站 (3)(4) → 8 轮 { 第一步 含水方块 → 第二步 mine simple → 第三步 捡东西+卸货 }
 *            → 拆工作站 (6) → 补货检查 (7)
 * </pre>
 *
 * <p>每一段都是一个独立的动作（见 {@code SingleStage*}），主控只负责按顺序跑它们、把失败往上带。
 * 一共 8 轮，每轮挖目标子区块里的 2 层（从 14~15 一直挖到 0~1）。
 */
public class MineComplexSingleStageN extends Action {

    /** 收集清单：item_collector 里那些物品的 id。 */
    private final Set<String> collectorItems;

    private SingleStageCtx ctx;

    private Action child;
    private Runnable afterChild;
    private int iteration;
    private int totalTicks;
    private boolean flying;

    /** 整个任务最久（20 tick = 1 秒；这里给 6 小时）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60 * 6;

    public MineComplexSingleStageN(Collection<String> collectorItems) {
        this.collectorItems = new LinkedHashSet<>(collectorItems);
    }

    @Override
    public String name() {
        return "单段挖掘（debug_chunk_miner）";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockPos initialMin = new BlockPos(
            player.getBlockX() & ~15, player.getBlockY() & ~15, player.getBlockZ() & ~15);

        SingleStageData data = new SingleStageData();
        data.refreshInventory(player.getInventory());

        ctx = new SingleStageCtx(DimensionUtils.current(), initialMin,
            MineFilterConfig.get().filter(), collectorItems, data);

        List<String> problems = new ArrayList<>(SingleStageCheck.checkSubChunks(mc.world, ctx));
        problems.addAll(SingleStageCheck.checkInventory(ctx));
        if (!problems.isEmpty()) {
            ChatUtils.error("单段挖掘不能开始（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.error("  · " + problem);
            fail("检查没通过（" + problems.size() + " 条，见聊天栏）");
            return;
        }

        ChatUtils.info("单段挖掘开始：" + ctx.describe() + "；收集 "
            + (collectorItems.isEmpty() ? "（空）" : String.join("、", collectorItems)));

        // 全程悬停：这段任务一直在半空中干活（临时站立点下面是空气），
        // 只要不每 tick 把速度写成 0，玩家就会掉下去。移动的时候由 MoveAction 自己写速度。
        FlightVelocity.begin();
        flying = true;
        run(new SingleStageSetup(ctx), this::runIteration);
    }

    @Override
    protected void tick() {
        if (child == null) {
            fail("内部状态不对：没有正在跑的步骤");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("单段挖掘超时（" + totalTicks + " tick）");
            return;
        }

        // 先清零再让子动作跑：移动类子动作会在同一 tick 里把速度写成它要的值
        FlightVelocity.apply(Vec3d.ZERO);

        child.update();
        if (!child.isFinished()) return;

        Action done = child;
        Runnable next = afterChild;
        child = null;
        afterChild = null;

        done.cleanup();
        if (done.failureReason() != null) {
            fail(done.failureReason());
            return;
        }
        if (next != null) next.run();
    }

    @Override
    protected void onEnd() {
        if (child != null) {
            child.cleanup();
            child = null;
        }
        if (flying) {
            FlightVelocity.end();
            flying = false;
        }
    }

    // ------------------------------------------------------------------
    // 流程
    // ------------------------------------------------------------------

    private void run(Action action, Runnable next) {
        child = action;
        afterChild = next;
        action.start();
    }

    private void runIteration() {
        if (iteration >= SingleStageCtx.ITERATIONS) {
            runTeardown();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            fail("世界不存在");
            return;
        }

        if (regionEmpty(mc, iteration)) {
            ChatUtils.info("第 " + (iteration + 1) + "/" + SingleStageCtx.ITERATIONS
                + " 层整个区域都是空气，跳过");
            afterIteration();
            return;
        }

        run(new SingleStageStage1(ctx, iteration), this::runStage2);
    }

    private void runStage2() {
        run(new SingleStageStage2(ctx, iteration), this::runStage3);
    }

    private void runStage3() {
        run(new SingleStageStage3(ctx, iteration), this::afterIteration);
    }

    private void afterIteration() {
        iteration++;
        runIteration();
    }

    private void runTeardown() {
        run(new SingleStageTeardown(ctx), this::restockCheck);
    }

    /** 需求 (7)：再按 (2) 检查一遍物品栏，看看要不要补货。 */
    private void restockCheck() {
        ctx.refresh();
        List<String> problems = SingleStageCheck.checkInventory(ctx);
        if (problems.isEmpty()) {
            ChatUtils.info("补货检查：物品栏仍然符合要求 —— §a无需补货");
        } else {
            ChatUtils.info("补货检查：§c需要补货§r（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.info("  · " + problem);
        }
        finish();
    }

    /** 这一轮的目标区域是不是全是空气。 */
    private boolean regionEmpty(MinecraftClient mc, int round) {
        for (BlockPos pos : ctx.regionCells(round)) {
            if (!mc.world.getBlockState(pos).isAir()) return false;
        }
        return true;
    }
}
