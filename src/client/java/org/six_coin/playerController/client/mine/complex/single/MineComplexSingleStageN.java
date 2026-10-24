package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc mine debug_chunk_miner <detail>} 的执行体：挖掉**当前子区块正下方**那个子区块。
 *
 * <p><b>这是个临时的调试功能</b>（以后会删），所以整体结构就是「按需求的顺序把几段串起来」：
 * <pre>
 * 检查 (1)(2) → 搭工作站 (3)(4) → levels 轮 {
 *     补圆石墙（detail 里配成 cobblestone 的方向）→ 第一步 含水方块 → 第二步 mine simple
 *     → 第三步 捡东西+卸货 }
 *            → 拆工作站 (6) → 补货检查 (7)
 * </pre>
 *
 * <p>每一段都是一个独立的动作（见 {@code SingleStage*}），主控只负责按顺序跑它们、把失败往上带。
 * 一共 {@code detail.levels} 轮，每轮挖目标子区块里的 2 层（从 14~15 一直挖到 0~1）。
 */
public class MineComplexSingleStageN extends Action {

    /** {@code detail}：收集清单、五个方向怎么填、挖几层。 */
    private final SingleStageDetail detail;

    private SingleStageCtx ctx;

    private Action child;
    private Runnable afterChild;
    private int iteration;
    private int totalTicks;
    private boolean flying;

    /**
     * 这一趟跑完之后要不要补货（物品栏已经不符合 {@link SingleStageCheck#checkInventory} 的要求了）。
     *
     * <p>给 {@code d_start_mining} 用：它每次挖完一个子区块就问一次
     * （见 {@link #needsResupply()}），需要补货就先回站里补一趟再接着挖。
     */
    private boolean needsResupply;

    /** 整个任务最久（20 tick = 1 秒；这里给 6 小时）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60 * 6;

    public MineComplexSingleStageN(SingleStageDetail detail) {
        this.detail = detail;
    }

    @Override
    public String name() {
        return "单段挖掘（debug_chunk_miner）";
    }

    /**
     * 这一趟跑完要不要补货。
     *
     * <p>只有正常跑完才有意义（{@link #restockCheck()} 里算的）；中途失败的话整个任务就停了，
     * 调用方看 {@link #failureReason()}。
     */
    public boolean needsResupply() {
        return needsResupply;
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockPos initialMin = new BlockPos(
            player.getBlockX() & ~15, player.getBlockY() & ~15, player.getBlockZ() & ~15);

        SingleStageData data = new SingleStageData();
        data.refreshInventory(player.getInventory());

        ctx = new SingleStageCtx(DimensionUtils.current(), initialMin,
            MineFilterConfig.get().filter(), detail, data);

        // 全程悬停：这段任务一直在半空中干活（临时站立点下面是空气），
        // 只要不每 tick 把速度写成 0，玩家就会掉下去。移动的时候由 MoveAction 自己写速度。
        // 检查 / 扔垃圾那几步也在这里面（玩家本来就站在半空）。
        FlightVelocity.begin();
        flying = true;

        checkThenStart();
    }

    /**
     * 先检查一遍；只有「快捷栏 6~8 里有东西」这一类问题的话，**就地扔掉**再查一遍。
     *
     * <p>为什么会有东西：上一段拆平台的时候会吸上来几块圆石（快捷栏 6~8 是临时空位），
     * 拆完扔掉之后，往下飞的时候又会顺手捡回来 —— 这一趟开始前再扔一次最省事。
     * 扔在脚下没关系：它会掉进这一层要挖的区域里，第三步捡掉落物的时候就收走了。
     */
    private void checkThenStart() {
        List<String> problems = problems();
        if (problems.isEmpty()) {
            startMining();
            return;
        }

        List<Integer> junk = junkSlots();
        if (!junk.isEmpty()) {
            ChatUtils.info("物品栏 " + junk + " 格里有不该有的东西，先低头扔掉再查一遍");
            run(new SingleStageThrowAction(ctx, junk, "快捷栏 6~8"), this::afterThrow);
            return;
        }

        report(problems);
    }

