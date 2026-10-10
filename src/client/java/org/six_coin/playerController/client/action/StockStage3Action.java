package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.station.StationState;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.stock.StockMaterials;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.ItemRules;
import org.six_coin.playerController.client.util.ShulkerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /pc stock task <名字> start} 的「第一部分 阶段3：分盒重装并进入第二部分」。
 *
 * <p>按需求：
 * <ol>
 *   <li><b>最终产物暂存处</b>固定用潜影盒摆放处 <b>id = 1</b>。开始先立刻从空潜影盒提供处取一个空盒放到那里。
 *       之后每次往暂存处放东西都走同一套：能放就放，盒子满了就<b>更换操作</b> ——
 *       退出界面 → 用快捷栏第一格的钻石镐挖掉 id=1 的潜影盒 → {@code move to_position}(盒子上面那一格)
 *       → 自然下落 → 捡起掉落物（落在快捷栏第二格）→ {@code move y 1} → {@code move to_position}(站立点)
 *       → 把捡到的盒子放进 item_final → 再从提供处取一个空盒放到 id=1；</li>
 *   <li><b>第 2 步</b>：遍历 item_storage，用 {@code only_item} 把 {@code final_pack} 里的东西按<b>物品形态</b>取出来
 *       （潜影盒整个无视）。物品栏满了就倒进最终产物暂存处，然后回到同一个容器接着取；</li>
 *   <li><b>第 3 步</b>：再处理<b>装在潜影盒里</b>的那些。维护 next_shulker_box_placement_id（初始 2，
 *       越界就回 2）：用 {@code only_one_shulker} 从 item_storage 取出一个装着所需物品的盒子放到摆放处，
 *       对着这个盒子 {@code get} 需要的东西；没拿完就先倒进暂存处再拿一遍，直到 all_cleared。</li>
 * </ol>
 *
 * <p>注意装盒用的是 {@code final_pack}（{@code 1_3_material_can_access_all.json} 的副本）：
 * 它是**完整需求量**，而 {@code final_final} 是「还缺多少、要去容器里取多少」。
 * 仓库里本来就有的那部分（比如仓库有 10 个、需求 30）也要一起装进盒子，
 * 所以这里必须用 final_pack，不能拿 final_final 当需求。
 *
 * <p>「挖盒子 → 落下去捡 → 飞回来 → 走回站立点」这一整段飞行是**连着**的：
 * {@code FlightVelocity} 在这里 begin 一次，中途每一段移动自己 begin/end 不会把飞行关掉，
 * 不然玩家会在两步之间自己掉下去、不在路径点上（需求里特意点出来的坑）。
 *
 * <p>这里所有开箱子都在站立点上做（工作站里那几个容器本来就在站立点的触及范围内）。
 */
public class StockStage3Action extends Action {

    /** 最终产物暂存处固定用摆放处 id 1。 */
    public static final int STAGING_ID = 1;

    /** 挖盒子用的是快捷栏第一格的钻石镐（阶段1 放进去的）。 */
    private static final int PICKAXE_HOTBAR = StockStartAction.PICKAXE_HOTBAR;

    /** 捡起来的潜影盒会落在快捷栏第二格（背包下标 1）。 */
    private static final int PICKUP_HOTBAR = 1;

    /** only_one_shulker 把盒子放进快捷栏第三格（背包下标 2）。 */
    private static final int BOX_HOTBAR = ContainerGetAction.HOTBAR_BOX_INDEX;

    /** 等掉落物被捡起来最多等这么多 tick。 */
    private static final int PICKUP_WAIT_TICKS = 20 * 15;

    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60;

    // ------------------------------------------------------------------
    // 小的「步骤队列」：动作本身是同步写不出来的一长串，这里拆成一步步跑
    // ------------------------------------------------------------------

    /** 队列里的一步。 */
    private interface Step {
        String label();
    }

    /** 同步步骤：这一 tick 直接跑掉。 */
    private record SyncStep(String label, Runnable body) implements Step {
    }

    /** 子动作步骤：跑一个 {@link Action}，跑完交给 after。 */
    private record ChildStep(String label, Supplier<Action> factory, @Nullable Consumer<Action> after)
        implements Step {
    }

    /** 盒子捡起来以后要放哪儿。 */
    private enum PutTarget {
        /** 最终产物地（暂存处挖出来的成品盒子）。 */
        FINAL,
        /** 物资存储地（从摆放处挖出来的、借来的盒子）。 */
        STORAGE
    }

    private final String task;

    /**
     * 阶段2 传过来的 station_data（里面记着阶段2 往 item_storage / item_final 里倒进去的东西）。
     *
     * <p>传 null 就自己从 check.json 读一份（单独跑阶段3 的时候用）。
     */
    @Nullable
    private final StationState handedState;

