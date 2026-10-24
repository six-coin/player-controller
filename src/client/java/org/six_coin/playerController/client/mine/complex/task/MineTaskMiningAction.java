package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.eat.EatConfig;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.mend.MendConfig;
import org.six_coin.playerController.client.mend.MendManager;
import org.six_coin.playerController.client.mend.MendPos;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.mine.MineTools;
import org.six_coin.playerController.client.mine.complex.MineTaskRegion;
import org.six_coin.playerController.client.mine.complex.single.MineComplexSingleStageN;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCheck;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCtx;
import org.six_coin.playerController.client.mine.complex.single.SingleStageData;
import org.six_coin.playerController.client.mine.complex.single.SingleStageDetail;
import org.six_coin.playerController.client.mine.station.MinePlace;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.mine.station.MineStationPart;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.SnapUtils;
import org.six_coin.playerController.client.waypoint.PathPlanner;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /pc mine task <task_name> d_start_mining} 的执行体：把整个挖掘区按区块挖空。
 *
 * <p>三段（需求原文的顺序）：
 * <ol>
 *   <li><b>预检查</b>：人在安全点、对齐、region.json 的 {@code inspected} / {@code checked}、
 *       清空 mine simple、自动进食、物品栏里正好是「快捷栏 9 个空潜影盒 + 背包第 1 格 1 个熔岩桶」、
 *       关掉容器缓存、mend 站立点和安全点双向可达、读 {@code check.json} 当 {@code station_data}；</li>
 *   <li><b>准备物品栏</b>：按 {@link MineTaskKit} 去各个容器里拿货，凑成
 *       {@code debug_chunk_miner} 要求的那一套（工具、食物、图腾、圆石盒、黏液块盒、空盒、熔岩桶）；</li>
 *   <li><b>逐个区块挖</b>：区块按 x 再 z 从小到大排；每个区块从上往下找第一个没挖干净的子区块，
 *       snap 到它上面那个子区块的 {@code 6 4 6}，跑一遍 {@link MineComplexSingleStageN}
 *       （就是 {@code debug_chunk_miner} 的实现），挖完再看下一个子区块 / 下一个区块。</li>
 * </ol>
 *
 * <p><b>区块和子区块的坐标</b>：区块起始点 = {@code (区块最小 x, y2+1, 区块最小 z)}；
 * 挖掘区起始点 = {@code (x1, y2+1, z1)}。子区块从上往下按 16 对齐切，最下面那个可能比 y1 低
 * （那部分不挖，所以 {@code levels} 会小于 8，{@code bottom} 用圆石）。
 *
 * <p><b>区块之间 / 子区块之间的移动</b>用 {@link MineTaskSpecialSnapAction}：不查「目的地加载了没有」、
 * 也不查路通不通 —— 整个区域上面那 16 层被 {@code b_inspect_region} 保证是空气，一定飞得过去。
 *
 * <p><b>补货</b>：每次挖完一个子区块，如果 {@link MineComplexSingleStageN#needsResupply()} 说要补，
 * 或者身上有盔甲耐久低于 {@value #ARMOR_MIN_DURABILITY}，就回区块起始点 → 挖掘区起始点 →
 * {@link MineTaskResupplyAction}（<b>这一轮是占位</b>）→ 再回去接着挖这个区块。
 */
public class MineTaskMiningAction extends Action {

    /** 整个任务最久（24 小时）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60 * 24;

    /** 区块一直没加载的话，最多等这么久（30 秒）。 */
    private static final int CHUNK_LOAD_WAIT_TICKS = 20 * 30;

    /** 「不在路径点网络上 → snap 回网络」最多试几次。 */
    private static final int MAX_NETWORK_SNAPS = 5;

    /** 盔甲耐久低于这个数就去补货（修装备）。 */
    public static final int ARMOR_MIN_DURABILITY = 100;

    private enum Phase {
        /** 备货中（第二阶段）。 */
        PREPARE,
        /** 走去挖掘区起始点。 */
        TRAVEL,
        /** 等区块加载（每 tick 重试扫描）。 */
        WAIT_CHUNK,
        IDLE
    }

    private final String task;
    private final MineTaskRegion region;

    private MineTaskStationData stationData;
    private final Set<String> collectorItems = new LinkedHashSet<>();
    private List<BlockPos> chunkStarts = List.of();
    private int chunkIndex;
    private BlockPos regionStart;

    /** 当前区块的起始点 / 当前要挖的子区块（最小角）。 */
    private BlockPos chunkStart;
    private BlockPos subChunk;

    private List<MineTaskKit.Slot> kit = List.of();
    private int kitIndex;
    @Nullable
    private MineComplexSingleStageN miner;

    private Action child;
    private Runnable afterChild;
    private Phase phase = Phase.IDLE;
    private int totalTicks;
    private int loadWaitTicks;
    private boolean flying;
    /** 「不在路径点网络上 → snap 回网络」用了几次（防止死循环）。 */
    private int networkSnaps;

    public MineTaskMiningAction(String task, MineTaskRegion region) {
        this.task = task;
        this.region = region;
    }

    @Override
    public String name() {
        return "挖掘任务 " + task;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!precheck(mc, player)) return;

        // 全程悬停：这一段一直在半空中干活（区块起始点下面是挖空的区域），
        // 只要不每 tick 把速度写成 0，玩家就会掉下去。移动的时候由 MoveAction 自己写速度。
        FlightVelocity.begin();
        flying = true;

        // 备货期间要开一堆容器、自己点格子，容器缓存的自动刷新会认错容器，先挂起
        ContainerCacheTracker.setSuspended(true);

        ChatUtils.info("挖掘任务 " + task + " 开始：" + region.describe());
        ChatUtils.info("区块顺序：x 从小到大、z 从小到大，共 " + chunkStarts.size() + " 个；"
            + "挖掘区起始点 " + regionStart.toShortString());

        run(MoveAction.alignToCenter(), this::startRearrange);
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("挖掘任务超时（" + totalTicks + " tick）");
            return;
        }

        if (child != null) {
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
            return;
        }

        FlightVelocity.apply(Vec3.ZERO);
        if (phase == Phase.WAIT_CHUNK) scanChunk();
    }

    @Override
    protected void onEnd() {
        if (child != null) {
            child.cleanup();
            child = null;
        }
        miner = null;

        // 挖矿用的临时任务不要留到任务外面
        MineManager.get().cancel();

        if (flying) {
            FlightVelocity.end();
            flying = false;
        }
        ContainerCacheTracker.setSuspended(false);
    }

    private void run(Action action, Runnable next) {
        child = action;
        afterChild = next;
        action.start();
    }

    // ------------------------------------------------------------------
    // 第一阶段：预检查
    // ------------------------------------------------------------------

    /** 需求第一阶段的 10 条。任何一条不过就 {@code fail}。 */
    private boolean precheck(Minecraft mc, LocalPlayer player) {
        // 1. 人在安全点
        MinePlace safe = MineStationManager.get().single(MineStationPart.SAFE_POINT);
        if (safe == null) {
            fail("没有设置安全点（/pc mine station safe_point set …）");
            return false;
        }
        if (!safe.dimension().equals(DimensionUtils.current())) {
            fail("安全点在 " + DimensionUtils.display(safe.dimension()) + "，你现在在 "
                + DimensionUtils.display(DimensionUtils.current()));
            return false;
        }
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null || !here.equals(safe.pos())) {
            fail("你现在不在安全点（应该在 " + safe.pos().toShortString() + "，实际在 "
                + (here == null ? "？" : here.toShortString()) + "）");
            return false;
        }

        // 3. region.json
        if (!region.dimension().equals(DimensionUtils.current())) {
            fail("挖掘区在 " + DimensionUtils.display(region.dimension()) + "，你现在在 "
                + DimensionUtils.display(DimensionUtils.current()));
            return false;
        }
        if (!region.inspected()) {
            fail("region.json 里 inspected 不是 true —— 先跑 /pc mine task " + task + " b_inspect_region");
            return false;
        }
        if (!region.checked()) {
            fail("region.json 里 checked 不是 true —— 先跑 /pc mine task " + task + " c_station_check");
            return false;
        }

        // 3.5 挖掘区起始点 (x1, y2+1, z1) 必须是一个**路径点**（不只是压在边上）：
        //     区块之间 / 子区块之间的 snap 都从这里出发，是边中间的话服务端那边不稳
        if (!checkStartWaypoint()) return false;

        // 4. 清空 mine simple 的区域（不记录，之后也不用设回去）
        if (MineManager.get().cancel()) {
            ChatUtils.info("已清空 mine simple 的区域");
        }

        // 5. 白名单默认值（缺文件时是 ["minecraft:air"]）由 MineFilterConfig 保证；
        //    这里不特判空气，一切按当前过滤器来
        ChatUtils.debug("挖掘过滤器：" + MineFilterConfig.get().describe(MineFilterConfig.get().mode()));

        // 2.5 路径点编辑模式关掉：挖矿全程到处飞，开着编辑模式会把飞过的每一格都记成路径点
        if (WaypointManager.get().isEditMode()) {
            WaypointManager.get().setEditMode(false);
            ChatUtils.info("已关闭路径点编辑模式（挖矿过程中不记路）");
        }

        // 6. 自动进食必须开着（没有它就挖到饿死）
        if (!EatConfig.get().enabled()) {
            EatConfig.get().setEnabled(true);
            ChatUtils.info("自动进食原本是关的，已经打开（/pc eat toggle 可以关掉）");
        }

        // 7. 物品栏：9 个空潜影盒（快捷栏 1~9）+ 1 个熔岩桶（背包 1），别的都不能有。
        //    盔甲不算（穿在身上，耐久低了会去修）。搬到位在下面 startRearrange() 里做。
        List<String> inventoryProblems = inventoryProblems(player);
        if (!inventoryProblems.isEmpty()) {
            fail("物品栏不对（" + inventoryProblems.size() + " 条）：" + String.join("；", inventoryProblems));
            return false;
        }

        // 8.5 补货要修工具，attack 里必须有 mend 的 profile
        if (!org.six_coin.playerController.client.attack.AttackProfiles.exists(MendManager.PROFILE)) {
            fail("attack 里没有叫「" + MendManager.PROFILE + "」的 profile —— 补货修工具要用它"
                + "（先 /pc attack profile add " + MendManager.PROFILE + " 建一份）");
            return false;
        }

        // 9. mend 站立点 + 和安全点双向可达
        if (!checkMend(safe)) return false;

        // 10. 读 check.json 当 station_data（不落盘）
        stationData = MineTaskStationData.load(MineStationManager.get(),
            MineStationManager.get().currentCheckFile());
        if (stationData == null) {
            fail("读不到 station_data（check.json 不存在或者没通过，见聊天栏）");
            return false;
        }

        // 收集清单 = 当前 item_collector 里所有产物（可以是空的）
        collectorItems.clear();
        for (MinePlace place : MineStationManager.get().positions(MineStationPart.ITEM_COLLECTOR)) {
            if (place.itemId() != null && !place.itemId().isBlank()) collectorItems.add(place.itemId());
        }

        buildChunks();
        kit = MineTaskKit.slots();
        kitIndex = 0;
        return true;
    }

    /**
     * 开挖掘之前物品栏必须正好是：
     * <ul>
     *   <li>快捷栏 1~9：各一个<b>空潜影盒</b>（正好 9 个）；</li>
     *   <li>背包 1：一个<b>熔岩桶</b>；背包 2~27 空着；</li>
     *   <li>副手空着；盔甲不算。</li>
     * </ul>
     */
    private List<String> inventoryProblems(LocalPlayer player) {
        List<String> problems = new ArrayList<>();

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!MineTaskKit.isEmptyBox(stack)) {
                problems.add("快捷栏第 " + (slot + 1) + " 格不是空潜影盒（是 " + describe(stack) + "）");
            }
        }

        ItemStack lava = player.getInventory().getItem(SingleStageCtx.SPARE_START);
        if (!lava.is(Items.LAVA_BUCKET)) {
            problems.add("背包第 1 格不是熔岩桶（是 " + describe(lava) + "）");
        }

        for (int slot = SingleStageCtx.SPARE_START + 1; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            problems.add("背包第 " + (slot - SingleStageCtx.SPARE_START + 1) + " 格还有 "
                + describe(stack) + "，要空着");
            if (problems.size() >= 6) break;
        }

        ItemStack offhand = player.getInventory().getItem(MineTaskKit.OFFHAND);
        if (!offhand.isEmpty()) {
            problems.add("副手还有 " + describe(offhand) + "，要空着");
        }
        return problems;
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /**
     * 挖掘区起始点 {@code (x1, y2+1, z1)} 必须是路径点。
     *
     * <p>它在区域正上方那一层（{@code b_inspect_region} 保证那里全是空气），
     * 也是整个挖掘过程的中转站：区块起始点、子区块站立点都是从它出发的。
     */
    private boolean checkStartWaypoint() {
        BlockPos start = region.headPos();
        if (WaypointManager.get().graph().at(region.dimension(), start) == null) {
            fail("挖掘区起始点 " + start.toShortString() + " 不是路径点 —— 先在那一格加一个路径点"
                + "（/pc waypoint add_waypoint position " + start.getX() + " " + start.getY() + " "
                + start.getZ() + " " + region.dimension() + "）");
            return false;
        }
        return true;
    }

    /** mend 站立点必须设了、而且和安全点双向可达。 */
    private boolean checkMend(MinePlace safe) {
        MendPos stand = MendConfig.get().standPoint();
        if (stand == null) {
            fail("mend 没设站立点（/pc mend stand set …）—— 工具坏了没地方修");
            return false;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint standNode = graph.at(stand.dimension(), stand.pos());
        if (standNode == null) {
            fail("mend 站立点 " + stand.describe() + " 不是路径点 —— 先把它放到路径点图上");
            return false;
        }
        Waypoint safeNode = graph.at(safe.dimension(), safe.pos());
        if (safeNode == null) {
            fail("安全点 " + safe.describe() + " 不是路径点");
            return false;
        }
        if (!graph.connectedBothWays(safeNode.id(), standNode.id())) {
            fail("mend 站立点 " + stand.describe() + " 和安全点不是双向可达");
            return false;
        }
        return true;
    }

    /** 区块按 x 再 z 从小到大排；区块起始点都在 {@code y2+1} 这一层。 */
    private void buildChunks() {
        List<BlockPos> starts = new ArrayList<>();
        int y = region.max().getY() + 1;
        for (int cx = region.min().getX(); cx <= region.max().getX(); cx += 16) {
            for (int cz = region.min().getZ(); cz <= region.max().getZ(); cz += 16) {
                starts.add(new BlockPos(cx, y, cz));
            }
        }
        chunkStarts = starts;
        regionStart = region.headPos();
    }

    // ------------------------------------------------------------------
    // 第二阶段：备货
    // ------------------------------------------------------------------

    /**
     * 把玩家自带的 9 个空潜影盒（快捷栏 1~9）和熔岩桶（背包 1）挪到 debug_chunk_miner 要求的格子。
     *
     * <p>搬完快捷栏就空了 —— 后面备货要把工具放进快捷栏 1~5。
     */
    private void startRearrange() {
        List<MineTaskInventoryMoveAction.Move> moves = new ArrayList<>();
        moves.addAll(MineTaskKit.boxesToCollectSlots());
        moves.addAll(MineTaskKit.lavaBucketMove());
        ChatUtils.info("先把 9 个空潜影盒搬到背包 13~21、熔岩桶搬到背包 12");
        run(new MineTaskInventoryMoveAction(moves), this::beginPrepare);
    }

    private void beginPrepare() {
        phase = Phase.PREPARE;
        ChatUtils.info("第二阶段：准备物品栏（" + kit.size() + " 项）");
        runNextTake();
    }

    /** 一件一件拿：先走到容器（不在触及范围内就走路径），再开箱拿。 */
    private void runNextTake() {
        while (kitIndex < kit.size()) {
            MineTaskKit.Slot slot = kit.get(kitIndex);
            MineTaskStationData.ContainerInfo container = pickContainer(slot);
            if (container == null) {
                fail("找不到能提供" + slot.label() + "的" + slot.part().display()
                    + "（station_data 里没有）");
                return;
            }

            if (!PlayerUtils.isWithinReach(container.pos())) {
                BlockPos access = container.access();
                if (access == null) {
                    fail(container.describe() + " 没有落点（重跑 /pc mine station check）");
                    return;
                }
                PathPlanner.Plan plan = PathPlanner.toPosition(access, container.dimension());
                if (!plan.ok()) {
                    fail("走不到 " + container.describe() + " 的落点 " + access.toShortString()
                        + "：" + plan.error());
                    return;
                }
                if (plan.alreadyThere()) {
                    // 人就在落点上，但够不着？再走一次没意义，直接报
                    fail("站在落点 " + access.toShortString() + " 上却够不着 " + container.pos().toShortString());
                    return;
                }
                run(new PathMoveAction(plan.path()), this::runNextTake);
                return;
            }

            kitIndex++;
            run(new MineTaskTakeAction(container, slot.index(), slot.filter(), slot.label()),
                this::runNextTake);
            return;
        }

        finishPrepare();
    }

    @Nullable
    private MineTaskStationData.ContainerInfo pickContainer(MineTaskKit.Slot slot) {
        if (slot.itemIdHint() != null) {
            MineTaskStationData.ContainerInfo found =
                stationData.firstContainerWith(slot.part(), slot.itemIdHint());
            if (found != null) return found;
        }
        return stationData.firstContainer(slot.part());
    }

    /** 拿完了：图腾换到副手，再按 debug_chunk_miner 的要求验一遍物品栏。 */
    private void finishPrepare() {
        run(new MineTaskOffhandAction(MineTaskKit.TOTEM_STAGING), this::verifyInventory);
    }

    private void verifyInventory() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        SingleStageData data = new SingleStageData();
        data.refreshInventory(player.getInventory());

        BlockPos initialMin = new BlockPos(player.getBlockX() & ~15, player.getBlockY() & ~15,
            player.getBlockZ() & ~15);
        SingleStageCtx ctx = new SingleStageCtx(region.dimension(), initialMin,
            MineFilterConfig.get().filter(),
            SingleStageDetail.of(collectorItems, Map.of(), 8), data);

        List<String> problems = new ArrayList<>(SingleStageCheck.checkInventory(ctx));
        problems.addAll(SingleStageCheck.checkReach());
        if (!problems.isEmpty()) {
            ChatUtils.error("物品栏还是不符合 debug_chunk_miner 的要求（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.error("  · " + problem);
            fail("备货没凑齐（" + problems.size() + " 条，见聊天栏）");
            return;
        }

        ChatUtils.info("物品栏备好了：工具、食物、图腾、圆石盒、黏液块盒、空盒、熔岩桶都对上了");
        travelToRegion();
    }

    // ------------------------------------------------------------------
    // 第三阶段：逐个区块挖
    // ------------------------------------------------------------------

    private void travelToRegion() {
        phase = Phase.TRAVEL;
        ChatUtils.info("走去挖掘区起始点 " + regionStart.toShortString());
        goToRegionStart(this::startChunk);
    }

    /**
     * 走到挖掘区起始点。
     *
     * <p>人不在路径点网络上（刚从摆放处收工、或者站在矿洞深处）的时候
     * {@code PathPlanner} 会直接报「既不是路径点」—— 这时候先 {@code snap network} 回网络再重新规划。
     */
    private void goToRegionStart(Runnable next) {
        PathPlanner.Plan plan = PathPlanner.toPosition(regionStart, region.dimension());
        if (!plan.ok()) {
            if (plan.error() != null && plan.error().contains("既不是路径点")
                && networkSnaps < MAX_NETWORK_SNAPS) {
                networkSnaps++;
                ChatUtils.info("不在路径点网络上，先 snap 到最近的路径点再走（第 " + networkSnaps + " 次）");
                run(new MineTaskSnapNetworkAction(), () -> goToRegionStart(next));
                return;
            }
            fail("走不到挖掘区起始点 " + regionStart.toShortString() + "：" + plan.error());
            return;
        }
        if (plan.alreadyThere()) {
            next.run();
            return;
        }
        run(new PathMoveAction(plan.path()), next);
    }

    /** 去当前 {@link #chunkIndex} 那个区块。 */
    private void startChunk() {
        phase = Phase.IDLE;
        if (chunkIndex >= chunkStarts.size()) {
            ChatUtils.info("§a挖掘任务 " + task + " 完成：挖掘区 " + region.describe() + " 全部挖完");
            finish();
            return;
        }
        chunkStart = chunkStarts.get(chunkIndex);
        ChatUtils.info("区块 " + chunkLabel(chunkStart) + " 起始点 " + chunkStart.toShortString());
        run(new MineTaskSpecialSnapAction(chunkStart), this::scanChunk);
    }

    /** 从上往下找第一个没挖干净的子区块；都干净了就去下一个区块。 */
    private void scanChunk() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!SnapUtils.isLoaded(mc, chunkStart)) {
            phase = Phase.WAIT_CHUNK;
            if (++loadWaitTicks > CHUNK_LOAD_WAIT_TICKS) {
                fail("区块 " + chunkStart.toShortString() + " 一直没加载（等了 "
                    + (CHUNK_LOAD_WAIT_TICKS / 20) + " 秒）");
                return;
            }
            return;
        }
        phase = Phase.IDLE;
        loadWaitTicks = 0;

        subChunk = findDirtySubChunk(mc);
        if (subChunk == null) {
            ChatUtils.debug("区块 " + chunkLabel(chunkStart) + " 都挖干净了");
            nextChunk();
            return;
        }

        ChatUtils.debug("区块 " + chunkLabel(chunkStart) + " 下一个要挖的子区块 " + subChunk.toShortString());
        goToSub();
    }

    /** 这个区块里最上面那个「y1 以上还有方块」的子区块；都干净了返回 null。 */
    @Nullable
    private BlockPos findDirtySubChunk(Minecraft mc) {
        int topMinY = region.max().getY() - 15;
        for (int minY = topMinY; minY + 15 >= region.min().getY(); minY -= 16) {
            BlockPos min = new BlockPos(chunkStart.getX(), minY, chunkStart.getZ());
            if (!subChunkCleared(mc, min)) return min;
        }
        return null;
    }

    /** 子区块在 y1 以上那部分是不是全空了（最下面那个子区块只看 y1 及以上）。 */
    private boolean subChunkCleared(Minecraft mc, BlockPos min) {
        int from = Math.max(region.min().getY(), min.getY());
        int to = min.getY() + 15;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = from; y <= to; y++) {
                    if (!mc.level.getBlockState(new BlockPos(min.getX() + x, y, min.getZ() + z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 站到子区块上面那个子区块的 6 4 6。 */
    private void goToSub() {
        BlockPos stand = subChunk.offset(6, 20, 6);
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here != null && here.equals(stand)) {
            startMine();
            return;
        }
        run(new MineTaskSpecialSnapAction(stand), this::startMine);
    }

    private void startMine() {
        SingleStageDetail detail = buildDetail(subChunk);
        ChatUtils.info("挖子区块 " + subChunk.toShortString() + "：" + detail.describe());
        miner = new MineComplexSingleStageN(detail);
        run(miner, this::afterMine);
    }

    private void afterMine() {
        // 挖矿的临时任务收掉：接下来要移动，别让 mine simple 还挂在这个区域上
        MineManager.get().cancel();

        boolean resupply = miner != null && miner.needsResupply();
        miner = null;

        if (resupply) {
            ChatUtils.info("这个子区块挖完了，需要补货");
            startResupply();
            return;
        }
        if (armorNeedsRepair()) {
            ChatUtils.info("盔甲耐久低于 " + ARMOR_MIN_DURABILITY + "，去补货（修装备）");
            startResupply();
            return;
        }
        afterSub();
    }

    /** 身上有盔甲耐久低于 {@value #ARMOR_MIN_DURABILITY}。 */
    private boolean armorNeedsRepair() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            if (MineTools.remaining(stack) < ARMOR_MIN_DURABILITY) return true;
        }
        return false;
    }

    /** 这个子区块挖完了：还有下面的就接着挖，没有了就去下一个区块。 */
    private void afterSub() {
        int nextMinY = subChunk.getY() - 16;
        if (nextMinY + 15 >= region.min().getY()) {
            subChunk = new BlockPos(subChunk.getX(), nextMinY, subChunk.getZ());
            goToSub();
            return;
        }
        nextChunk();
    }

    /** 去下一个区块：先回本区块起始点，再 snap 到下一个区块起始点。 */
    private void nextChunk() {
        run(new MineTaskSpecialSnapAction(chunkStart), this::advanceChunk);
    }

    private void advanceChunk() {
        chunkIndex++;
        startChunk();
    }

    // ------------------------------------------------------------------
    // 补货
    // ------------------------------------------------------------------

    private void startResupply() {
        run(new MineTaskSpecialSnapAction(chunkStart), this::snapToRegionStart);
    }

    private void snapToRegionStart() {
        run(new MineTaskSpecialSnapAction(regionStart), this::runResupply);
    }

    private void runResupply() {
        run(new MineTaskResupplyAction(task, region, stationData), this::afterResupply);
    }

    private void afterResupply() {
        goToRegionStart(this::backToChunk);
    }

    private void backToChunk() {
        run(new MineTaskSpecialSnapAction(chunkStart), this::scanChunk);
    }

    // ------------------------------------------------------------------
    // detail
    // ------------------------------------------------------------------

    /**
     * 这个子区块的 {@code debug_chunk_miner} 参数。
     *
     * <ul>
     *   <li>四个侧面：贴着**挖掘区边缘**的那一面用圆石（外面就是没挖过的世界，必须是实心墙），
     *       其余方向 {@code auto}（挨着邻居子区块，填黏液块，下一轮挖邻居时会挖掉）；</li>
     *   <li>{@code bottom}：只有最下面那个子区块用圆石（它下面是 y1-1，已经出了挖掘区），
     *       别的子区块是 {@code auto}（黏液块，下一轮要挖掉）；</li>
     *   <li>{@code levels}：这个子区块在 y1 以上有几层（一个 level = 2 格），最下面那个可能小于 8。</li>
     * </ul>
     */
    private SingleStageDetail buildDetail(BlockPos sub) {
        Map<Direction, SingleStageDetail.WallMode> walls = new EnumMap<>(Direction.class);

        walls.put(Direction.WEST, sub.getX() <= region.min().getX()
            ? SingleStageDetail.WallMode.COBBLESTONE : SingleStageDetail.WallMode.AUTO);
        walls.put(Direction.EAST, sub.getX() + 15 >= region.max().getX()
            ? SingleStageDetail.WallMode.COBBLESTONE : SingleStageDetail.WallMode.AUTO);
        walls.put(Direction.NORTH, sub.getZ() <= region.min().getZ()
            ? SingleStageDetail.WallMode.COBBLESTONE : SingleStageDetail.WallMode.AUTO);
        walls.put(Direction.SOUTH, sub.getZ() + 15 >= region.max().getZ()
            ? SingleStageDetail.WallMode.COBBLESTONE : SingleStageDetail.WallMode.AUTO);

        boolean lowest = sub.getY() - 1 < region.min().getY();
        walls.put(Direction.DOWN, lowest
            ? SingleStageDetail.WallMode.COBBLESTONE : SingleStageDetail.WallMode.AUTO);

        int bottom = Math.max(region.min().getY(), sub.getY());
        int levels = (sub.getY() + 15 - bottom + 1) / 2;

        return SingleStageDetail.of(collectorItems, walls, levels);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 「(2, 3)」这种：区块在挖掘区里的相对位置（从 1 开始）。 */
    private String chunkLabel(BlockPos start) {
        int ix = (start.getX() - region.min().getX()) / 16 + 1;
        int iz = (start.getZ() - region.min().getZ()) / 16 + 1;
        return "(" + ix + ", " + iz + ")";
    }
}
