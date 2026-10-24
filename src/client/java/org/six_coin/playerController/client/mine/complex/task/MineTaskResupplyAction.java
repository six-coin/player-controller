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
 *       前三行 = 背包 27 格、第四行 = 快捷栏，位置一一对应）；把 9 个收集盒摆到 9 个
 *       {@code unloading_shulker_box_placement} 上；逐个倒进物品栏 → 按物品去对应的
 *       {@code item_collector} 装盒（满了就等 1 秒，活塞会换一个新的）；最后把 9 个空盒拆回来、
 *       放回大箱子 13~21 格。</li>
 *   <li><b>补材料</b>：把大箱子 6~8 的圆石盒、9~11 的黏液块盒摆到
 *       {@code supplement_shulker_box_placement} 的 6 个位置；不够就从 {@code item_temp} 里补，
 *       item_temp 也空了就去缓存拿 3 盒倒进 item_temp 再补。</li>
 *   <li><b>换 / 修工具</b>：大箱子里耐久 &lt; 200 的工具换成满耐久的；缓存里满耐久的够就直接换，
 *       不够就把所有不满的工具拿去 {@code /pc mend} 修一轮再换。</li>
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

    private static final Predicate<ItemStack> ANY = stack -> !stack.isEmpty();
    private static final Predicate<ItemStack> COBBLE = stack -> stack.is(Items.COBBLESTONE);
    private static final Predicate<ItemStack> SLIME =
        stack -> stack.is(Items.SLIME_BLOCK) || stack.is(Items.RESIN_BLOCK);
    private static final Predicate<ItemStack> TOOL = stack -> MineToolRules.kindOf(stack) != null;
    private static final Predicate<ItemStack> WORN_TOOL = stack -> {
        MineToolRules.Kind kind = MineToolRules.kindOf(stack);
        return kind != null && stack.getDamageValue() > 0;
    };
    /** 耐久 &lt; 200 的工具（要换掉的）。 */
    private static final Predicate<ItemStack> TIRED_TOOL = stack -> {
        MineToolRules.Kind kind = MineToolRules.kindOf(stack);
        return kind != null && MineTools.remaining(stack) < 200;
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
    /** 补充盒里现在各有多少（下标 = supplement 的下标）。 */
    private final Map<Integer, Integer> boxCounts = new LinkedHashMap<>();
    /** 缓存倒进 item_temp 的轮数（防止死循环）。 */
    private int fetchRounds;
    /** 这一轮要换的工具种类。 */
    private final List<MineToolRules.Kind> toolKinds = new ArrayList<>();
    /** 修工具时临时借的那把剑（在快捷栏第 1 格）。 */
    private boolean borrowedSword;
    /** 已经修了几轮（防止「修不完 → 再拿 → 再修」死循环）。 */
    private int mendRounds;
    /** 每个产物收集盒连着「满了」几次（活塞一直不换新的就报错）。 */
    private final Map<String, Integer> collectorRetries = new LinkedHashMap<>();
    /** 拆收集盒之前从 inventory_temp 借的镐子（在快捷栏第 2 格）。 */
    private boolean borrowedPickaxe;
    /** 「不在路径点网络上 → snap 回网络」用了几次（防止死循环）。 */
    private int networkSnaps;

    public MineTaskResupplyAction(String task, MineTaskRegion region, MineTaskStationData data) {
        this.task = task;
        this.region = region;
        this.data = data;
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
        boxIndex = 0;
        placeNextBox();
    }

    private void placeNextBox() {
        if (boxIndex >= 9) {
            boxIndex = 0;
            unloadNextBox();
            return;
        }

        MineTaskStationData.Placement spot = unloading.get(boxIndex);
        int inventorySlot = SingleStageCtx.COLLECT_START + boxIndex;
        goToPlacement(spot, () -> run(
            new MineTaskPlaceBoxAction(inventorySlot, spot.pos(), "收集盒 " + (boxIndex + 1)),
            () -> {
                boxIndex++;
                placeNextBox();
            }));
    }

    private void unloadNextBox() {
        if (boxIndex >= 9) {
            boxIndex = 0;
            breakAllBoxes();
            return;
        }

        MineTaskStationData.Placement spot = unloading.get(boxIndex);
        goToPlacement(spot, () -> {
            MineTaskContainerAction count = new MineTaskContainerAction(spot.pos(),
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "看看收集盒里有什么");
            run(count, () -> {
                if (count.countedEmpty() >= count.countedSlots()) {
                    ChatUtils.debug("第 %d 个收集盒是空的，跳过", boxIndex + 1);
                    boxIndex++;
                    unloadNextBox();
                    return;
                }
                ChatUtils.info("第 " + (boxIndex + 1) + " 个收集盒里有："
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
     * 拆 9 个收集盒。
     *
     * <p>空手拆一个要 3 秒，所以先从 inventory_temp 把镐子借出来（快捷栏第 2 格，
     * 也就是它本来的位置）；借不到才空手。拆完再放回原格。
     */
    private void breakAllBoxes() {
        boxIndex = 0;
        goToAccess(inventoryTemp, () -> {
            MineTaskTakeAction take = new MineTaskTakeAction(inventoryTemp,
                SingleStageCtx.HOTBAR_PICKAXE, SingleStageCtx.HOTBAR_PICKAXE + 27, "镐子").tolerant();
            run(take, () -> {
                borrowedPickaxe = take.taken() != null;
                if (!borrowedPickaxe) {
                    ChatUtils.debug("物品栏暂存处的快捷栏第 2 格没有镐子，空手拆收集盒");
                }
                breakNextBox();
            });
        });
    }

    private void breakNextBox() {
        if (boxIndex >= 9) {
            returnPickaxe();
            return;
        }
        MineTaskStationData.Placement spot = unloading.get(boxIndex);
        goToPlacement(spot, () -> run(
            new MineTaskBreakBoxAction(spot.pos(), SingleStageCtx.HOTBAR_PICKAXE, "收集盒 " + (boxIndex + 1)),
            () -> {
                boxIndex++;
                breakNextBox();
            }));
    }

    /** 把借来的镐子放回 inventory_temp 的快捷栏第 2 格（箱子第 28 格）。 */
    private void returnPickaxe() {
        if (!borrowedPickaxe) {
            putBoxesBack();
            return;
        }
        goToAccess(inventoryTemp, () -> run(
            new MineTaskContainerAction(inventoryTemp, MineTaskContainerAction.Mode.SYNC_TO_CONTAINER,
                ANY, 0, Set.of(SingleStageCtx.HOTBAR_PICKAXE), "把借的镐子放回去"),
            () -> {
                borrowedPickaxe = false;
                putBoxesBack();
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
            fail("拆完收集盒之后物品栏里有 " + boxSlots.size() + " 个空盒（应该是 9 个）");
            return;
        }

        List<MineTaskInventoryMoveAction.Move> moves = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
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

    private void restockMaterial(Material material, Runnable next) {
        goToAccess(inventoryTemp, () -> placeSupplementBoxes(material, 0, () -> {
            boxCounts.clear();
            countSupplementBoxes(material, 0, () -> fillLoop(material, next));
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

    /** 数一下补充摆放处那 3 个盒子里各有多少。 */
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
                data.recordPlacementBox(MineStationPart.SUPPLEMENT_PLACEMENT, spot.pos(), count.counted());
                countSupplementBoxes(material, index + 1, next);
            });
        });
    }

    /** 缺口 → 从 item_temp 拿 → 往盒子里装，直到 3 个盒子都满 / item_temp 没货。 */
    private void fillLoop(Material material, Runnable next) {
        int deficit = deficit(material);
        if (deficit <= 0) {
            finishMaterial(material, next);
            return;
        }

        MineTaskStationData.ContainerInfo source = itemTempWith(material.filter());
        if (source == null) {
            fetchFromCache(material, () -> fillLoop(material, next));
            return;
        }

        goToAccess(source, () -> run(
            new MineTaskContainerAction(source, MineTaskContainerAction.Mode.TO_INVENTORY,
                material.filter(), Math.min(TRIP_LIMIT, deficit), Set.of(),
                "从 item_temp 拿" + material.label()),
            () -> distributeToBoxes(material, next)));
    }

    private void distributeToBoxes(Material material, Runnable next) {
        int index = firstDeficientBox(material);
        if (index < 0) {
            next.run();
            return;
        }

        MineTaskStationData.Placement spot = supplement.get(index);
        goToPlacement(spot, () -> {
            MineTaskContainerAction put = new MineTaskContainerAction(spot.pos(),
                MineTaskContainerAction.Mode.TO_CONTAINER, material.filter(), 0, Set.of(),
                "往盒子里装" + material.label());
            run(put, () -> {
                MineTaskContainerAction count = new MineTaskContainerAction(spot.pos(),
                    MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "数" + material.label());
                run(count, () -> {
                    boxCounts.put(index, materialCount(material, count.counted()));
                    distributeToBoxes(material, next);
                });
            });
        });
    }

    /** 这一种材料补完了：物品栏里还剩的同类东西放回 item_temp，然后下一步。 */
    private void finishMaterial(Material material, Runnable next) {
        LocalPlayer player = Minecraft.getInstance().player;
        MineTaskStationData.ContainerInfo source = itemTempWith(material.filter());
        if (player != null && source != null && hasMatching(player, material.filter())) {
            goToAccess(source, () -> run(
                new MineTaskContainerAction(source, MineTaskContainerAction.Mode.TO_CONTAINER,
                    material.filter(), 0, Set.of(), "剩下的" + material.label() + "放回 item_temp"),
                next));
            return;
        }
        next.run();
    }

    /** item_temp 里没货了：去缓存拿 3 盒倒进 item_temp，再回到上面的循环。 */
    private void fetchFromCache(Material material, Runnable next) {
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
                ChatUtils.info("item_temp 里没" + material.label() + "了：从"
                    + material.cache().display() + "搬 3 盒过来");
                fetchBoxes(material, cache, 0, () -> dumpTempBoxes(material, 0,
                    () -> recycleTempBoxes(material, 0, next)));
            });
        });
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

    /** 临时摆放处那 3 个盒子：倒进物品栏 → 放进 item_temp。 */
    private void dumpTempBoxes(Material material, int index, Runnable next) {
        if (index >= 3) {
            next.run();
            return;
        }

        MineTaskStationData.Placement spot = temp.get(index);
        goToPlacement(spot, () -> run(
            new MineTaskContainerAction(spot.pos(), MineTaskContainerAction.Mode.TO_INVENTORY,
                ANY, 0, Set.of(), "把临时盒倒进物品栏"),
            () -> {
                MineTaskStationData.ContainerInfo target = data.firstContainer(MineStationPart.ITEM_TEMP);
                if (target == null) {
                    fail("station_data 里没有任务前物品暂存处（item_temp）");
                    return;
                }
                goToAccess(target, () -> run(
                    new MineTaskContainerAction(target,
                        MineTaskContainerAction.Mode.TO_CONTAINER, material.filter(), 0, Set.of(),
                        "把" + material.label() + "放进 item_temp"),
                    () -> dumpTempBoxes(material, index + 1, next)));
            }));
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

    private void startTools() {
        ChatUtils.info("补货 3/5：换工具 / 修工具");
        goToAccess(inventoryTemp, () -> {
            MineTaskContainerAction scan = new MineTaskContainerAction(inventoryTemp,
                MineTaskContainerAction.Mode.COUNT, ANY, 0, Set.of(), "看看有哪些工具要换");
            run(scan, () -> {
                toolKinds.clear();
                for (MineTaskContainerAction.ToolEntry entry : scan.tools()) {
                    if (entry.slot() > 4) continue;          // 只有背包 1~5 会到 <200
                    if (entry.remaining() >= 200) continue;
                    if (!toolKinds.contains(entry.kind())) toolKinds.add(entry.kind());
                }
                if (toolKinds.isEmpty()) {
                    ChatUtils.info("没有耐久 <200 的工具，跳过换工具");
                    startArmor();
                    return;
                }

                ChatUtils.info("要换的工具：" + toolKinds);
                MineTaskContainerAction take = new MineTaskContainerAction(inventoryTemp,
                    MineTaskContainerAction.Mode.TO_INVENTORY, TIRED_TOOL, 0, Set.of(),
                    "把要换的工具拿出来");
                run(take, () -> {
                    if (take.moved() == 0) {
                        fail("要把 " + toolKinds + " 拿去换，但物品栏放不下（或者大箱子里已经没有这些工具）");
                        return;
                    }
                    checkToolCache();
                });
            });
        });
    }

    /** 数一遍所有工具缓存里「满耐久」的工具够不够。 */
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
        boolean enough = true;
        for (MineToolRules.Kind kind : toolKinds) {
            if (totals.getOrDefault(kind, 0) < 1) {
                enough = false;
                break;
            }
        }
        if (enough) {
            ChatUtils.info("工具缓存里的满耐久工具够用，直接换");
            swapTools();
        } else {
            ChatUtils.info("工具缓存里满耐久的工具不够，先去修一轮");
            mendTools();
        }
    }

    /** 够用：去缓存把旧的放进去、每种拿一把满耐久的，回大箱子放到 1~5 格。 */
    private void swapTools() {
        MineTaskStationData.ContainerInfo cache = data.firstContainer(MineStationPart.TOOL_CACHE);
        if (cache == null) {
            fail("没有工具缓存");
            return;
        }

        goToAccess(cache, () -> run(
            new MineTaskContainerAction(cache, MineTaskContainerAction.Mode.TO_CONTAINER,
                TIRED_TOOL, 0, Set.of(), "把要换的工具放进缓存"),
            () -> takeTools(cache, 0, () -> goToAccess(inventoryTemp, () -> run(
                new MineTaskContainerAction(inventoryTemp,
                    MineTaskContainerAction.Mode.SYNC_TO_CONTAINER, ANY, 0,
                    MineTaskContainerAction.slots(SingleStageCtx.SPARE_START,
                        SingleStageCtx.SPARE_START + 4),
                    "把新工具放进大箱子 1~5 格"),
                this::startArmor)))));
    }

    /** 每种要换的工具拿一把满耐久的（放进背包 1~5 对应的位置）。 */
    private void takeTools(MineTaskStationData.ContainerInfo cache, int index, Runnable next) {
        if (index >= toolKinds.size()) {
            next.run();
            return;
        }
        MineToolRules.Kind kind = toolKinds.get(index);
        run(new MineTaskTakeAction(cache, SingleStageCtx.SPARE_START + kind.ordinal(),
            stack -> MineToolRules.kindOf(stack) == kind && MineToolRules.isLegal(stack)
                && MineToolRules.isUsable(stack),
            kind.label()),
            () -> takeTools(cache, index + 1, next));
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

    /** 修完了：拿每种要换的工具各一把，借来的剑放回去，然后回大箱子放到 1~5 格。 */
    private void finishMend(MineTaskStationData.ContainerInfo cache) {
        goToAccess(cache, () -> takeTools(cache, 0, () -> {
            if (!borrowedSword) {
                goToAccess(inventoryTemp, this::putToolsIntoTemp);
                return;
            }
            goToAccess(cache, () -> run(
                new MineTaskContainerAction(cache, MineTaskContainerAction.Mode.TO_CONTAINER,
                    stack -> stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD),
                    1, MineTaskContainerAction.slots(0, 0), "把借来的剑放回去"),
                () -> {
                    borrowedSword = false;
                    goToAccess(inventoryTemp, this::putToolsIntoTemp);
                }));
        }));
    }

    private void putToolsIntoTemp() {
        run(new MineTaskContainerAction(inventoryTemp,
            MineTaskContainerAction.Mode.SYNC_TO_CONTAINER, ANY, 0,
            MineTaskContainerAction.slots(SingleStageCtx.SPARE_START,
                SingleStageCtx.SPARE_START + 4),
            "把新工具放进大箱子 1~5 格"),
            this::startArmor);
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