    private StationState state;
    /** 还要装进暂存处的成品（final_pack，**完整需求量**；每次取货会扣减它）。 */
    private ItemList need;
    /** 完整的一份 final_pack，只用来判断「这是不是成品」（不会被扣）。 */
    private ItemList finalWanted;

    private final List<Integer> storageIds = new ArrayList<>();
    private final List<Integer> finalIds = new ArrayList<>();
    private final List<Integer> placementIds = new ArrayList<>();

    /** 下一个要用哪个摆放处（需求里的 next_shulker_box_placement_id，初始 2）。 */
    private int nextPlacementId = 2;

    private final Deque<Step> steps = new ArrayDeque<>();
    private Action child;
    private Consumer<Action> afterChild;

    /**
     * 正在跑的这一步（同步步骤 / 子动作的收尾回调）里新加的步骤。
     *
     * <p>它们是「接着往下做」的，必须插到队首：比如 {@link #pushMoveTo} 本身是个同步步骤，
     * 它跑的时候才真正把「走过去」这个子动作建出来，要是直接丢到队尾，
     * 就会排在后面那些步骤后面，顺序全乱。
     */
    private final List<Step> pending = new ArrayList<>();
    private boolean executing;

    /** 第 2 / 第 3 步走到 item_storage 的第几个了。 */
    private int step2Index;
    private int step3Index;

    /** 从 container 里拿潜影盒之前，那个容器里都有哪些盒子（拿来算被拿走的是哪个）。 */
    private List<Map<String, Integer>> boxesBeforeTake = List.of();
    /** 盒子捡起来以后落在哪一格（正常情况下就是快捷栏第二格）。 */
    private int pickupHotbar = PICKUP_HOTBAR;

    private int totalTicks;
    private int replaced;
    private int takenBoxes;

    public StockStage3Action(String task) {
        this(task, null);
    }

    public StockStage3Action(String task, @Nullable StationState handedState) {
        this.task = task;
        this.handedState = handedState;
    }

    /**
     * 这一轮维护的 station_data（第二部分接着用）。
     *
     * <p>跑完会把「下一个用哪个摆放处」留给第二部分阶段3（station_data 不落盘，只在内存里传）。
     */
    public StationState state() {
        return state;
    }

    @Override
    public String name() {
        return "备货 " + task + " 阶段3（分盒重装）";
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        try {
            if (handedState != null) {
                state = handedState;
                ChatUtils.debug("阶段3 直接用阶段2 交过来的 station_data（里面有阶段2 倒进去的东西）");
            } else {
                state = StationState.load(PlayerControllerConfig.getWorld());
            }
            loadNeed();
        } catch (Exception e) {
            fail("准备阶段3 失败：" + e.getMessage());
            return;
        }

        collectPositions();

        if (WaypointManager.get().isEditMode()) {
            ChatUtils.error("路径点编辑模式开着：阶段3 的移动会顺手记路径点，建议先关掉（/pc w edit）");
        }

        if (placementIds.size() < 2) {
            fail("潜影盒摆放处少于 2 个（暂存处要用 id 1，第 3 步还要用别的 id），先补几个");
            return;
        }
        if (storageIds.isEmpty()) {
            fail("没有物资存储地（item_storage）");
            return;
        }
        for (int id : storageIds) {
            StationState.Storage storage = state.storage(id);
            if (storage == null || storage.freeSlots() < 0) {
                ChatUtils.error("物资存储地 #" + id + " 的内容不明（阶段1 检查时没数到？），阶段3 可能会漏东西");
            }
        }

        if (need.isEmpty()) {
            ChatUtils.info("final_pack 里没有要装盒的东西了，阶段3 直接结束");
            finish();
            return;
        }

        // 挖盒子要用快捷栏第一格的钻石镐；捡起来的东西会落在快捷栏第二格，所以它得是空的
        pushCheckTools();

        // 人得站在站立点上（工作站里那几个容器都在站立点的触及范围内）
        StationPos stand = standPoint();
        if (stand == null) {
            fail("没有设置站立点");
            return;
        }
        if (!stand.pos().equals(currentBlock())) {
            pushMoveTo(stand.pos(), "先走回站立点 " + stand.coordString());
        }

        pushPrepareStaging();
        pushStep2();

        ChatUtils.info("阶段3 开始：要装进暂存处的成品 " + need.describe()
            + "；摆放处 " + placementIds + "（暂存处用 #" + STAGING_ID + "）");
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

        // 先把正在跑的子动作推进完
        if (child != null) {
            child.update();
            if (!child.isFinished()) return;

            Action done = child;
            Consumer<Action> after = afterChild;
            child = null;
            afterChild = null;

            done.cleanup();
            if (done.failureReason() != null) {
                fail("子任务失败（" + done.name() + "）：" + done.failureReason());
                return;
            }
            if (after != null) runWithInsertion(() -> after.accept(done));
            if (isFinished()) return;
            if (child != null) return;   // after 里又起了新的子动作
        }

        // 再往下跑同步步骤，直到需要起子动作 / 队列空了
        while (!isFinished()) {
            Step step = steps.pollFirst();
            if (step == null) break;

            ChatUtils.debug("阶段3 步骤：" + step.label());
            if (step instanceof SyncStep sync) {
                runWithInsertion(sync.body());
                continue;
            }

            ChildStep childStep = (ChildStep) step;
            Action action = childStep.factory().get();
            if (action == null) {
                fail("内部错误：步骤「" + childStep.label() + "」没生成动作");
                return;
            }
            child = action;
            afterChild = childStep.after();
            child.start();
            return;
        }

        if (!isFinished() && child == null) {
            ChatUtils.info("阶段3（分盒重装）完成：换了 " + replaced + " 次暂存盒，搬了 "
                + takenBoxes + " 个盒子");
            if (!need.isEmpty()) {
                ChatUtils.error("还有东西没凑齐：" + need.describe() + "（仓库里可能本来就没有）");
            }
            ChatUtils.info("最终产物暂存处（摆放处 #" + STAGING_ID + "）还留着一个盒子，里面是这次没装满的成品；"
                + "摆放处 " + placementIds + " 上借来的盒子也留在原地（下次用到那个 id 时会送回 item_storage）");
            // 把「下一个用哪个摆放处」留给第二部分阶段3 接着用（station_data 不落盘，只在内存里传）
            state.setNextShulkerBoxPlacementId(nextPlacementId);
            ChatUtils.debug("下一个潜影盒摆放处 id = " + nextPlacementId + "（交给第二部分阶段3）");
            finish();
        }
    }

