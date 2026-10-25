package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.mine.MineTools;
import org.six_coin.playerController.client.mine.complex.MineTaskRegion;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCtx;
import org.six_coin.playerController.client.mine.station.MinePlace;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.mine.station.MineStationPart;
import org.six_coin.playerController.client.mine.station.MineToolRules;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;
import org.six_coin.playerController.client.waypoint.PathPlanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * 【补货一趟】—— {@code d_start_mining} 挖完一个子区块、发现要补货时走的那一趟。
 *
 * <p>入口约定：<b>人一定在挖掘区起始点</b>（挖掘主控只有这一个入口）。整个过程分五步：
 * <ol>
 *   <li><b>卸货</b>：把除了 9 个收集盒以外的东西原样放进 {@code inventory_temp}（大箱子，
 *       前三行 = 背包 27 格、第四行 = 快捷栏，位置一一对应）；把<b>有东西的</b>收集盒摆到
 *       {@code unloading_shulker_box_placement} 上（<b>空盒不摆</b>）；逐个倒进物品栏 → 按物品去对应的
 *       {@code item_collector} 装盒（满了就等 1 秒，活塞会换一个新的）；最后把摆出去的盒子
 *       <b>空手快挖</b>拆回来、放回大箱子 13~21 格。</li>
 *   <li><b>补材料</b>：把大箱子 6~8 的圆石盒、9~11 的黏液块盒摆到
 *       {@code supplement_shulker_box_placement} 的 6 个位置；<b>先用物品栏里现成的</b>
 *       （卸货倒出来的树脂块 / 圆石），不够再从 {@code item_temp} 里补，item_temp 也空了就去缓存
 *       拿 3 盒倒进 item_temp 再补；补满之后把这 3 个盒子<b>空手快挖</b>拆回来按位置放回大箱子。</li>
 *   <li><b>换 / 修工具</b>：按位置核对 10 个工具格（背包 1~5 + 快捷栏 1~5）：
 *       斧头 / 锄头剩 &lt; 1200、剑 / 镐 / 铲剩 &lt; 200、备用格不满耐久 → 换新；
 *       缓存里满耐久的够就直接换，不够就把所有不满的工具拿去 {@code /pc mend} 修一轮再换。</li>
 *   <li><b>拿吃的</b>：食物不满 64 就去 {@code food_provider} 补到 64。</li>
 *   <li><b>拿回东西</b>：大箱子 1~27 格搬回背包 27 格、28~36 格搬回快捷栏，然后回安全点。</li>
 * </ol>
 *
 * <p>实现方式：一个「步骤队列」（{@link #queue}）+ 一个子动作。每一步要么是同步的小决定
 * （往队列里塞下一步），要么跑一个子动作（移动 / 开箱搬东西 / 摆盒子 / 拆盒子 / mend），
 * 子动作跑完自动接着下一步。
 */
public class MineTaskResupplyAction extends Action {

    /** 整个补货最久（2 小时）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60 * 2;

    /** 收集盒满了之后等活塞换新盒子的时间（1 秒）。 */
    private static final int PISTON_WAIT_TICKS = 20;

    /** 一满盒。 */
    private static final int FULL_BOX = 27 * 64;

    /** 「不在路径点网络上 → snap 回网络」最多试几次。 */
    private static final int MAX_NETWORK_SNAPS = 5;

    /** 一次从 item_temp 最多拿多少（36 组）。 */
    private static final int TRIP_LIMIT = 36 * 64;

    /** 补一种材料最多循环几轮（每一轮 = 装一盒 / 拿一趟 / 搬一次缓存）。 */
    private static final int MAX_FILL_ROUNDS = 400;

    private static final Predicate<ItemStack> ANY = stack -> !stack.isEmpty();
    private static final Predicate<ItemStack> COBBLE = stack -> stack.is(Items.COBBLESTONE);
    private static final Predicate<ItemStack> SLIME =
        stack -> stack.is(Items.SLIME_BLOCK) || stack.is(Items.RESIN_BLOCK);
    private static final Predicate<ItemStack> SLIME_BLOCK = stack -> stack.is(Items.SLIME_BLOCK);
    private static final Predicate<ItemStack> RESIN_BLOCK = stack -> stack.is(Items.RESIN_BLOCK);
    private static final Predicate<ItemStack> TOOL = stack -> MineToolRules.kindOf(stack) != null;
    private static final Predicate<ItemStack> WORN_TOOL = stack -> {
        MineToolRules.Kind kind = MineToolRules.kindOf(stack);
        return kind != null && stack.getDamageValue() > 0;
    };

    /** 一种材料（圆石 / 黏液块）。 */
    private record Material(String label, MineStationPart cache, Predicate<ItemStack> filter,
                            int placementStart, int firstChestSlot) {
    }

    private static final Material COBBLE_MAT = new Material("圆石", MineStationPart.COBBLESTONE_CACHE,
        COBBLE, 0, 5);
    private static final Material SLIME_MAT = new Material("黏液块", MineStationPart.SLIME_CACHE,
        SLIME, 3, 8);

    private final String task;
    private final MineTaskRegion region;
    private final MineTaskStationData data;

    private final Deque<Runnable> queue = new ArrayDeque<>();
    @Nullable
    private Action child;
    private boolean flying;
    private int totalTicks;

    private MinePlace safePoint;
    private MineTaskStationData.ContainerInfo inventoryTemp;
    private List<MineTaskStationData.Placement> unloading = List.of();
    private List<MineTaskStationData.Placement> supplement = List.of();
    private List<MineTaskStationData.Placement> temp = List.of();
    private List<MinePlace> collectors = List.of();

    /** 卸货 / 换货的进度。 */
    private int boxIndex;
    /** 这一趟真正要摆出去卸的收集盒（下标 = 物品栏 21~29 里的第几个；空盒不摆）。 */
    private final List<Integer> unloadBoxes = new ArrayList<>();
    /** 补充盒里现在各有多少（下标 = supplement 的下标）。 */
    private final Map<Integer, Integer> boxCounts = new LinkedHashMap<>();
    /** 补充盒里装的是哪一种（黏液块 / 树脂块；下标同上）。混装过不了 debug 的检查。 */
    private final Map<Integer, String> boxTypes = new LinkedHashMap<>();
    /** 补材料循环跑了几轮（防止死循环）。 */
    private int fillRounds;
    /** 连着几轮「一点都没装进去」（盒子满了 / 只剩别的种类）。 */
    private int stallRounds;
    /** 缓存倒进 item_temp 的轮数（防止死循环）。 */
    private int fetchRounds;
    /** 要换的工具：大箱子第几格 → 要换成的种类（按位置核对，空的 / 放错的也算）。 */
    private final Map<Integer, MineToolRules.Kind> tiredSlots = new LinkedHashMap<>();
    /** 修工具时临时借的那把剑（在快捷栏第 1 格）。 */
    private boolean borrowedSword;
    /** 已经修了几轮（防止「修不完 → 再拿 → 再修」死循环）。 */
    private int mendRounds;
    /** 每个产物收集盒连着「满了」几次（活塞一直不换新的就报错）。 */
    private final Map<String, Integer> collectorRetries = new LinkedHashMap<>();
    /**
     * 「不在路径点网络上 → snap 回网络」**连着**用了几次（防止死循环）。
     *
     * <p>规划成功就清零：补货一路要经过 9 个卸货位 + 6 个补充位 + 3 个临时位，
     * 每一个都是浮空的潜影盒，站上去会被顶高半格 → 每次都要 snap 一次。
     * 如果这是个「一辈子用几次」的计数，第 6 个摆放处就会直接报错。
     */
    private int networkSnaps;
    /** 自己挂起 / 恢复容器缓存的自动刷新（挖掘主控已经挂起过了，那边不用这个）。 */
    private boolean ownsCacheSuspend;

    public MineTaskResupplyAction(String task, MineTaskRegion region, MineTaskStationData data) {
        this.task = task;
        this.region = region;
        this.data = data;
    }

    /**
     * 自己把容器缓存的自动刷新挂起（{@code /pc mine debug_restock} 单独跑的时候用）。
     *
     * <p>挖掘主控（{@code MineTaskMiningAction}）在开任务的时候已经挂起过了，那边不要调这个 ——
     * 挂起是个布尔量，嵌套着用会在补货结束时把主控的挂起一起解掉。
     */
    public MineTaskResupplyAction suspendCache() {
        this.ownsCacheSuspend = true;
        return this;
    }

    @Override
    public String name() {
        return "补货一趟（" + task + "）";
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!prepare()) return;

        if (ownsCacheSuspend) ContainerCacheTracker.setSuspended(true);

        FlightVelocity.begin();
        flying = true;

        ChatUtils.info("补货开始：先回安全点，然后卸货 → 补材料 → 换工具 → 拿吃的 → 拿回东西");
        push(() -> goTo(safePoint.pos(), safePoint.dimension(), this::startUnload));
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("补货超时（" + (MAX_TOTAL_TICKS / 20 / 60) + " 分钟）");
            return;
        }

        if (child != null) {
            FlightVelocity.apply(Vec3.ZERO);
            child.update();
            if (!child.isFinished()) return;

            Action done = child;
            child = null;
            done.cleanup();
            if (done.failureReason() != null) {
                fail(done.failureReason());
                return;
            }
        }

        while (child == null && !queue.isEmpty() && !isFinished()) {
            queue.pollFirst().run();
        }

        if (child == null && queue.isEmpty() && !isFinished()) {
            ChatUtils.info("补货完成");
            finish();
        }
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
        if (ownsCacheSuspend) {
            ContainerCacheTracker.setSuspended(false);
            ownsCacheSuspend = false;
        }
    }

    /** 把站点坐标都读出来；缺东西就直接失败（不该发生 —— check 过了才行）。 */
    private boolean prepare() {
        safePoint = MineStationManager.get().single(MineStationPart.SAFE_POINT);
        if (safePoint == null) {
            fail("没有设置安全点");
            return false;
        }
        inventoryTemp = data.firstContainer(MineStationPart.INVENTORY_TEMP);
        if (inventoryTemp == null) {
            fail("station_data 里没有物品栏暂存处（inventory_temp）");
            return false;
        }
        unloading = data.placements(MineStationPart.UNLOADING_PLACEMENT);
        supplement = data.placements(MineStationPart.SUPPLEMENT_PLACEMENT);
        temp = data.placements(MineStationPart.TEMP_PLACEMENT);
        collectors = MineStationManager.get().positions(MineStationPart.ITEM_COLLECTOR);

        if (unloading.size() < 9 || supplement.size() < 6 || temp.size() < 3) {
            fail("摆放处数量不对（卸货 " + unloading.size() + " / 补充 " + supplement.size()
                + " / 临时 " + temp.size() + "），先重跑 /pc mine station check");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 第一步：卸货
    // ------------------------------------------------------------------

    private void startUnload() {
        ChatUtils.info("补货 1/5：卸货（除了 9 个收集盒，其他东西原样放进物品栏暂存处）");
        goToAccess(inventoryTemp, this::dumpToInventoryTemp);
    }

    private void dumpToInventoryTemp() {
        // 只搬 0~20 和 30~35：21~29 是那 9 个收集盒，要留着拿去摆
        Set<Integer> slots = MineTaskContainerAction.slots(0, 20);
        slots.addAll(MineTaskContainerAction.slots(30, 35));
        run(new MineTaskContainerAction(inventoryTemp,
            MineTaskContainerAction.Mode.SYNC_TO_CONTAINER, ANY, 0, slots, "卸货到物品栏暂存处"),
            this::placeAllBoxes);
    }

    private void placeAllBoxes() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        // 需求：**空盒不摆**。只摆里面真的有东西的收集盒（空盒留在物品栏里，最后原样放回去）
        unloadBoxes.clear();
        for (int i = 0; i < 9; i++) {
            if (!MineTaskKit.isEmptyBox(player.getInventory().getItem(SingleStageCtx.COLLECT_START + i))) {
                unloadBoxes.add(i);
            }
        }
        if (unloadBoxes.isEmpty()) {
            ChatUtils.info("9 个收集盒都是空的，跳过卸货");
            putBoxesBack();
            return;
        }
        ChatUtils.info("要卸的收集盒 " + unloadBoxes.size() + " 个（另外 "
            + (9 - unloadBoxes.size()) + " 个是空盒，不摆）");
        boxIndex = 0;
        placeNextBox();
    }

    private void placeNextBox() {
        if (boxIndex >= unloadBoxes.size()) {
            boxIndex = 0;
            unloadNextBox();
            return;
        }

        int which = unloadBoxes.get(boxIndex);
        int inventorySlot = SingleStageCtx.COLLECT_START + which;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        // 数盒子的时候这一格还有盒子，现在没了 —— 客户端和服务端对不上（或者中途吸进别的地方去了）。
        // 跳过这一个，别把整趟补货搞挂（丢的那一个会体现在「空盒不够 9 个」的警告里）
        if (!ShulkerUtils.isShulkerBox(player.getInventory().getItem(inventorySlot))) {
            ChatUtils.error("物品栏第 " + inventorySlot + " 格已经没有潜影盒了（数的时候还有），跳过收集盒 "
                + (which + 1));
            boxIndex++;
            placeNextBox();
            return;
        }

        MineTaskStationData.Placement spot = unloading.get(which);
        goToPlacement(spot, () -> run(
            new MineTaskPlaceBoxAction(inventorySlot, spot.pos(), "收集盒 " + (which + 1)),
            () -> {
                boxIndex++;
                placeNextBox();
            }));
    }

    private void unloadNextBox() {
        if (boxIndex >= unloadBoxes.size()) {
            boxIndex = 0;
            breakAllBoxes();
            return;
        }

        int which = unloadBoxes.get(boxIndex);
        MineTaskStationData.Placement spot = unloading.get(which);
        goToPlacement(spot, () -> {
            MineTaskContainerAction count = new MineTaskContainerAction(spot.pos(),
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "看看收集盒里有什么");
            run(count, () -> {
                if (count.countedEmpty() >= count.countedSlots()) {
                    ChatUtils.debug("第 %d 个收集盒是空的，跳过", which + 1);
                    boxIndex++;
                    unloadNextBox();
                    return;
                }
                ChatUtils.info("第 " + (which + 1) + " 个收集盒里有："
                    + MineTaskContainerAction.describeItems(count.counted()));
                data.recordPlacementBox(MineStationPart.UNLOADING_PLACEMENT, spot.pos(), count.counted());
                run(new MineTaskContainerAction(spot.pos(),
                    MineTaskContainerAction.Mode.TO_INVENTORY, ANY, 0, Set.of(), "把收集盒倒进物品栏"),
                    this::distributeItems);
            });
        });
    }

    /** 把物品栏里「有对应产物收集」的东西逐个送过去。 */
    private void distributeItems() {
        String itemId = firstDistributableItem();
        if (itemId == null) {
            boxIndex++;
            unloadNextBox();
            return;
        }

        MinePlace collector = collectorFor(itemId);
        if (collector == null || collector.access() == null) {
            fail("产物收集 " + itemId + " 没有落点");
            return;
        }

        goTo(collector.access().pos(), collector.dimension(), () -> {
            MineTaskContainerAction put = new MineTaskContainerAction(collector.pos(),
                MineTaskContainerAction.Mode.TO_CONTAINER, stack -> idOf(stack).equals(itemId),
                0, Set.of(), "把 " + itemId + " 装进收集盒").allowClose();
            run(put, () -> {
                if (put.containerFull()) {
                    int retries = collectorRetries.merge(itemId, 1, Integer::sum);
                    if (retries > 5) {
                        fail("收集盒 " + itemId + " 连着满了 5 次，活塞没换新的？");
                        return;
                    }
                    ChatUtils.debug("收集盒满了：等 1 秒让活塞换一个新的（第 %d 次）", retries);
                    waitTicks(PISTON_WAIT_TICKS, () -> waitForContainer(collector.pos(), this::distributeItems));
                    return;
                }
                collectorRetries.remove(itemId);
                distributeItems();
            });
        });
    }

    /**
     * 拆摆出去的收集盒。
     *
     * <p>**空手快挖**（{@link MineTaskBreakBoxAction} 自己就是快挖），不再从 inventory_temp
     * 借镐子 —— 借镐子那一套既慢又吃耐久。
     */
    private void breakAllBoxes() {
        boxIndex = 0;
        breakNextBox();
    }

    private void breakNextBox() {
        if (boxIndex >= unloadBoxes.size()) {
            putBoxesBack();
            return;
        }
        int which = unloadBoxes.get(boxIndex);
        MineTaskStationData.Placement spot = unloading.get(which);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null
            && !(mc.level.getBlockState(spot.pos()).getBlock() instanceof ShulkerBoxBlock)) {
            ChatUtils.error("摆放处 " + spot.pos().toShortString() + " 上已经没有潜影盒了（收集盒 "
                + (which + 1) + "），跳过");
            boxIndex++;
            breakNextBox();
            return;
        }

        goToPlacement(spot, () -> run(
            new MineTaskBreakBoxAction(spot.pos(), "收集盒 " + (which + 1)),
            () -> {
                boxIndex++;
                breakNextBox();
            }));
    }

    /** 把 9 个空盒放回大箱子 13~21 格。 */
    private void putBoxesBack() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        List<Integer> boxSlots = new ArrayList<>();
        for (int slot = 0; slot <= 35; slot++) {
            if (MineTaskKit.isEmptyBox(player.getInventory().getItem(slot))) boxSlots.add(slot);
        }
        if (boxSlots.size() != 9) {
            // 不直接失败：少的那几个是丢了（多半是中途某次点击被服务端丢了），
            // 先按现有的放回去，下一轮 debug_chunk_miner 的检查会明确报「背包 X 是空的」
            ChatUtils.error("拆完收集盒之后物品栏里有 " + boxSlots.size()
                + " 个空盒（应该是 9 个）—— 少的几个丢了，先按有的放回去");
        }

        List<MineTaskInventoryMoveAction.Move> moves = new ArrayList<>();
        for (int i = 0; i < Math.min(9, boxSlots.size()); i++) {
            int from = boxSlots.get(i);
            int to = SingleStageCtx.COLLECT_START + i;
            if (from == to) continue;
            moves.add(new MineTaskInventoryMoveAction.Move(from, to, "空盒 " + (i + 1) + " → 背包 " + (13 + i)));
        }

        goToAccess(inventoryTemp, () -> {
            Runnable sync = () -> run(new MineTaskContainerAction(inventoryTemp,
                MineTaskContainerAction.Mode.SYNC_TO_CONTAINER, ANY, 0,
                MineTaskContainerAction.slots(SingleStageCtx.COLLECT_START, SingleStageCtx.COLLECT_END),
                "把 9 个空盒放回大箱子 13~21"), this::startRestock);

            if (moves.isEmpty()) {
                sync.run();
                return;
            }
            run(new MineTaskInventoryMoveAction(moves), sync);
        });
    }

    // ------------------------------------------------------------------
    // 第二步：补材料
    // ------------------------------------------------------------------

    private void startRestock() {
        ChatUtils.info("补货 2/5：补材料");
        restockMaterial(COBBLE_MAT, () -> restockMaterial(SLIME_MAT, this::startTools));
    }

    /**
     * 一种材料的一整轮：摆盒子 → 数盒子 → 补满 → 剩下的材料放回 item_temp
     * → **把盒子拆回来按位置放回大箱子**。
     *
     * <p>「拆回来」这一步以前漏了：盒子在摆放处补满之后就留在世界里，玩家物品栏里还是
     * 那几个用空了的盒子，所以下一轮挖掘当场报错，树脂块 / 圆石也只能堆在物品栏里。
     *
     * <p>顺序上**先**把剩下的材料放回 item_temp：物品栏腾出空位，拆下来的盒子才吸得进来。
     */
    private void restockMaterial(Material material, Runnable next) {
        goToAccess(inventoryTemp, () -> placeSupplementBoxes(material, 0, () -> {
            boxCounts.clear();
            boxTypes.clear();
            fillRounds = 0;
            stallRounds = 0;
            countSupplementBoxes(material, 0, () ->
                fillLoop(material, () ->
                    finishMaterial(material, () ->
                        returnSupplementBoxes(material, 0, next))));
        }));
    }

    /** 从大箱子 6~8 / 9~11 拿 3 个盒子，摆到补充摆放处。 */
    private void placeSupplementBoxes(Material material, int index, Runnable next) {
        if (index >= 3) {
            next.run();
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        int tempSlot = player == null ? -1 : firstEmptyInvSlot(player);
        if (tempSlot < 0) {
            fail("物品栏没有空格，拿不了" + material.label() + "盒");
            return;
        }

        MineTaskStationData.Placement spot = supplement.get(material.placementStart() + index);
        goToAccess(inventoryTemp, () -> run(
            new MineTaskTakeAction(inventoryTemp, tempSlot, material.firstChestSlot() + index,
                material.label() + "盒"),
            () -> goToPlacement(spot, () -> run(
                new MineTaskPlaceBoxAction(tempSlot, spot.pos(), material.label() + "盒"),
                () -> placeSupplementBoxes(material, index + 1, next)))));
    }

    /** 数一下补充摆放处那 3 个盒子里各有多少（顺便记下装的是黏液块还是树脂块）。 */
    private void countSupplementBoxes(Material material, int index, Runnable next) {
        if (index >= 3) {
            next.run();
            return;
        }
        int slot = material.placementStart() + index;
        MineTaskStationData.Placement spot = supplement.get(slot);
        goToPlacement(spot, () -> {
            MineTaskContainerAction count = new MineTaskContainerAction(spot.pos(),
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "数" + material.label());
            run(count, () -> {
                boxCounts.put(slot, materialCount(material, count.counted()));
                boxTypes.put(slot, typeOf(material, count.counted()));
                data.recordPlacementBox(MineStationPart.SUPPLEMENT_PLACEMENT, spot.pos(), count.counted());
                countSupplementBoxes(material, index + 1, next);
            });
        });
    }

    /**
     * 补满 3 个盒子。
     *
     * <p>顺序：<b>物品栏里现成的</b>（卸货倒出来的树脂块 / 圆石）→ {@code item_temp} 里拿 →
     * 缓存搬 3 盒倒进 {@code item_temp} 再拿。以前只看 {@code item_temp}，卸货倒进物品栏的
     * 那一堆根本装不进盒子，所以「补不满、还留一背包树脂块」。
     */
    private void fillLoop(Material material, Runnable next) {
        int index = firstDeficientBox(material);
        if (index < 0) {
            next.run();
            return;
        }
        if (++fillRounds > MAX_FILL_ROUNDS) {
            fail("补" + material.label() + "补了 " + fillRounds + " 轮还是没满（盒子装不进去了？）");
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        Predicate<ItemStack> filter = filterForBox(material, index, player);

        // 1. 物品栏里还有同类东西 → 直接装进盒子（少跑一趟 item_temp）
        if (hasMatching(player, filter)) {
            distributeToBox(material, index, filter, next);
            return;
        }

        // 2. item_temp 里有 → 拿一趟（最多 36 组）
        MineTaskStationData.ContainerInfo source = itemTempWith(filter);
        if (source != null) {
            int need = Math.min(TRIP_LIMIT, deficit(material));
            goToAccess(source, () -> {
                MineTaskContainerAction take = new MineTaskContainerAction(source,
                    MineTaskContainerAction.Mode.TO_INVENTORY, filter, need, Set.of(),
                    "从 item_temp 拿" + material.label());
                run(take, () -> {
                    if (take.moved() == 0) {
                        fail("item_temp 里的" + material.label() + "拿不出来（物品栏满了？）");
                        return;
                    }
                    fillLoop(material, next);
                });
            });
            return;
        }

        // 3. 都没有 → 从缓存搬 3 盒过来，回来接着补
        fetchFromCache(material, filter, () -> fillLoop(material, next));
    }

    /** 把物品栏里的材料装进第 {@code index} 个盒子，装完回 {@link #fillLoop}。 */
    private void distributeToBox(Material material, int index, Predicate<ItemStack> filter,
                                 Runnable next) {
        MineTaskStationData.Placement spot = supplement.get(index);
        goToPlacement(spot, () -> {
            MineTaskContainerAction put = new MineTaskContainerAction(spot.pos(),
                MineTaskContainerAction.Mode.TO_CONTAINER, filter, 0, Set.of(),
                "往盒子里装" + material.label());
            run(put, () -> {
                MineTaskContainerAction count = new MineTaskContainerAction(spot.pos(),
                    MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "数" + material.label());
                run(count, () -> {
                    int before = boxCounts.getOrDefault(index, 0);
                    int now = materialCount(material, count.counted());
                    boxCounts.put(index, now);
                    String type = typeOf(material, count.counted());
                    if (type != null) boxTypes.put(index, type);
                    data.recordPlacementBox(MineStationPart.SUPPLEMENT_PLACEMENT, spot.pos(),
                        count.counted());

                    if (now <= before) {
                        // 一点都没装进去（盒子满了 / 只剩别的种类占着格子）→ 当它满的，别死循环
                        ChatUtils.debug("第 %d 个%s盒装不进去了（%d → %d），按满的算",
                            index - material.placementStart() + 1, material.label(), before, now);
                        boxCounts.put(index, FULL_BOX);
                        if (++stallRounds > 3) {
                            fail("3 个" + material.label() + "盒都装不进去了，物品栏里还有"
                                + material.label());
                            return;
                        }
                    } else {
                        stallRounds = 0;
                    }
                    fillLoop(material, next);
                });
            });
        });
    }

    /**
     * 把补满的 3 个盒子拆回来，按位置放回大箱子 6~8 / 9~11（拆是**空手快挖**）。
     *
     * <p>盒子被吸到哪一格是随机的，所以用 {@link MineTaskPutSlotAction} 直接「物品栏那一格 →
     * 箱子那一格」，不靠 {@code SYNC_TO_CONTAINER}（那个要求东西先在对应的物品栏格）。
     */
    private void returnSupplementBoxes(Material material, int index, Runnable next) {
        if (index >= 3) {
            next.run();
            return;
        }
        int chestSlot = material.firstChestSlot() + index;
        MineTaskStationData.Placement spot = supplement.get(material.placementStart() + index);

        goToPlacement(spot, () -> {
            MineTaskBreakBoxAction breakBox = new MineTaskBreakBoxAction(spot.pos(),
                material.label() + "盒 " + (index + 1));
            run(breakBox, () -> {
                int found = breakBox.pickedSlot();
                if (found < 0) {
                    fail("拆回来的" + material.label() + "盒不知道吸到哪一格了（物品栏满了？）");
                    return;
                }
                ChatUtils.debug("拆回来的%s盒在第 %d 格，放回大箱子第 %d 格",
                    material.label(), found, chestSlot + 1);
                goToAccess(inventoryTemp, () -> run(
                    new MineTaskPutSlotAction(inventoryTemp, found, chestSlot,
                        material.label() + "盒"),
                    () -> returnSupplementBoxes(material, index + 1, next)));
            });
        });
    }

    /**
     * 这个盒子该装哪一种：黏液块 / 树脂块跟着盒子现在装的走（不许混装），
     * 空盒就先按「物品栏里现成的那种」定下来，定了就不再变。
     */
    private Predicate<ItemStack> filterForBox(Material material, int index, LocalPlayer player) {
        if (material != SLIME_MAT) return material.filter();

        String type = boxTypes.get(index);
        if (type == null) {
            if (hasMatching(player, SLIME_BLOCK)) {
                type = "minecraft:slime_block";
            } else if (hasMatching(player, RESIN_BLOCK)) {
                type = "minecraft:resin_block";
            }
        }
        if (type == null) return SLIME;      // 两种都没有：先不定，等从 item_temp 拿到货再定
        boxTypes.put(index, type);

        String decided = type;
        return stack -> idOf(stack).equals(decided);
    }

    /** 这个盒子里装的是黏液块还是树脂块（空盒 / 圆石盒返回 null）。 */
    @Nullable
    private static String typeOf(Material material, Map<String, Integer> items) {
        if (material != SLIME_MAT) return null;
        for (String id : items.keySet()) {
            if ("minecraft:slime_block".equals(id) || "minecraft:resin_block".equals(id)) return id;
        }
        return null;
    }

    /** 这一种材料补完了：物品栏里还剩的同类东西放回 item_temp，然后下一步。 */
    private void finishMaterial(Material material, Runnable next) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !hasMatching(player, material.filter())) {
            next.run();
            return;
        }
        dumpToItemTemp(material.filter(), "剩下的" + material.label(), next);
    }

    /** item_temp 里没货了：去缓存拿 3 盒倒进 item_temp，再回到上面的循环。 */
    private void fetchFromCache(Material material, Predicate<ItemStack> filter, Runnable next) {
        if (++fetchRounds > 3) {
            failAtSafePoint("从" + material.cache().display() + "补了 3 轮还是不够" + material.label());
            return;
        }

        MineTaskStationData.ContainerInfo cache = data.firstContainer(material.cache());
        if (cache == null) {
            failAtSafePoint("station_data 里没有" + material.cache().display());
            return;
        }

        goToAccess(cache, () -> {
            MineTaskContainerAction count = new MineTaskContainerAction(cache,
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "数缓存里的盒子");
            run(count, () -> {
                if (count.countedBoxes() < 3) {
                    failAtSafePoint(material.cache().display() + " 里只有 " + count.countedBoxes()
                        + " 盒" + material.label() + "（要 3 盒）");
                    return;
                }
                if (!containsAny(count.counted(), filter)
                    && !containsAny(count.countedBoxContents(), filter)) {
                    // 缓存是「整份只有一种」的：盒子要的是另一种，再搬也没用
                    failAtSafePoint(material.cache().display() + " 里没有能装进这个盒子的"
                        + material.label() + "（缓存里是 "
                        + MineTaskContainerAction.describeItems(count.countedBoxContents()) + "）"
                        + " —— 盒子里的那一种和缓存对不上");
                    return;
                }
                ChatUtils.info("item_temp 里没" + material.label() + "了：从"
                    + material.cache().display() + "搬 3 盒过来");
                fetchBoxes(material, cache, 0, () -> dumpTempBoxes(material, 0,
                    () -> recycleTempBoxes(material, 0, next)));
            });
        });
    }

    /** 这个容器里有没有符合 {@code filter} 的东西。 */
    private static boolean containsAny(Map<String, Integer> items, Predicate<ItemStack> filter) {
        for (String id : items.keySet()) {
            Item item = itemOf(id);
            if (item != null && filter.test(item.getDefaultInstance())) return true;
        }
        return false;
    }

    /** 从缓存拿 3 个盒子摆到临时摆放处。 */
    private void fetchBoxes(Material material, MineTaskStationData.ContainerInfo cache,
                            int index, Runnable next) {
        if (index >= 3) {
            next.run();
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        int tempSlot = player == null ? -1 : firstEmptyInvSlot(player);
        if (tempSlot < 0) {
            fail("物品栏没有空格，拿不了缓存里的盒子");
            return;
        }

        MineTaskStationData.Placement spot = temp.get(index);
        goToAccess(cache, () -> run(
            new MineTaskTakeAction(cache, tempSlot,
                stack -> org.six_coin.playerController.client.util.ShulkerUtils.isShulkerBox(stack),
                material.label() + "盒"),
            () -> goToPlacement(spot, () -> run(
                new MineTaskPlaceBoxAction(tempSlot, spot.pos(), material.label() + "盒"),
                () -> fetchBoxes(material, cache, index + 1, next)))));
    }

    /** 临时摆放处那 3 个盒子：逐个倒进物品栏 → 放进 item_temp。 */
    private void dumpTempBoxes(Material material, int index, Runnable next) {
        if (index >= 3) {
            next.run();
            return;
        }
        MineTaskStationData.Placement spot = temp.get(index);
        emptyTempBox(material, spot, () -> dumpTempBoxes(material, index + 1, next));
    }

    /**
     * 把一个临时盒**倒空**。
     *
     * <p>一次「倒进物品栏」可能只倒进去一部分（物品栏满了）：那就先把物品栏里的材料塞进
     * item_temp，回来接着倒，直到盒子真的空了。不这样的话剩下的材料会跟着盒子被拆下来 ——
     * 变成一个「不是空盒」的潜影盒卡在物品栏里（回收处不收，下一轮检查也过不了）。
     */
    private void emptyTempBox(Material material, MineTaskStationData.Placement spot, Runnable next) {
        goToPlacement(spot, () -> {
            MineTaskContainerAction take = new MineTaskContainerAction(spot.pos(),
                MineTaskContainerAction.Mode.TO_INVENTORY, ANY, 0, Set.of(), "把临时盒倒进物品栏");
            run(take, () -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (take.inventoryFull() && player != null && hasMatching(player, material.filter())) {
                    ChatUtils.debug("物品栏满了，先把%s塞进 item_temp，回来接着倒", material.label());
                    dumpToItemTemp(material.filter(), material.label(),
                        () -> emptyTempBox(material, spot, next));
                    return;
                }
                dumpToItemTemp(material.filter(), material.label(), next);
            });
        });
    }

    /**
     * 把物品栏里的东西全部塞进 {@code item_temp} —— **一个个容器试过去**，装满一个换下一个。
     *
     * <p>{@code item_temp} 是列表（可以配好几个大箱子）。以前只认第一个（{@code firstContainer}），
     * 第一个满了就一个都塞不进去：倒临时盒的时候 1728 个只进去 1408 个，剩下的把物品栏撑满，
     * 紧接着拆盒子连盒子都捡不起来（「等了 5 秒，物品栏里没多出潜影盒」）。
     *
     * @param filter 只搬这些东西（{@code ANY} = 全搬）
     */
    private void dumpToItemTemp(Predicate<ItemStack> filter, String what, Runnable next) {
        List<MineTaskStationData.ContainerInfo> containers =
            data.containers(MineStationPart.ITEM_TEMP);
        if (containers.isEmpty()) {
            fail("station_data 里没有任务前物品暂存处（item_temp）");
            return;
        }
        dumpToItemTemp(containers, 0, filter, what, next);
    }

    private void dumpToItemTemp(List<MineTaskStationData.ContainerInfo> containers, int index,
                                Predicate<ItemStack> filter, String what, Runnable next) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (!hasMatching(player, filter)) {
            next.run();
            return;
        }
        if (index >= containers.size()) {
            fail("item_temp 的 " + containers.size() + " 个容器都塞满了，" + what
                + " 还有一部分在物品栏里（腾点地方，或者多加一个 item_temp）");
            return;
        }

        MineTaskStationData.ContainerInfo target = containers.get(index);
        goToAccess(target, () -> {
            MineTaskContainerAction put = new MineTaskContainerAction(target,
                MineTaskContainerAction.Mode.TO_CONTAINER, filter, 0, Set.of(),
                "把" + what + "放进 item_temp #" + target.id());
            run(put, () -> {
                if (put.moved() == 0 && put.containerFull()) {
                    ChatUtils.debug("item_temp #%d 满了，换下一个", target.id());
                }
                dumpToItemTemp(containers, index + 1, filter, what, next);
            });
        });
    }

    /** 拆掉临时摆放处的 3 个空盒，放回空潜影盒回收处。 */
    private void recycleTempBoxes(Material material, int index, Runnable next) {
        if (index >= 3) {
            MineTaskStationData.ContainerInfo recycler =
                data.firstContainer(MineStationPart.EMPTY_SHULKER_BOX_RECYCLER);
            if (recycler == null) {
                fail("station_data 里没有空潜影盒回收处");
                return;
            }
            goToAccess(recycler, () -> run(
                new MineTaskContainerAction(recycler, MineTaskContainerAction.Mode.TO_CONTAINER,
                    stack -> MineTaskKit.isEmptyBox(stack), 0, Set.of(), "把空盒放回回收处"),
                next));
            return;
        }

        MineTaskStationData.Placement spot = temp.get(index);
        goToPlacement(spot, () -> run(
            new MineTaskBreakBoxAction(spot.pos(), material.label() + "临时盒"),
            () -> recycleTempBoxes(material, index + 1, next)));
    }

    private int deficit(Material material) {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            int count = boxCounts.getOrDefault(material.placementStart() + i, 0);
            total += Math.max(0, FULL_BOX - count);
        }
        return total;
    }

    private int firstDeficientBox(Material material) {
        for (int i = 0; i < 3; i++) {
            int index = material.placementStart() + i;
            if (boxCounts.getOrDefault(index, 0) < FULL_BOX) return index;
        }
        return -1;
    }

    private static int materialCount(Material material, Map<String, Integer> items) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            Item item = itemOf(entry.getKey());
            if (item == null) continue;
            if (material.filter().test(item.getDefaultInstance())) total += entry.getValue();
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 第三步：换 / 修工具
    // ------------------------------------------------------------------

    /**
     * 换工具：<b>按位置</b>把 10 个工具格（背包 1~5 + 快捷栏 1~5）逐个核对一遍。
     *
     * <p>门槛（和 debug 前的检查同一套，见 {@link MineToolRules#minDurability}）：
     * 斧头 / 锄头剩 ≥{@value MineToolRules#AXE_HOE_MIN_DURABILITY}，剑 / 镐 / 铲剩
     * ≥{@value MineToolRules#TOOL_MIN_DURABILITY}；备用格（背包 1~5）要求满耐久
     * —— 备用格不满说明是挖矿时换下来的，也一并换新。
     */
    private void startTools() {
        ChatUtils.info("补货 3/5：换工具 / 修工具");
        goToAccess(inventoryTemp, () -> {
            MineTaskContainerAction scan = new MineTaskContainerAction(inventoryTemp,
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "看看有哪些工具要换");
            run(scan, () -> {
                planTiredTools(scan);
                if (tiredSlots.isEmpty()) {
                    ChatUtils.info("工具都够用（斧头 / 锄头 ≥"
                        + MineToolRules.AXE_HOE_MIN_DURABILITY + "，其他 ≥"
                        + MineToolRules.TOOL_MIN_DURABILITY + "，备用满耐久），跳过换工具");
                    startArmor();
                    return;
                }

                ChatUtils.info("要换的工具：" + describeTired());
                takeTiredOut(new ArrayList<>(tiredSlots.entrySet()), 0, this::putTiredIntoCache);
            });
        });
    }

    /** 哪些工具格要换：空的 / 放错种类 / 耐久低于门槛的都算。 */
    private void planTiredTools(MineTaskContainerAction scan) {
        tiredSlots.clear();
        Map<Integer, MineTaskContainerAction.ToolEntry> bySlot = new LinkedHashMap<>();
        for (MineTaskContainerAction.ToolEntry entry : scan.tools()) bySlot.put(entry.slot(), entry);

        for (int chestSlot : TOOL_CHEST_SLOTS) {
            MineToolRules.Kind kind = expectedTool(chestSlot);
            if (kind == null) continue;
            MineTaskContainerAction.ToolEntry entry = bySlot.get(chestSlot);
            if (entry == null || entry.kind() != kind) {
                // 空的 / 放的是别的东西（比如上一版的斧头）：也要换成对的
                tiredSlots.put(chestSlot, kind);
                continue;
            }
            // 备用格（背包 1~5）必须满耐久，掉了一点就是挖矿时换下来的旧工具
            if (chestSlot <= 4) {
                if (!entry.full()) tiredSlots.put(chestSlot, kind);
                continue;
            }
            if (entry.remaining() < MineToolRules.minDurability(kind)) {
                tiredSlots.put(chestSlot, kind);
            }
        }
    }

    /** 「大箱子第 5 格（背包 6）→ 圆石盒」这种一行。 */
    private String describeTired() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, MineToolRules.Kind> entry : tiredSlots.entrySet()) {
            parts.add(chestLabel(entry.getKey()) + "=" + entry.getValue().label());
        }
        return String.join("、", parts);
    }

    private static String chestLabel(int chestSlot) {
        return chestSlot < 27
            ? "背包 " + (chestSlot + 1)
            : "快捷栏 " + (chestSlot - 26);
    }

    /** 补货时要按位置核对的工具格（大箱子下标）：背包 1~5 + 快捷栏 1~5。 */
    private static final int[] TOOL_CHEST_SLOTS = {0, 1, 2, 3, 4, 27, 28, 29, 30, 31};

    /**
     * 这个工具格里应该是哪种工具。
     *
     * <p>背包 1~5 = 备用剑 / 备用镐 / 备用镐 / 备用铲 / 备用镐；快捷栏 1~5 = 剑 / 镐 / 斧 / 铲 / 锄。
     */
    private static MineToolRules.Kind expectedTool(int chestSlot) {
        return switch (chestSlot) {
            case 0, 27 -> MineToolRules.Kind.SWORD;
            case 1, 2, 4, 28 -> MineToolRules.Kind.PICKAXE;
            case 3, 30 -> MineToolRules.Kind.SHOVEL;
            case 29 -> MineToolRules.Kind.AXE;
            case 31 -> MineToolRules.Kind.HOE;
            default -> null;
        };
    }

    /** 把要换的工具按位置从大箱子里拿出来（放到物品栏的临时空格里）。 */
    private void takeTiredOut(List<Map.Entry<Integer, MineToolRules.Kind>> entries, int index,
                              Runnable next) {
        if (index >= entries.size()) {
            next.run();
            return;
        }
        int chestSlot = entries.get(index).getKey();
        MineToolRules.Kind kind = entries.get(index).getValue();

        LocalPlayer player = Minecraft.getInstance().player;
        int free = player == null ? -1 : firstFreeSlotForTool(player);
        if (free < 0) {
            fail("物品栏没有空格，拿不出" + chestLabel(chestSlot) + "的旧" + kind.label());
            return;
        }

        goToAccess(inventoryTemp, () -> run(
            new MineTaskTakeAction(inventoryTemp, free, chestSlot, "旧" + kind.label()).tolerant(),
            () -> takeTiredOut(entries, index + 1, next)));
    }

    /** 旧的工具都塞进工具缓存（修好还能用）。 */
    private void putTiredIntoCache() {
        MineTaskStationData.ContainerInfo cache = data.firstContainer(MineStationPart.TOOL_CACHE);
        if (cache == null) {
            fail("没有工具缓存，换下来的旧工具没地方放");
            return;
        }
        goToAccess(cache, () -> run(
            new MineTaskContainerAction(cache, MineTaskContainerAction.Mode.TO_CONTAINER,
                stack -> MineToolRules.kindOf(stack) != null, 0, Set.of(), "把旧工具放进缓存"),
            this::checkToolCache));
    }

    /** 数一遍所有工具缓存里「满耐久」的工具够不够（按要换的件数算）。 */
    private void checkToolCache() {
        List<MineTaskStationData.ContainerInfo> caches = data.containers(MineStationPart.TOOL_CACHE);
        if (caches.isEmpty()) {
            fail("没有配置工具缓存");
            return;
        }
        countToolCaches(caches, 0, new LinkedHashMap<>(), () -> {
        });
    }

    private void countToolCaches(List<MineTaskStationData.ContainerInfo> caches, int index,
                                 Map<MineToolRules.Kind, Integer> totals, Runnable next) {
        if (index >= caches.size()) {
            afterToolCheck(totals);
            return;
        }
        MineTaskStationData.ContainerInfo cache = caches.get(index);
        goToAccess(cache, () -> {
            MineTaskContainerAction count = new MineTaskContainerAction(cache,
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "数工具缓存");
            run(count, () -> {
                for (MineToolRules.Kind kind : MineToolRules.Kind.values()) {
                    totals.merge(kind, count.fullTools(kind), Integer::sum);
                }
                countToolCaches(caches, index + 1, totals, next);
            });
        });
    }

    private void afterToolCheck(Map<MineToolRules.Kind, Integer> totals) {
        Map<MineToolRules.Kind, Integer> need = new LinkedHashMap<>();
        for (MineToolRules.Kind kind : tiredSlots.values()) need.merge(kind, 1, Integer::sum);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<MineToolRules.Kind, Integer> entry : need.entrySet()) {
            int have = totals.getOrDefault(entry.getKey(), 0);
            if (have < entry.getValue()) {
                missing.add(entry.getKey().label() + " " + have + "/" + entry.getValue());
            }
        }

        if (missing.isEmpty()) {
            ChatUtils.info("工具缓存里的满耐久工具够用，直接换");
            takeNewTools(0, this::startArmor);
        } else {
            ChatUtils.info("工具缓存里满耐久的工具不够（" + String.join("、", missing) + "），先去修一轮");
            mendTools();
        }
    }

    /** 一件一件换：从缓存拿一把新的到物品栏空格 → 按位置放进大箱子对应那一格。 */
    private void takeNewTools(int index, Runnable next) {
        List<Map.Entry<Integer, MineToolRules.Kind>> entries = new ArrayList<>(tiredSlots.entrySet());
        if (index >= entries.size()) {
            next.run();
            return;
        }
        int chestSlot = entries.get(index).getKey();
        MineToolRules.Kind kind = entries.get(index).getValue();

        MineTaskStationData.ContainerInfo cache = data.firstContainer(MineStationPart.TOOL_CACHE);
        if (cache == null) {
            fail("没有工具缓存");
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        int free = player == null ? -1 : firstFreeSlotForTool(player);
        if (free < 0) {
            fail("物品栏没有空格，拿不了新的" + kind.label());
            return;
        }

        goToAccess(cache, () -> run(
            new MineTaskTakeAction(cache, free,
                stack -> MineToolRules.kindOf(stack) == kind && MineToolRules.isLegal(stack)
                    && MineToolRules.isUsable(stack),
                kind.label()),
            () -> goToAccess(inventoryTemp, () -> run(
                new MineTaskPutSlotAction(inventoryTemp, free, chestSlot, kind.label()),
                () -> takeNewTools(index + 1, next)))));
    }

    /** 不够用：把所有不满的工具拿去 mend，修好再放回来，反复直到没有要修的。 */
    private void mendTools() {
        // 先借一把剑（mend 要求快捷栏第 1 格是带经验修补、剩余耐久 > 100 的剑）
        MineTaskStationData.ContainerInfo cache = data.firstContainer(MineStationPart.TOOL_CACHE);
        if (cache == null) {
            fail("没有工具缓存");
            return;
        }

        goToAccess(cache, () -> run(
            new MineTaskTakeAction(cache, 0,
                stack -> MineToolRules.kindOf(stack) == MineToolRules.Kind.SWORD
                    && MineToolRules.isLegal(stack) && MineTools.remaining(stack) > 101,
                "借一把剑"),
            () -> {
                borrowedSword = true;
                mendRound(cache);
            }));
    }

    /** 一轮：把缓存里所有不满的工具拿走 → 去安全点 mend → 回缓存放回去 → 还有就再来一轮。 */
    private void mendRound(MineTaskStationData.ContainerInfo cache) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (++mendRounds > 5) {
            fail("修了 5 轮工具缓存里还是有不满耐久的工具（mend 没修好？）");
            return;
        }

        goToAccess(cache, () -> run(
            new MineTaskContainerAction(cache, MineTaskContainerAction.Mode.TO_INVENTORY,
                WORN_TOOL, 0, Set.of(), "拿走不满耐久工具"),
            () -> {
                goTo(safePoint.pos(), safePoint.dimension(), () ->
                    run(new MineTaskMendAction(), () -> {
                        goToAccess(cache, () -> run(
                            new MineTaskContainerAction(cache,
                                MineTaskContainerAction.Mode.TO_CONTAINER, stack ->
                                    MineToolRules.kindOf(stack) != null && MineToolRules.isUsable(stack),
                                0, Set.of(), "把修好的工具放回去"),
                            () -> countWorn(caches(), 0, 0, worn -> {
                                if (worn > 0) {
                                    mendRound(cache);
                                } else {
                                    finishMend(cache);
                                }
                            })));
                    }));
            }));
    }

    private List<MineTaskStationData.ContainerInfo> caches() {
        return data.containers(MineStationPart.TOOL_CACHE);
    }

    /** 数一遍所有工具缓存里还有多少件不满耐久的工具。 */
    private void countWorn(List<MineTaskStationData.ContainerInfo> caches, int index, int total,
                           java.util.function.IntConsumer done) {
        if (index >= caches.size()) {
            done.accept(total);
            return;
        }
        MineTaskStationData.ContainerInfo cache = caches.get(index);
        goToAccess(cache, () -> {
            MineTaskContainerAction count = new MineTaskContainerAction(cache,
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "看看还有没有要修的");
            run(count, () -> {
                int worn = 0;
                for (MineToolRules.Kind kind : MineToolRules.Kind.values()) {
                    worn += count.repairTools(kind);
                }
                countWorn(caches, index + 1, total + worn, done);
            });
        });
    }

    /** 修完了：借来的剑放回去，然后按位置把新工具换进大箱子。 */
    private void finishMend(MineTaskStationData.ContainerInfo cache) {
        if (!borrowedSword) {
            takeNewTools(0, this::startArmor);
            return;
        }
        goToAccess(cache, () -> run(
            new MineTaskContainerAction(cache, MineTaskContainerAction.Mode.TO_CONTAINER,
                stack -> stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD),
                1, MineTaskContainerAction.slots(0, 0), "把借来的剑放回去"),
            () -> {
                borrowedSword = false;
                takeNewTools(0, this::startArmor);
            }));
    }

    // ------------------------------------------------------------------
    // 第三步半：盔甲
    // ------------------------------------------------------------------

    /**
     * 盔甲耐久低了也去修一趟（{@code d_start_mining} 就是在盔甲 &lt; 100 的时候叫补货的）。
     *
     * <p>mend 修的是「穿在身上 + 手上」的东西，所以盔甲不用脱，人站到站立点刷经验就行。
     * 前提是快捷栏第 1 格有一把合用的剑（mend 的硬要求）：没有就从工具缓存借一把，
     * 修完再放回去。
     */
    private void startArmor() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (!armorNeedsRepair(player)) {
            startFood();
            return;
        }

        MineTaskStationData.ContainerInfo cache = data.firstContainer(MineStationPart.TOOL_CACHE);
        if (cache == null) {
            fail("盔甲要修，但没有工具缓存（借不到 mend 要用的剑）");
            return;
        }

        ChatUtils.info("盔甲耐久低了，去站立点修一趟");
        ItemStack sword = player.getInventory().getItem(0);
        boolean ok = MineToolRules.kindOf(sword) == MineToolRules.Kind.SWORD
            && MineToolRules.isLegal(sword) && MineTools.remaining(sword) > 101;
        if (ok) {
            mendArmor(cache, false);
            return;
        }
        goToAccess(cache, () -> run(new MineTaskTakeAction(cache, 0,
            stack -> MineToolRules.kindOf(stack) == MineToolRules.Kind.SWORD
                && MineToolRules.isLegal(stack) && MineTools.remaining(stack) > 101,
            "借一把剑"), () -> mendArmor(cache, true)));
    }

    private void mendArmor(MineTaskStationData.ContainerInfo cache, boolean borrowed) {
        goTo(safePoint.pos(), safePoint.dimension(), () -> run(new MineTaskMendAction(), () -> {
            if (!borrowed) {
                startFood();
                return;
            }
            goToAccess(cache, () -> run(
                new MineTaskContainerAction(cache, MineTaskContainerAction.Mode.TO_CONTAINER,
                    stack -> MineToolRules.kindOf(stack) == MineToolRules.Kind.SWORD,
                    1, MineTaskContainerAction.slots(0, 0), "把借来的剑放回去"),
                this::startFood));
        }));
    }

    private static boolean armorNeedsRepair(LocalPlayer player) {
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            if (MineTools.remaining(stack) < 200) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 第四步：拿吃的
    // ------------------------------------------------------------------

    private void startFood() {
        ChatUtils.info("补货 4/5：拿吃的");
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        int have = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.COOKED_PORKCHOP)) have += stack.getCount();
        }
        if (have >= 64) {
            ChatUtils.debug("身上有 %d 个熟猪排，不用补", have);
            startRestore();
            return;
        }

        int need = 64 - have;
        MineTaskStationData.ContainerInfo provider = data.firstContainer(MineStationPart.FOOD_PROVIDER);
        if (provider == null) {
            fail("没有食物补给");
            return;
        }

        goToAccess(provider, () -> run(
            new MineTaskContainerAction(provider, MineTaskContainerAction.Mode.TO_INVENTORY,
                stack -> stack.is(Items.COOKED_PORKCHOP), need, Set.of(), "拿熟猪排"),
            () -> goToAccess(inventoryTemp, () -> run(
                new MineTaskContainerAction(inventoryTemp,
                    MineTaskContainerAction.Mode.TO_CONTAINER,
                    stack -> stack.is(Items.COOKED_PORKCHOP), 0, Set.of(), "把熟猪排放回大箱子"),
                this::startRestore))));
    }

    // ------------------------------------------------------------------
    // 第五步：拿回东西
    // ------------------------------------------------------------------

    private void startRestore() {
        ChatUtils.info("补货 5/5：把东西拿回来");
        goToAccess(inventoryTemp, () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            boolean leftovers = player != null && !inventoryEmpty(player);

            Runnable restore = () -> run(
                new MineTaskContainerAction(inventoryTemp,
                    MineTaskContainerAction.Mode.SYNC_TO_INVENTORY, ANY, 0,
                    MineTaskContainerAction.slots(0, 35), "把大箱子里的东西搬回物品栏"),
                () -> goTo(safePoint.pos(), safePoint.dimension(), () -> {
                    ChatUtils.info("补货完成，已经回到安全点");
                    finish();
                }));

            if (!leftovers) {
                restore.run();
                return;
            }
            // 物品栏里还有没归位的东西（多半是收集盒里没有对应 item_collector 的）：
            // 先塞回大箱子的空格，免得按位置搬回去的时候撞车
            ChatUtils.info("物品栏里还有没送出去的东西，先塞回物品栏暂存处");
            run(new MineTaskContainerAction(inventoryTemp,
                MineTaskContainerAction.Mode.TO_CONTAINER, ANY, 0, Set.of(),
                "把剩下的东西塞回物品栏暂存处"), restore);
        });
    }

    private static boolean inventoryEmpty(LocalPlayer player) {
        for (int slot = 0; slot <= 35; slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 步骤 / 移动 / 等待
    // ------------------------------------------------------------------

    private void push(Runnable step) {
        queue.addLast(step);
    }

    /** 跑一个子动作，跑完接着下一步（下一步插到队首）。 */
    private void run(Action action, Runnable next) {
        child = action;
        queue.addFirst(next);
        action.start();
    }

    private void goTo(BlockPos target, String dimension, Runnable next) {
        PathPlanner.Plan plan = PathPlanner.toPosition(target, dimension);
        if (!plan.ok()) {
            // 人不在路径点网络上（刚从摆放处 / 矿洞深处收工）就先 snap 回去，再重新规划
            if (plan.error() != null && plan.error().contains("既不是路径点")
                && networkSnaps < MAX_NETWORK_SNAPS) {
                networkSnaps++;
                ChatUtils.info("补货：现在不在路径点网络上，先 snap 到最近的路径点再走（第 "
                    + networkSnaps + " 次）");
                run(new MineTaskSnapNetworkAction(), () -> goTo(target, dimension, next));
                return;
            }
            fail("走不到 " + target.toShortString() + "：" + plan.error());
            return;
        }
        networkSnaps = 0;
        if (plan.alreadyThere()) {
            next.run();
            return;
        }
        run(new PathMoveAction(plan.path()), next);
    }

    private void goToAccess(MineTaskStationData.ContainerInfo container, Runnable next) {
        BlockPos access = container.access();
        if (access == null) {
            fail(container.describe() + " 没有落点（重跑 /pc mine station check）");
            return;
        }
        goTo(access, container.dimension(), next);
    }

    /**
     * 走到摆放处 —— **一定要站到盒子上面那一格**。
     *
     * <p>不能因为「已经够得着」就不走：拆盒子的时候掉落物是从盒子那一格往上吸的，
     * 人站在旁边（虽然够得着）就捡不到，拆完等 5 秒直接失败。所以这里老老实实走。
     *
     * <p>人不在路径点网络上（被潜影盒顶高半格之类）时 {@link #goTo} 会先 snap 回最近的路径点。
     */
    private void goToPlacement(MineTaskStationData.Placement spot, Runnable next) {
        BlockPos target = spot.access() != null ? spot.access() : spot.pos().above();
        goTo(target, spot.dimension(), next);
    }

    private void waitTicks(int ticks, Runnable next) {
        run(new MineTaskWaitAction(ticks), next);
    }

    /** 等这一格重新变成容器（收集盒被活塞换掉的那一下）。 */
    private void waitForContainer(BlockPos pos, Runnable next) {
        run(new MineTaskWaitAction(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return false;
            var state = mc.level.getBlockState(pos);
            return state.getBlock() instanceof ShulkerBoxBlock
                || ContainerTypes.isSupported(state.getBlock());
        }, 20 * 10), next);
    }

    /** 在安全点报错（需求里说「回 safe_point，直接报错」）。 */
    private void failAtSafePoint(String reason) {
        ChatUtils.error("补货中止：" + reason);
        queue.clear();
        goTo(safePoint.pos(), safePoint.dimension(), () -> fail(reason));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private String firstDistributableItem() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;

        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (org.six_coin.playerController.client.util.ShulkerUtils.isShulkerBox(stack)) continue;
            String id = idOf(stack);
            if (collectorFor(id) != null) return id;
        }
        return null;
    }

    @Nullable
    private MinePlace collectorFor(String itemId) {
        for (MinePlace place : collectors) {
            if (itemId.equals(place.itemId())) return place;
        }
        return null;
    }

    @Nullable
    private MineTaskStationData.ContainerInfo itemTempWith(Predicate<ItemStack> filter) {
        return data.firstContainerWith(MineStationPart.ITEM_TEMP, id -> {
            Item item = itemOf(id);
            return item != null && filter.test(item.getDefaultInstance());
        });
    }

    private static boolean hasMatching(LocalPlayer player, Predicate<ItemStack> filter) {
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && filter.test(stack)) return true;
        }
        return false;
    }

    private static int firstEmptyInvSlot(LocalPlayer player) {
        for (int slot = 0; slot <= 35; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /**
     * 换工具时临时放物品栏哪一格。
     *
     * <p>优先背包 22~27（那几格「随便」），其次别的空格；<b>0 号格不用</b> ——
     * 那是 mend 借剑的位置（{@code MineTaskMendAction} 要求快捷栏第 1 格是剑）。
     */
    private static int firstFreeSlotForTool(LocalPlayer player) {
        for (int slot = SingleStageCtx.FILLER_START; slot <= SingleStageCtx.FILLER_END; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        for (int slot = 9; slot <= 35; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        for (int slot = 1; slot <= 8; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Nullable
    private static Item itemOf(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return null;
        return BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    // ------------------------------------------------------------------
    // 两个小动作
    // ------------------------------------------------------------------

    /** 等固定的 tick 数。 */
    private static final class MineTaskWaitAction extends Action {

        private final int ticks;
        private final BooleanSupplier condition;
        private final int timeout;
        private int waited;

        MineTaskWaitAction(int ticks) {
            this.ticks = ticks;
            this.condition = null;
            this.timeout = ticks;
        }

        MineTaskWaitAction(BooleanSupplier condition, int timeout) {
            this.ticks = 0;
            this.condition = condition;
            this.timeout = timeout;
        }

        @Override
        public String name() {
            return condition == null ? ("等 " + ticks + " tick") : "等容器出现";
        }

        @Override
        protected void tick() {
            waited++;
            if (condition != null) {
                if (condition.getAsBoolean()) {
                    finish();
                    return;
                }
                if (waited > timeout) {
                    fail("等了 " + (timeout / 20) + " 秒，容器还没出现");
                }
                return;
            }
            if (waited >= ticks) finish();
        }
    }
}