    private void afterThrow() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            report(problems);
            return;
        }
        startMining();
    }

    /** 该空着的格子（快捷栏 6~8）里有东西的那几格。 */
    private List<Integer> junkSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = SingleStageCtx.HOTBAR_FREE_1; slot <= SingleStageCtx.HOTBAR_FREE_3; slot++) {
            if (!ctx.data.isEmpty(slot)) slots.add(slot);
        }
        return slots;
    }

    private List<String> problems() {
        Minecraft mc = Minecraft.getInstance();
        ctx.refresh();
        List<String> problems = new ArrayList<>(SingleStageCheck.checkSubChunks(mc.level, ctx));
        problems.addAll(SingleStageCheck.checkReach());
        problems.addAll(SingleStageCheck.checkInventory(ctx));
        return problems;
    }

    private void report(List<String> problems) {
        ChatUtils.error("单段挖掘不能开始（" + problems.size() + " 条）：");
        for (String problem : problems) ChatUtils.error("  · " + problem);
        fail("检查没通过（" + problems.size() + " 条，见聊天栏）");
    }

    private void startMining() {
        ChatUtils.info("单段挖掘开始：" + ctx.describe() + "；" + detail.describe());

        // debug_chunk_miner 专用工具策略（「空手」= 快捷栏 9）：
        // 每种方块只判断一次、只缓存在内存里，任务结束就清掉（见 MineManager#setDebugToolPolicy）
        MineManager.get().setDebugToolPolicy(detail.itemCollectorList());
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
        FlightVelocity.apply(Vec3.ZERO);

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
        // debug 工具策略只在这一轮任务里有效
        MineManager.get().clearDebugToolPolicy();
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
        if (iteration >= ctx.levels()) {
            runTeardown();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            fail("世界不存在");
            return;
        }

        if (regionEmpty(mc, iteration)) {
            ChatUtils.info("第 " + (iteration + 1) + "/" + ctx.levels()
                + " 层整个区域都是空气，跳过");
            afterIteration();
            return;
        }

        // 先用圆石把配置成 cobblestone 的那几面墙 / 底面填上，再补黏液块
        run(new SingleStageCobblePass(ctx, iteration), this::runStage1);
    }

    private void runStage1() {
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
        // 挖目标子区块的阶段结束了：debug 工具策略只服务于「挖区域」，
        // 拆工作站那一段要正常挖圆石（平台），不能被它判成「不用挖」
        MineManager.get().clearDebugToolPolicy();
        run(new SingleStageTeardown(ctx), this::throwLeftovers);
    }

    /** 拆完平台，快捷栏 6~8（临时空位）基本一定会吸上来几块圆石：低头扔掉，再去做补货检查。 */
    private void throwLeftovers() {
        run(new SingleStageThrowAction(ctx,
            List.of(SingleStageCtx.HOTBAR_FREE_1, SingleStageCtx.HOTBAR_FREE_2,
                SingleStageCtx.HOTBAR_FREE_3), "快捷栏 6~8"),
            this::restockCheck);
    }

    /** 需求 (7)：再按 (2) 检查一遍物品栏，看看要不要补货。 */
    private void restockCheck() {
        ctx.refresh();
        List<String> problems = SingleStageCheck.checkInventory(ctx);
        needsResupply = !problems.isEmpty();
        if (problems.isEmpty()) {
            ChatUtils.info("补货检查：物品栏仍然符合要求 —— §a无需补货");
        } else {
            ChatUtils.info("补货检查：§c需要补货§r（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.info("  · " + problem);
        }
        finish();
    }

    /** 这一轮的目标区域是不是全是空气。 */
    private boolean regionEmpty(Minecraft mc, int round) {
        for (BlockPos pos : ctx.regionCells(round)) {
            if (!mc.level.getBlockState(pos).isAir()) return false;
        }
        return true;
    }
}