    @Override
    protected void onEnd() {
        endFlightHold();
        if (failureReason() != null) {
            ChatUtils.error(name() + "没做完：" + failureReason());
        }
    }

    // ------------------------------------------------------------------
    // 准备
    // ------------------------------------------------------------------

    private void loadNeed() throws Exception {
        // 装盒用的是 final_pack（**完整需求量**），不是 final_final（那是要去取货的量）：
        // 仓库里本来就有的那部分也要一起装进盒子，不然 10 + 拿回来的 20 只会装 20 个。
        Path file = StockManager.get().currentFinalPackFile(task);
        if (!Files.exists(file)) {
            throw new IllegalStateException("找不到 " + file + "（第一部分阶段1 生成的？）");
        }

        String json = Files.readString(file, StandardCharsets.UTF_8);
        need = ItemList.parseOrEmpty(json);
        finalWanted = ItemList.parseOrEmpty(json);
    }

    private void collectPositions() {
        for (StationPos pos : StationManager.get().list(StationPart.ITEM_STORAGE)) {
            storageIds.add(pos.id());
        }
        storageIds.sort(Integer::compareTo);

        for (StationPos pos : StationManager.get().list(StationPart.ITEM_FINAL)) {
            finalIds.add(pos.id());
        }
        finalIds.sort(Integer::compareTo);

        for (StationPos pos : StationManager.get().list(StationPart.SHULKER_BOX_PLACEMENT)) {
            placementIds.add(pos.id());
        }
        placementIds.sort(Integer::compareTo);

        nextPlacementId = 2;
        ChatUtils.debug("阶段3：item_storage " + storageIds + "，item_final " + finalIds
            + "，摆放处 " + placementIds);
        state.setNextShulkerBoxPlacementId(nextPlacementId);
    }

    /** 挖盒子之前先确认工具和快捷栏位置都对。 */
    private void pushCheckTools() {
        pushSync("检查快捷栏（第一格钻石镐、第二格空的）", () -> {
            ItemStack tool = hotbarStack(PICKAXE_HOTBAR);
            if (!tool.isOf(net.minecraft.item.Items.DIAMOND_PICKAXE)) {
                ChatUtils.error("快捷栏第一格里不是钻石镐（是 " + describe(tool) + "），挖盒子会挖很久");
            }
            if (!hotbarStack(PICKUP_HOTBAR).isEmpty()) {
                fail("快捷栏第二格被 " + describe(hotbarStack(PICKUP_HOTBAR))
                    + " 占了；挖下来的潜影盒会顺手填到那一格，先腾出来");
            }
        });
    }

    // ------------------------------------------------------------------
    // 最终产物暂存处
    // ------------------------------------------------------------------

    /** 开始：从空潜影盒提供处取一个空盒，放到暂存处（摆放处 id 1）。 */
    private void pushPrepareStaging() {
        pushSync("准备最终产物暂存处（摆放处 #" + STAGING_ID + "）", () ->
            ChatUtils.info("先从空潜影盒提供处取一个空潜影盒放到最终产物暂存处"));
        pushTakeProviderBox();
        pushPlaceHeldBox(STAGING_ID, false, Map.of(), "暂存盒");
    }

    /** 从空潜影盒提供处取一个**空**潜影盒，放进快捷栏第三格。 */
    private void pushTakeProviderBox() {
        StationPos provider = StationManager.get().single(StationPart.SHULKER_BOX_PROVIDER);
        if (provider == null) {
            pushSync("没有空潜影盒提供处", () -> fail("没有设置空潜影盒提供处（shulker_box_provider）"));
            return;
        }

        pushChild("从空潜影盒提供处取一个空盒（放快捷栏第三格）",
            () -> new TakeItemAction(provider.pos(), BOX_HOTBAR,
                stack -> ShulkerUtils.isShulkerBox(stack)
                    && ItemRules.customNameOf(stack) == null
                    && ShulkerUtils.contents(stack).isEmpty(),
                "空潜影盒"),
            action -> {
                if (!((TakeItemAction) action).found()) {
                    fail("空潜影盒提供处里没有空潜影盒了（需求：要整桶都是空潜影盒）");
                }
            });
    }

    /**
     * 把快捷栏第三格里的盒子放到某个摆放处。
     *
     * <p>那一格要是已经有盒子了，先按需求「挖掉 → 捡起来 → 回站立点 → 把盒子放回 item_storage」，
     * 然后再放新盒子。
     *
     * @param advance  放完要不要把 next_shulker_box_placement_id 加一（暂存处 id 1 不加）
     * @param contents 放上去的这个盒子里装着什么（记账用；新盒子就是空的）
     */
    private void pushPlaceHeldBox(int placementId, boolean advance, Map<String, Integer> contents, String what) {
        pushSync("看看摆放处 #" + placementId + " 上有没有盒子", () -> {
            StationPos spot = placement(placementId);
            if (spot == null) {
                fail("找不到潜影盒摆放处 #" + placementId);
                return;
            }

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null) {
                net.minecraft.block.BlockState blockState = mc.world.getBlockState(spot.pos());
                if (!blockState.isAir()
                    && !(blockState.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock)) {
                    fail("摆放处 #" + placementId + " " + spot.coordString() + " 上有别的方块（"
                        + net.minecraft.registry.Registries.BLOCK.getId(blockState.getBlock())
                        + "），先自己清掉");
                    return;
                }
                if (!blockState.isAir()) {
                    ChatUtils.info("摆放处 #" + placementId + " 上已经有盒子了，先挖掉送回 item_storage");
                    pushMineAndCollect(spot, placementId, PutTarget.STORAGE);
                }
            }
            pushPlaceAt(spot, placementId, advance, contents, what);
        });
    }

    private void pushPlaceAt(StationPos spot, int placementId, boolean advance,
                             Map<String, Integer> contents, String what) {
        pushChild("把" + what + "放到摆放处 #" + placementId + "（" + spot.coordString() + "）",
            () -> new PlaceShulkerBoxAction(spot.pos(), BOX_HOTBAR),
            action -> {
                state.setPlacement(placementId, contents);
                ChatUtils.info("摆放处 #" + placementId + " 放好了" + what + "（"
                    + (contents.isEmpty() ? "空盒子" : "内容 " + contents) + "）");
                if (advance) advancePlacementId();
            });
    }

    /** 更换操作：暂存盒满了 —— 挖掉、捡起来、送回 item_final，再放一个新的空盒。 */
    private void pushReplaceStagingBox() {
        StationPos spot = placement(STAGING_ID);
        if (spot == null) {
            pushSync("找不到暂存处", () -> fail("找不到潜影盒摆放处 #" + STAGING_ID));
            return;
        }

        replaced++;
        pushSync("开始更换操作（第 " + replaced + " 次）", () ->
            ChatUtils.info("更换最终产物暂存处的盒子：挖掉装好的盒子送去 item_final，再放一个新空盒"));
        pushMineAndCollect(spot, STAGING_ID, PutTarget.FINAL);
        pushTakeProviderBox();
        pushPlaceHeldBox(STAGING_ID, false, Map.of(), "新暂存盒");
    }

    /**
     * 挖掉摆放处的潜影盒 → 等自然下落、捡起来 → {@code move y 1} → 走回站立点 → 把盒子放进目标容器。
     *
     * <p>整段都持有飞行（begin 一次，中途各段自己 begin/end 不会把飞行关掉）。
     */
    private void pushMineAndCollect(StationPos spot, int placementId, PutTarget target) {
        pushSync("准备挖 " + spot.coordString() + " 的潜影盒", () -> {
            if (!hotbarStack(PICKUP_HOTBAR).isEmpty()) {
                fail("快捷栏第二格被 " + describe(hotbarStack(PICKUP_HOTBAR)) + " 占了，先腾出来");
                return;
            }
            pickupHotbar = PICKUP_HOTBAR;
            ChatUtils.debug("挖之前物品栏里有 " + countShulkerBoxes() + " 个潜影盒");
        });

        // 这一段飞行整段持有（引用计数），中途不关飞行
        pushChild("挖掉摆放处 #" + placementId + " 的潜影盒",
            () -> {
                beginFlightHold();
                return new MineBlockAction(spot.pos(), PICKAXE_HOTBAR);
            },
            action -> state.clearPlacement(placementId));

        // 站到盒子上面那一格，然后不写速度，让重力把人带下去，掉到盒子的位置捡东西。
        // 掉落物一定会顺位填进快捷栏第二格（那一格我们确保是空的），所以等的就是「第二格里有潜影盒了」。
        pushMoveTo(spot.pos().up(), "走到潜影盒上面那一格 " + spot.pos().up().toShortString());
        pushChild("等自然下落并捡起潜影盒",
            () -> new WaitAction("捡起潜影盒", this::hasPickupBox, PICKUP_WAIT_TICKS, false),
            action -> {
                if (!((WaitAction) action).met()) {
                    fail("等了 " + PICKUP_WAIT_TICKS + " tick，快捷栏第二格还是没有潜影盒"
                        + "（人没落下去？掉落物掉到别处了？）");
                    return;
                }
                pickupHotbar = PICKUP_HOTBAR;
                ChatUtils.info("潜影盒已经捡到快捷栏第二格（" + describe(hotbarStack(PICKUP_HOTBAR)) + "）");
            });

        // 飞上来（这一段结束之后飞行不能关，不然又会掉下去，所以后面马上接走回站立点）
        pushChild("往上飞一格（move y 1）", () -> new MoveAction(Direction.Axis.Y, 1), null);
        pushMoveTo(requireStandPoint().pos(), "走回站立点");
        pushPutHotbarBox(target);
        pushSync("飞行收尾", this::endFlightHold);
    }

    // ------------------------------------------------------------------
    // 把捡起来的盒子放进 item_final / item_storage
    // ------------------------------------------------------------------

    private void pushPutHotbarBox(PutTarget target) {
        pushSync("把捡起来的盒子放进" + targetName(target), () -> {
            // 用 station_data 里「还没满」的那些（阶段2 倒过东西，可能已经有满的了）
            List<Integer> ids = new ArrayList<>(target == PutTarget.FINAL
                ? state.finalTargets()
                : state.storageTargets());
            if (ids.isEmpty()) {
                fail(targetName(target) + "都满了，盒子没地方放");
                return;
            }
            pushPutHotbarTo(target, ids, 0);
        });
    }

    private void pushPutHotbarTo(PutTarget target, List<Integer> ids, int index) {
        if (index >= ids.size()) {
            final int total = ids.size();
            pushSync("没地方放盒子了", () ->
                fail(targetName(target) + " 的 " + total + " 个容器都放不下这个盒子了"));
            return;
        }

        int id = ids.get(index);
        StationPos pos = stationPos(target == PutTarget.FINAL ? StationPart.ITEM_FINAL : StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            pushPutHotbarTo(target, ids, index + 1);
            return;
        }

        pushChild("把盒子放进" + targetName(target) + " #" + id + "（" + pos.coordString() + "）",
            () -> new PutHotbarItemAction(pos.pos(), pickupHotbar, true),
            action -> {
                PutHotbarItemAction put = (PutHotbarItemAction) action;
                if (put.detail() != null) {
                    if (target == PutTarget.STORAGE) {
                        state.setStorage(id, put.detail(), put.freeSlots());
                    } else {
                        state.setFinalFull(id, put.containerFull());
                    }
                }

                if (put.allCleared()) {
                    ChatUtils.debug("盒子已经放进" + targetName(target) + " #" + id);
                    return;
                }
                ChatUtils.info(targetName(target) + " #" + id + " 装不下了，换下一个");
                pushPutHotbarTo(target, ids, index + 1);
            });
    }

    // ------------------------------------------------------------------
    // 暂存操作：把物品栏里的成品倒进最终产物暂存处
    // ------------------------------------------------------------------

    /**
     * 需求里的「对最终产物暂存处的修改」：把物品栏里的成品放进暂存盒。
     *
     * <p>盒子满了就走更换操作，换完接着放，直到物品栏里没有还要放的成品。
     */
    private void pushStore() {
        pushSync("把物品栏里的成品倒进最终产物暂存处", () -> {
            if (!inventoryHasWanted()) {
                ChatUtils.debug("物品栏里没有要倒进暂存处的成品了");
                return;
            }
            pushPutToStaging();
        });
    }

    private void pushPutToStaging() {
        StationPos spot = placement(STAGING_ID);
        if (spot == null) {
            pushSync("找不到暂存处", () -> fail("找不到潜影盒摆放处 #" + STAGING_ID));
            return;
        }

        pushChild("往最终产物暂存处放成品（" + spot.coordString() + "）",
            () -> new ContainerPutAction(spot.pos(), ContainerPutAction.Mode.WANTED, true, finalWanted),
            action -> {
                ContainerPutAction put = (ContainerPutAction) action;
                if (put.detail() != null) {
                    state.setPlacement(STAGING_ID, put.detail().all());
                }

                if (put.allCleared()) {
                    ChatUtils.debug("物品栏里的成品都倒进暂存盒了");
                    return;
                }

                ChatUtils.info("暂存盒装不下了，开始更换操作");
                pushReplaceStagingBox();
                pushPutToStaging();
            });
    }

    // ------------------------------------------------------------------
    // 第 2 步：物品形态的成品
    // ------------------------------------------------------------------

    private void pushStep2() {
        pushSync("阶段3 第 2 步：从 item_storage 取物品形态的成品", () -> {
            step2Index = 0;
            ChatUtils.info("第 2 步：遍历 item_storage，把物品形态的成品取出来（潜影盒整个无视）");
            pushStep2Next();
        });
    }

    private void pushStep2Next() {
        pushSync("找下一个有物品形态成品的 item_storage", () -> {
            if (need.isEmpty()) {
                ChatUtils.debug("要的东西都拿齐了，第 2 步提前结束");
                pushStep3();
                return;
            }

            while (step2Index < storageIds.size() && !storageHasLooseWanted(storageIds.get(step2Index))) {
                ChatUtils.debug("item_storage #" + storageIds.get(step2Index) + " 里没有需要的物品形态成品，跳过");
                step2Index++;
            }
            if (step2Index >= storageIds.size()) {
                ChatUtils.info("item_storage 里没有更多物品形态的成品了，第 2 步结束");
                pushStep3();
                return;
            }

            int id = storageIds.get(step2Index);
            StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
            if (pos == null) {
                step2Index++;
                pushStep2Next();
                return;
            }

            pushChild("从 item_storage #" + id + " 取物品形态的成品（only_item）",
                () -> new ContainerGetAction(pos.pos(), need, true, ContainerGetAction.Mode.ONLY_ITEM),
                action -> {
                    ContainerGetAction get = (ContainerGetAction) action;
                    if (get.detail() != null) state.setStorage(id, get.detail(), get.freeSlots());

                    if (get.allCleared()) {
                        ChatUtils.info("item_storage #" + id + " 的物品形态成品取完了，还差 " + need.describe());
                        step2Index++;
                        pushStep2Next();
                    } else {
                        ChatUtils.info("物品栏满了，先把东西倒进暂存处，再回到 item_storage #" + id + " 接着取");
                        pushStore();
                        pushStep2Next();
                    }
                });
        });
    }

    // ------------------------------------------------------------------
    // 第 3 步：装在潜影盒里的成品
    // ------------------------------------------------------------------

    private void pushStep3() {
        pushSync("阶段3 第 3 步：把装在潜影盒里的成品也拿出来", () -> {
            step3Index = 0;
            nextPlacementId = 2;
            ChatUtils.info("第 3 步：遍历 item_storage，把装着所需物品的潜影盒整个取出来、摆好、再取里面的东西");
            pushStep3Next();
        });
    }

    private void pushStep3Next() {
        pushSync("找下一个装着所需物品的潜影盒", () -> {
            if (need.isEmpty()) {
                ChatUtils.debug("要的东西都拿齐了，第 3 步提前结束");
                pushFinish();
                return;
            }

            while (step3Index < storageIds.size() && !storageHasBoxWithWanted(storageIds.get(step3Index))) {
                ChatUtils.debug("item_storage #" + storageIds.get(step3Index) + " 里没有装着所需物品的潜影盒，跳过");
                step3Index++;
            }
            if (step3Index >= storageIds.size()) {
                ChatUtils.info("item_storage 里没有更多装着所需物品的潜影盒了，第 3 步结束");
                pushFinish();
                return;
            }

            int id = storageIds.get(step3Index);
            StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
            if (pos == null) {
                step3Index++;
                pushStep3Next();
                return;
            }

            int placementId = nextPlacementId;
            pushChild("从 item_storage #" + id + " 取一个装着所需物品的潜影盒（only_one_shulker）",
                () -> {
                    StationState.Storage storage = state.storage(id);
                    boxesBeforeTake = storage == null ? List.of() : new ArrayList<>(storage.shulkerBoxes());
                    return new ContainerGetAction(pos.pos(), need, true, ContainerGetAction.Mode.ONLY_ONE_SHULKER);
                },
                action -> {
                    Map<String, Integer> contents = Map.of();
                    if (action instanceof ContainerGetAction get && get.detail() != null) {
                        contents = findTakenBox(boxesBeforeTake, get.detail().shulkerBoxes());
                        state.setStorage(id, get.detail(), get.freeSlots());
                    }

                    takenBoxes++;
                    ChatUtils.info("盒子已经在快捷栏第三格了（摆放处准备用 #" + placementId
                        + "，内容 " + (contents.isEmpty() ? "空" : contents) + "）");

                    pushPlaceHeldBox(placementId, true, contents, "装东西的盒子");
                    pushGetFromPlaced(placementId);
                    // 这个容器可能还有别的装着所需物品的盒子，回到同一个 id 再看一遍
                    pushStep3Next();
                });
        });
    }

    /** 对着摆放处的盒子取需要的东西；没拿完就先倒进暂存处，再拿一遍，直到 all_cleared。 */
    private void pushGetFromPlaced(int placementId) {
        StationPos spot = placement(placementId);
        if (spot == null) {
            pushSync("找不到摆放处 #" + placementId, () -> fail("找不到潜影盒摆放处 #" + placementId));
            return;
        }

        pushChild("从摆放处 #" + placementId + " 的盒子里取需要的东西",
            () -> new ContainerGetAction(spot.pos(), need, true),
            action -> {
                ContainerGetAction get = (ContainerGetAction) action;
                if (get.detail() != null) {
                    state.setPlacement(placementId, get.detail().all());
                }

                if (get.allCleared()) {
                    ChatUtils.info("摆放处 #" + placementId + " 的盒子取完了，还差 " + need.describe());
                    return;
                }
                ChatUtils.info("物品栏满了，先把东西倒进暂存处，再回来接着取摆放处 #" + placementId + " 的盒子");
                pushStore();
                pushGetFromPlaced(placementId);
            });
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private void pushFinish() {
        pushSync("收尾：把物品栏里剩下的成品也倒进暂存处", () -> pushStore());
        pushSync("检查物品栏里有没有漏下的成品", () -> {
            if (inventoryHasWanted()) {
                fail("物品栏里还有成品没倒进暂存处（倒的时候暂存处一直装不下？）");
            }
        });
    }

    // ------------------------------------------------------------------
    // 步骤队列小工具
    // ------------------------------------------------------------------

    private void pushSync(String label, Runnable body) {
        pushStep(new SyncStep(label, body));
    }

    private void pushChild(String label, Supplier<Action> factory) {
        pushChild(label, factory, null);
    }

    private void pushChild(String label, Supplier<Action> factory, @Nullable Consumer<Action> after) {
        pushStep(new ChildStep(label, factory, after));
    }

    private void pushStep(Step step) {
        if (executing) {
            pending.add(step);
        } else {
            steps.addLast(step);
        }
    }

    /**
     * 跑一步（同步步骤的内容，或者子动作跑完的收尾），并把它里面新加的步骤**按顺序插到队首**。
     */
    private void runWithInsertion(Runnable body) {
        executing = true;
        pending.clear();
        try {
            body.run();
        } finally {
            executing = false;
            for (int i = pending.size() - 1; i >= 0; i--) {
                steps.addFirst(pending.get(i));
            }
            pending.clear();
        }
    }

    /**
     * 走到某个格子（跟 {@code /pc move to_position} 一样的规则）：
     * 是路径点就直接走；在某条边中间就先在那边建个路径点；都不是就报错。
     */
    private void pushMoveTo(BlockPos target, String label) {
        pushSync(label, () -> {
            WaypointGraph graph = WaypointManager.get().graph();
            String dimension = DimensionUtils.current();

            Waypoint to = graph.at(dimension, target);
            if (to == null) {
                WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, target);
                if (edge == null) {
                    fail("目标 " + target.toShortString() + " 既不是路径点，也不在任何边上，走不过去");
                    return;
                }
                to = WaypointManager.get().createWaypoint(dimension, target);
                ChatUtils.debug("在边 #" + edge.id() + " 上新建了路径点 #" + to.id());
            }

            Waypoint from = WaypointManager.get().playerStartWaypoint();
            if (from == null) {
                fail("你现在既不在路径点上，也不在任何边上，算不了路");
                return;
            }
            if (from.id() == to.id()) {
                ChatUtils.debug("已经在 " + target.toShortString() + " 了");
                return;
            }

            List<Waypoint> path = graph.shortestPath(from.id(), to.id());
            if (path == null || path.size() < 2) {
                fail("从当前位置走不到 " + target.toShortString());
                return;
            }
            pushChild(label, () -> new PathMoveAction(path));
        });
    }

    // ------------------------------------------------------------------
    // 飞行持有（整段「挖 → 落 → 捡 → 飞上来 → 走回去」中途不关飞行）
    // ------------------------------------------------------------------

    private boolean flightHeld;

    private void beginFlightHold() {
        if (flightHeld) return;
        FlightVelocity.begin();
        flightHeld = true;
    }

    private void endFlightHold() {
        if (!flightHeld) return;
        FlightVelocity.end();
        flightHeld = false;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private void advancePlacementId() {
        int max = placementIds.isEmpty() ? 1 : placementIds.get(placementIds.size() - 1);
        int next = nextPlacementId + 1;
        if (next < 2 || next > max) next = 2;
        nextPlacementId = next;
        state.setNextShulkerBoxPlacementId(nextPlacementId);
        ChatUtils.debug("下一个潜影盒摆放处 id = " + nextPlacementId);
    }

    @Nullable
    private StationPos placement(int id) {
        for (StationPos pos : StationManager.get().list(StationPart.SHULKER_BOX_PLACEMENT)) {
            if (pos.id() == id) return pos;
        }
        return null;
    }

    @Nullable
    private StationPos stationPos(StationPart part, int id) {
        for (StationPos pos : StationManager.get().list(part)) {
            if (pos.id() == id) return pos;
        }
        return null;
    }

    @Nullable
    private StationPos standPoint() {
        return StationManager.get().single(StationPart.STAND_POINT);
    }

    private StationPos requireStandPoint() {
        StationPos stand = standPoint();
        if (stand == null) throw new IllegalStateException("没有设置站立点");
        return stand;
    }

    private static String targetName(PutTarget target) {
        return target == PutTarget.FINAL ? "最终产物地（item_final）" : "物资存储地（item_storage）";
    }

    /** 这个 item_storage 里有没有「item_list 还要的」散装物品。 */
    private boolean storageHasLooseWanted(int id) {
        StationState.Storage storage = state.storage(id);
        if (storage == null) return false;
        for (String key : storage.items().keySet()) {
            Item item = StockMaterials.parseItem(key);
            if (item != null && need.wants(item)) return true;
        }
        return false;
    }

    /** 这个 item_storage 里有没有「装着 item_list 还要的东西」的潜影盒。 */
    private boolean storageHasBoxWithWanted(int id) {
        StationState.Storage storage = state.storage(id);
        if (storage == null) return false;
        for (Map<String, Integer> box : storage.shulkerBoxes()) {
            for (String key : box.keySet()) {
                Item item = StockMaterials.parseItem(key);
                if (item != null && need.wants(item)) return true;
            }
        }
        return false;
    }

    /** 拿之前 / 拿之后比一比，算出来被拿走的是哪个盒子、里面装着什么。 */
    private static Map<String, Integer> findTakenBox(List<Map<String, Integer>> before,
                                                     List<Map<String, Integer>> after) {
        List<Map<String, Integer>> left = new ArrayList<>(before);
        for (Map<String, Integer> box : after) left.remove(box);
        return left.isEmpty() ? Map.of() : new TreeMap<>(left.get(0));
    }

    /** 物品栏 27 格里有没有成品（也就是 final_pack 里写过的东西）。 */
    private boolean inventoryHasWanted() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && finalWanted.wants(stack.getItem())) return true;
        }
        return false;
    }

    /** 挖下来的潜影盒有没有已经落在快捷栏第二格里。 */
    private boolean hasPickupBox() {
        return ShulkerUtils.isShulkerBox(hotbarStack(PICKUP_HOTBAR));
    }

    /** 整个物品栏里有几叠潜影盒（只用来打日志）。 */
    private int countShulkerBoxes() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (ShulkerUtils.isShulkerBox(player.getInventory().getStack(i))) count++;
        }
        return count;
    }

    private ItemStack hotbarStack(int index) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getInventory().getStack(index);
    }

    @Nullable
    private BlockPos currentBlock() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player == null ? null : BlockPos.ofFloored(player.getEntityPos());
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
