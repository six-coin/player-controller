package org.six_coin.playerController.client.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.recipe.CraftPlan;
import org.six_coin.playerController.client.recipe.MaxCraft;
import org.six_coin.playerController.client.recipe.RecipePlanner;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.station.StationState;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.stock.StockMaterials;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.ItemRules;
import org.six_coin.playerController.client.util.PlayerUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /pc stock task <名字> start} 的「第二部分 阶段3：合成」。
 *
 * <p>照着 {@code final_steps.json} 一步一步来（它是 {@code final_process.json} 摊平出来的，
 * 顺序是「先零件后组装」）：
 * <ol>
 *   <li>每个步骤先按 {@code sub_materials} 把材料**从 item_storage 的潜影盒里掏出来**
 *       （见 {@link #pushTakeFromBoxes}）：仓库里散装的就够了就直接跳过；不够就先把摆放处
 *       已经摆好的盒子里的掏出来，再不够就去 item_storage 里的盒子掏（only_one_shulker →
 *       放到下一个摆放处 → 对着它 get → 东西存回 item_storage）；</li>
 *   <li>用 {@link MaxCraft} 问「单背包最大合成」能一次做多少，把这一步的 count 切成几段
 *       （比如 3638 = 384×9 + 182）；</li>
 *   <li>每一段走一遍「取货 → 合成 → 放入」：从 item_storage 把这段材料取到物品栏 →
 *       {@code /pc recipe} 合成 → 把快捷栏 4~9 格的成品放进目标容器
 *       （步骤的 {@code final=true} 放最终产物暂存盒，否则放 item_storage）。</li>
 * </ol>
 *
 * <p>全部做完以后收尾：最终产物暂存盒（摆放处 #1）里非空就挖出来放 item_final、空的就放
 * item_storage；再把其余摆放处上的盒子都挖出来放进 item_storage。
 *
 * <p>每次开箱（潜影盒 / item_storage）都会顺手更新 station_data。
 *
 * <p>挖盒子那一整套（挖 → 自然下落捡起来 → {@code move y 1} → 回站立点 → 放进容器）跟第一部分
 * 阶段3 是一样的，飞行整段持有，中途不关。
 */
public class StockPart2Stage3Action extends Action {

    /** 最终产物暂存处固定用摆放处 id 1。 */
    public static final int STAGING_ID = 1;

    /** 挖盒子用快捷栏第一格的钻石镐。 */
    private static final int PICKAXE_HOTBAR = StockStartAction.PICKAXE_HOTBAR;

    /** 捡起来的潜影盒落在快捷栏第二格。 */
    private static final int PICKUP_HOTBAR = 1;

    /** 临时放潜影盒用快捷栏第三格。 */
    private static final int BOX_HOTBAR = ContainerGetAction.HOTBAR_BOX_INDEX;

    /** 合成成品会在快捷栏这几格（4~9）。 */
    private static final int RESULT_FROM = RecipePlanner.RESULT_HOTBAR_FROM;
    private static final int RESULT_TO = RecipePlanner.RESULT_HOTBAR_TO;

    private static final int PICKUP_WAIT_TICKS = 20 * 15;

    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60;

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /** final_steps.json 里的一步。 */
    private record StepData(String itemId,
                            int count,
                            boolean isFinal,
                            String recipeType,
                            Map<String, Integer> materials) {
    }

    /** 盒子挖出来以后放哪儿。 */
    private enum PutTarget {
        /** 最终产物地（暂存盒）。 */
        FINAL,
        /** 物资存储地。 */
        STORAGE
    }

    private final String task;
    /** 第二阶段2 交过来的 station_data（必须有：它不落盘）。 */
    @Nullable
    private final StationState handedState;

    private StationState state;
    private final List<StepData> steps = new ArrayList<>();
    private final List<Integer> placementIds = new ArrayList<>();
    private String unreachableJson = "{}";

    private int nextPlacementId = 2;
    private int stepIndex;
    private int craftedSegments;
    private int craftedCount;

    // ------------------------------------------------------------------
    // 小的「步骤队列」（跟第一部分阶段3 一个套路）
    // ------------------------------------------------------------------

    private interface Step {
        String label();
    }

    private record SyncStep(String label, Runnable body) implements Step {
    }

    private record ChildStep(String label, Supplier<Action> factory, @Nullable Consumer<Action> after)
        implements Step {
    }

    private final Deque<Step> queue = new ArrayDeque<>();
    private final List<Step> pending = new ArrayList<>();
    private boolean executing;
    private Action child;
    private Consumer<Action> afterChild;

    /** 飞行持有（挖盒子那一段「挖 → 落 → 捡 → 飞上来 → 走回去」中途不关）。 */
    private boolean flightHeld;

    private int totalTicks;
    private int pickupHotbar = PICKUP_HOTBAR;
    private int boxesMined;
    /** 刚从 item_storage 拿出来的那个盒子里装着什么（拿去记账）。 */
    private Map<String, Integer> takenBoxContents = Map.of();
    /** 拿盒子之前那个 item_storage 里有哪些盒子。 */
    private List<Map<String, Integer>> boxesBeforeTake = List.of();

    public StockPart2Stage3Action(String task) {
        this(task, null);
    }

    public StockPart2Stage3Action(String task, @Nullable StationState handedState) {
        this.task = task;
        this.handedState = handedState;
    }

    /** 这一轮维护的 station_data（收尾 / 单独跑的时候可以写出去）。 */
    public StationState state() {
        return state;
    }

    @Override
    public String name() {
        return "备货 " + task + " 第二阶段3（合成）";
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
            if (handedState == null) {
                fail("station_data 不落盘（只在一次任务的内存里传），第二部分只能从 "
                    + "/pc stock task <名字> start 一路跑下来");
                return;
            }
            state = handedState;
            loadSteps();
            loadUnreachable();
        } catch (Exception e) {
            fail("准备第二阶段3 失败：" + e.getMessage());
            return;
        }

        for (StationPos pos : StationManager.get().list(StationPart.SHULKER_BOX_PLACEMENT)) {
            placementIds.add(pos.id());
        }
        placementIds.sort(Integer::compareTo);
        // 接着第一部分阶段3 用到的那个摆放处编号继续（station_data 里带过来的）
        nextPlacementId = state.nextShulkerBoxPlacementId();
        ChatUtils.debug("接着用摆放处 #" + nextPlacementId + " 往后排");

        if (placementIds.size() < 2) {
            fail("潜影盒摆放处少于 2 个（暂存处用 id 1，中间还要用别的 id）");
            return;
        }
        if (steps.isEmpty()) {
            ChatUtils.info("final_steps.json 里没有要合成的步骤，直接收尾");
        }

        // 挖盒子要用钻石镐；捡起来的盒子会落在快捷栏第二格，所以它得是空的
        pushSync("检查快捷栏", () -> {
            ItemStack tool = hotbarStack(PICKAXE_HOTBAR);
            if (!tool.isOf(net.minecraft.item.Items.DIAMOND_PICKAXE)) {
                ChatUtils.error("快捷栏第一格里不是钻石镐（是 " + describe(tool) + "），挖盒子会很慢");
            }
            if (!hotbarStack(PICKUP_HOTBAR).isEmpty()) {
                fail("快捷栏第二格被 " + describe(hotbarStack(PICKUP_HOTBAR))
                    + " 占了；挖下来的潜影盒会填到那一格，先腾出来");
            }
        });

        // 人得站在站立点上（工作台/切石机、item_storage 都在它的触及范围内）
        StationPos stand = standPoint();
        if (stand == null) {
            fail("没有设置站立点");
            return;
        }
        if (!stand.pos().equals(currentBlock())) {
            pushMoveTo(stand.pos(), "先走回站立点 " + stand.coordString());
        }

        pushEnsureStaging();
        pushStepLoop();

        ChatUtils.info("第二阶段3 开始：final_steps 有 " + steps.size() + " 步；要合成的东西从 item_storage 里掏");
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

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
            if (child != null) return;
        }

        while (!isFinished()) {
            Step step = queue.pollFirst();
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
            ChatUtils.info("第二阶段3（合成）完成：合了 " + craftedSegments + " 段 / " + craftedCount + " 个");
            ChatUtils.info("拿不到的最终产物（unreachable.json）：" + unreachableJson);
            ChatUtils.rawCopyable(unreachableJson);
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
    // 读文件
    // ------------------------------------------------------------------

    private void loadSteps() throws Exception {
        Path file = StockManager.get().currentFinalStepsFile(task);
        if (!Files.exists(file)) {
            throw new IllegalStateException("找不到 " + file + "（第二部分阶段1 生成的？）");
        }

        JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!root.isJsonArray()) throw new IllegalStateException(file.getFileName() + " 最外层应该是数组");

        for (JsonElement element : root.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();

            if (!object.has("item") || !object.has("count")) continue;
            Item item = StockMaterials.parseItem(object.get("item").getAsString());
            if (item == null) continue;
            String itemId = Registries.ITEM.getId(item).toString();

            int count = object.get("count").getAsInt();
            if (count <= 0) continue;

            boolean isFinal = object.has("final") && object.get("final").getAsBoolean();
            String recipeType = object.has("recipe_type")
                ? object.get("recipe_type").getAsString() : "crafting_shaped";

            Map<String, Integer> materials = new LinkedHashMap<>();
            JsonElement sub = object.get("sub_materials");
            if (sub != null && sub.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : sub.getAsJsonObject().entrySet()) {
                    Item material = StockMaterials.parseItem(entry.getKey());
                    if (material == null) continue;
                    int amount = entry.getValue().getAsInt();
                    if (amount <= 0) continue;
                    materials.merge(Registries.ITEM.getId(material).toString(), amount, Integer::sum);
                }
            }

            steps.add(new StepData(itemId, count, isFinal, recipeType, materials));
        }
        ChatUtils.debug("final_steps：读到 " + steps.size() + " 步");
    }

    private void loadUnreachable() {
        Path file = StockManager.get().currentUnreachableFile(task);
        if (!Files.exists(file)) {
            ChatUtils.debug("没有 " + file + "，unreachable 按空的算");
            return;
        }
        try {
            unreachableJson = Files.readString(file, StandardCharsets.UTF_8).replaceAll("\\s", "");
        } catch (Exception e) {
            ChatUtils.error("读 " + file + " 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    /** 保证摆放处 #1 上有一个潜影盒（暂存盒）。 */
    private void pushEnsureStaging() {
        pushSync("准备最终产物暂存盒（摆放处 #" + STAGING_ID + "）", () -> {
            StationPos spot = placement(STAGING_ID);
            if (spot == null) {
                fail("找不到潜影盒摆放处 #" + STAGING_ID);
                return;
            }

            BlockState state = worldState(spot.pos());
            if (state.getBlock() instanceof ShulkerBoxBlock) {
                if (!this.state.hasPlacement(STAGING_ID)) {
                    ChatUtils.debug("摆放处 #1 上已经有盒子了（内容不明，先按空的算）");
                    this.state.setPlacement(STAGING_ID, Map.of());
                }
                return;
            }
            if (!state.isAir()) {
                fail("摆放处 #1 " + spot.coordString() + " 上有别的方块（"
                    + Registries.BLOCK.getId(state.getBlock()) + "），先自己清掉");
                return;
            }

            ChatUtils.info("暂存处是空的，先从空潜影盒提供处取一个空盒放上去");
            pushTakeProviderBox();
            pushPlaceHeldBox(STAGING_ID, Map.of());
        });
    }

    /** 遍历 final_steps 的每一步。 */
    private void pushStepLoop() {
        pushSync("取下一步", () -> {
            if (stepIndex >= steps.size()) {
                pushCleanup();
                return;
            }
            StepData step = steps.get(stepIndex++);
            ChatUtils.info("第 " + stepIndex + "/" + steps.size() + " 步：" + step.itemId()
                + " x" + step.count() + (step.isFinal() ? "（最终产物）" : "")
                + "，材料 " + step.materials());
            pushMaterialLoop(step, new ArrayList<>(step.materials().entrySet()), 0);
        });
    }

    /** 逐个材料把「潜影盒里的」掏到 item_storage 的散装里。 */
    private void pushMaterialLoop(StepData step, List<Map.Entry<String, Integer>> materials, int index) {
        pushSync("处理材料 " + (index + 1) + "/" + materials.size(), () -> {
            if (index >= materials.size()) {
                pushPlanSegments(step);
                return;
            }
            Map.Entry<String, Integer> entry = materials.get(index);
            pushTakeFromBoxes(entry.getKey(), entry.getValue(),
                () -> pushMaterialLoop(step, materials, index + 1));
        });
    }

    /**
     * 【从 item_storage 的潜影盒中取出物品】：保证 item_storage 里**散装**的
     * {@code itemId} 至少有 {@code need} 个。
     */
    private void pushTakeFromBoxes(String itemId, int need, Runnable next) {
        pushSync("看看 " + itemId + " 够不够 " + need + " 个", () -> {
            int loose = looseCount(itemId);
            if (loose >= need) {
                ChatUtils.debug("item_storage 散装的 " + itemId + " 有 " + loose + " 个，够了");
                next.run();
                return;
            }

            int left = need - loose;
            ChatUtils.debug("item_storage 散装的 " + itemId + " 只有 " + loose + " 个，还差 "
                + left + "，去盒子里掏");
            pushScanPlacedBoxes(itemId, left, 0, next);
        });
    }

    /** 先看已经摆在摆放处上的盒子（除了 id=1 的暂存盒）。 */
    private void pushScanPlacedBoxes(String itemId, int need, int index, Runnable next) {
        pushSync("看摆放处上的盒子（从 #" + index + " 开始）", () -> {
            if (index >= placementIds.size() || need <= 0) {
                pushDigStorageBoxes(itemId, need, 0, next);
                return;
            }

            int placementId = placementIds.get(index);
            if (placementId == STAGING_ID) {
                pushScanPlacedBoxes(itemId, need, index + 1, next);
                return;
            }

            StationPos spot = placement(placementId);
            if (spot == null || !(worldState(spot.pos()).getBlock() instanceof ShulkerBoxBlock)) {
                pushScanPlacedBoxes(itemId, need, index + 1, next);
                return;
            }

            Map<String, Integer> contents = state.placement(placementId);
            if (!containsItem(contents, itemId)) {
                pushScanPlacedBoxes(itemId, need, index + 1, next);
                return;
            }

            ItemList want = itemListOf(itemId, need);
            ChatUtils.info("摆放处 #" + placementId + " 的盒子里有 " + itemId + "，从它取 " + need + " 个");
            pushChild("从摆放处 #" + placementId + " 的盒子里取 " + itemId,
                () -> new ContainerGetAction(spot.pos(), want, true),
                action -> {
                    ContainerGetAction get = (ContainerGetAction) action;
                    if (get.detail() != null) {
                        state.setPlacement(placementId, get.detail().all());
                    }
                    int remaining = remainingOf(want, itemId);
                    ChatUtils.debug("取完还剩 " + remaining + " 个要掏");
                    pushStoreInventoryToStorage(() -> {
                        if (remaining <= 0) {
                            next.run();
                        } else {
                            pushScanPlacedBoxes(itemId, remaining, index + 1, next);
                        }
                    });
                });
        });
    }

    /** 再挖 item_storage 里的潜影盒：only_one_shulker → 摆到下一个摆放处 → 对着它取。 */
    private void pushDigStorageBoxes(String itemId, int need, int storageIndex, Runnable next) {
        pushSync("去 item_storage 的盒子里掏 " + itemId + "（还差 " + need + "）", () -> {
            if (need <= 0) {
                next.run();
                return;
            }

            List<Integer> ids = state.storageIds();
            if (storageIndex >= ids.size()) {
                ChatUtils.error("item_storage 里所有盒子都翻遍了，" + itemId + " 还差 " + need
                    + "（前面的阶段没保证够？）");
                next.run();
                return;
            }

            int storageId = ids.get(storageIndex);
            StationPos containerPos = stationPos(StationPart.ITEM_STORAGE, storageId);
            if (containerPos == null || !storageHasBoxWith(storageId, itemId)) {
                pushDigStorageBoxes(itemId, need, storageIndex + 1, next);
                return;
            }

            int placementId = nextPlacementId;
            ItemList want = itemListOf(itemId, need);
            ChatUtils.info("item_storage #" + storageId + " 里有装着 " + itemId + " 的盒子，"
                + "拿出来放到摆放处 #" + placementId + " 再取");

            pushChild("从 item_storage #" + storageId + " 取一个装着 " + itemId + " 的盒子",
                () -> {
                    StationState.Storage storage = state.storage(storageId);
                    boxesBeforeTake = storage == null
                        ? List.of() : new ArrayList<>(storage.shulkerBoxes());
                    return new ContainerGetAction(containerPos.pos(), want, true,
                        ContainerGetAction.Mode.ONLY_ONE_SHULKER);
                },
                action -> {
                    Map<String, Integer> contents = Map.of();
                    if (action instanceof ContainerGetAction get && get.detail() != null) {
                        contents = findTakenBox(boxesBeforeTake, get.detail().shulkerBoxes());
                        state.setStorage(storageId, get.detail(), get.freeSlots());
                    }
                    takenBoxContents = contents;
                });

            pushPlaceHeldBox(placementId, takenBoxContents);

            StationPos spot = placement(placementId);
            pushSync("对着摆放处 #" + placementId + " 的盒子取 " + itemId, () -> {
                if (spot == null) {
                    fail("找不到摆放处 #" + placementId);
                    return;
                }
                pushChild("从摆放处 #" + placementId + " 的盒子里取 " + itemId,
                    () -> new ContainerGetAction(spot.pos(), want, true),
                    action -> {
                        ContainerGetAction get = (ContainerGetAction) action;
                        if (get.detail() != null) state.setPlacement(placementId, get.detail().all());
                        int remaining = remainingOf(want, itemId);
                        pushStoreInventoryToStorage(() -> {
                            if (remaining <= 0) {
                                next.run();
                            } else {
                                pushDigStorageBoxes(itemId, remaining, storageIndex + 1, next);
                            }
                        });
                    });
            });
        });
    }

    /** 算这一步要分几段，然后逐段做。 */
    private void pushPlanSegments(StepData step) {
        pushSync("规划 " + step.itemId() + " x" + step.count() + " 怎么分几次合成", () -> {
            Item item = StockMaterials.parseItem(step.itemId());
            if (item == null) {
                fail("认不出的物品：" + step.itemId());
                return;
            }

            // 「单背包最大合成」只看物品种类，所以把 sub_materials 的物品拿出来给它
            List<Item> available = new ArrayList<>();
            for (String materialId : step.materials().keySet()) {
                Item material = StockMaterials.parseItem(materialId);
                if (material != null) available.add(material);
            }
            if (available.isEmpty()) {
                fail(step.itemId() + " 这一步没有材料？");
                return;
            }

            boolean stonecutter = step.recipeType().startsWith("stonecutting");
            MaxCraft.Result max = stonecutter
                ? MaxCraft.stonecutter(item, available)
                : MaxCraft.craftingTable(item, available);
            if (max == null) {
                fail("用 " + step.materials().keySet() + " 这些材料" + (stonecutter ? "切" : "合")
                    + "不出 " + step.itemId() + "（配方没解锁？材料对不上？）");
                return;
            }
            ChatUtils.info("单背包最大合成：" + MaxCraft.describe(item, max));

            int perCraft = Math.max(1, max.perCraft());
            List<Integer> segments = new ArrayList<>();
            int left = step.count();
            while (left > 0) {
                int size = Math.min(max.resultCount(), left);
                size = (size / perCraft) * perCraft;   // 必须是单次产量的整数倍
                if (size <= 0) break;
                segments.add(size);
                left -= size;
            }
            if (segments.isEmpty()) {
                fail(step.count() + " 个 " + step.itemId() + " 分不出合法的一段（一次出 "
                    + perCraft + " 个）");
                return;
            }
            if (left > 0) {
                ChatUtils.error("有 " + left + " 个 " + step.itemId() + " 凑不成整次合成，先跳过");
            }
            ChatUtils.info("分成 " + segments.size() + " 次合成：" + segments);

            pushSegmentLoop(step, max, segments, 0);
        });
    }

    /** 逐段合成。 */
    private void pushSegmentLoop(StepData step, MaxCraft.Result max, List<Integer> segments, int index) {
        pushSync("第 " + (index + 1) + "/" + segments.size() + " 段合成", () -> {
            if (index >= segments.size()) {
                pushStepLoop();
                return;
            }
            pushSegment(step, max, segments, index);
        });
    }

    /** 【取货、合成、放入】 */
    private void pushSegment(StepData step, MaxCraft.Result max, List<Integer> segments, int index) {
        int want = segments.get(index);
        int perCraft = Math.max(1, max.perCraft());

        pushSync("准备第 " + (index + 1) + " 段（" + want + " 个 " + step.itemId() + "）", () -> {
            // 这一段要的材料：先按配方算「单次合成要多少」，再乘这一段的合成次数
            int craftTimes = Math.max(1, want / perCraft);
            Map<String, Integer> perCraftNeed = new LinkedHashMap<>();
            for (Map.Entry<Item, Integer> entry : max.materials().entrySet()) {
                String id = Registries.ITEM.getId(entry.getKey()).toString();
                perCraftNeed.put(id, Math.max(1, entry.getValue() / Math.max(1, max.crafts())));
            }

            // 兜一下：仓库散装的 + 手上的够不够，不够就把这一段缩小
            int crafts = craftTimes;
            for (Map.Entry<String, Integer> entry : perCraftNeed.entrySet()) {
                int have = looseCount(entry.getKey()) + inventoryCount(entry.getKey());
                int canCraft = have / entry.getValue();
                if (canCraft < crafts) {
                    ChatUtils.error("材料 " + entry.getKey() + " 只够合 " + canCraft + " 次（这段本来 "
                        + crafts + " 次），这一段缩小");
                    crafts = canCraft;
                }
            }
            if (crafts <= 0) {
                fail("材料不够合 " + step.itemId() + "（要 " + perCraftNeed + "）");
                return;
            }
            int segmentCount = crafts * perCraft;

            Map<String, Integer> actualNeed = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : perCraftNeed.entrySet()) {
                actualNeed.put(entry.getKey(), entry.getValue() * crafts);
            }

            ItemList fetch = itemList(actualNeed);
            pushFetchFromStorage(fetch, actualNeed, () -> {
                pushCraft(step, segmentCount, () -> pushPutResult(step, segmentCount, () -> {
                    craftedSegments++;
                    craftedCount += segmentCount;
                    pushSegmentLoop(step, max, segments, index + 1);
                }));
            });
        });
    }

    /** 从 item_storage 把材料取到物品栏（只拿物品形态的）。 */
    private void pushFetchFromStorage(ItemList fetch, Map<String, Integer> need, Runnable next) {
        pushSync("从 item_storage 取材料 " + need, () -> {
            fetchFromStorage(fetch, state.storageIds(), 0, next);
        });
    }

    private void fetchFromStorage(ItemList fetch, List<Integer> ids, int index, Runnable next) {
        if (fetch.isEmpty()) {
            next.run();
            return;
        }
        if (index >= ids.size()) {
            ChatUtils.error("item_storage 里没有更多" + fetch.describe() + "了（前面阶段没保证够？）");
            next.run();
            return;
        }

        int id = ids.get(index);
        if (!storageHasLoose(id, fetch)) {
            fetchFromStorage(fetch, ids, index + 1, next);
            return;
        }

        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            fetchFromStorage(fetch, ids, index + 1, next);
            return;
        }

        pushChild("从 item_storage #" + id + " 取材料（only_item）",
            () -> new ContainerGetAction(pos.pos(), fetch, true, ContainerGetAction.Mode.ONLY_ITEM),
            action -> {
                ContainerGetAction get = (ContainerGetAction) action;
                if (get.detail() != null) state.setStorage(id, get.detail(), get.freeSlots());
                if (!get.allCleared()) {
                    fail("物品栏放不下这些材料了（应该在 27 格以内的）");
                    return;
                }
                ChatUtils.debug("取完还差 " + fetch.describe());
                fetchFromStorage(fetch, ids, index + 1, next);
            });
    }

    /** {@code /pc recipe}：按精确数量合成。 */
    private void pushCraft(StepData step, int count, Runnable next) {
        pushSync("合成 " + step.itemId() + " x" + count, () -> {
            Item item = StockMaterials.parseItem(step.itemId());
            if (item == null) {
                fail("认不出的物品：" + step.itemId());
                return;
            }

            boolean stonecutter = step.recipeType().startsWith("stonecutting");
            RecipePlanner.Result planned = stonecutter
                ? RecipePlanner.planStonecutting(item, count)
                : RecipePlanner.planCrafting(item, count);
            if (!planned.ok()) {
                fail("不能合成 " + step.itemId() + " x" + count + "：" + planned.error());
                return;
            }

            CraftPlan plan = planned.plan();
            ChatUtils.info("合成：" + plan.describe());
            StationPos station = StationManager.get().single(
                stonecutter ? StationPart.STONECUTTER : StationPart.CRAFTING_TABLE);
            if (station == null) {
                fail("工作站没设" + (stonecutter ? "切石机" : "工作台"));
                return;
            }

            pushChild("合成 " + step.itemId() + " x" + count,
                () -> new CraftAction(plan, station.pos()),
                action -> next.run());
        });
    }

    /** 把快捷栏 4~9 格的成品放进目标容器。 */
    private void pushPutResult(StepData step, int count, Runnable next) {
        if (step.isFinal()) {
            pushPutResultToStaging(next);
        } else {
            // 中间产物：成品在快捷栏 4~9 格，放进 item_storage（后面的步骤再拿出来用）
            pushSync("把中间产物放进 item_storage", () -> {
                List<Integer> ids = new ArrayList<>(state.storageTargets());
                if (ids.isEmpty()) {
                    fail("物资存储地都满了，放不下中间产物");
                    return;
                }
                putHotbarToStorage(ids, 0, next);
            });
        }
    }

    private void putHotbarToStorage(List<Integer> ids, int index, Runnable next) {
        if (index >= ids.size()) {
            fail("物资存储地的几个容器都放不下这些成品了");
            return;
        }

        int id = ids.get(index);
        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            putHotbarToStorage(ids, index + 1, next);
            return;
        }

        pushChild("把快捷栏 4~9 格的成品放进 item_storage #" + id,
            () -> new PutHotbarItemAction(pos.pos(), RESULT_FROM, RESULT_TO, true),
            action -> {
                PutHotbarItemAction put = (PutHotbarItemAction) action;
                if (put.detail() != null) state.setStorage(id, put.detail(), put.freeSlots());

                if (put.allCleared()) {
                    next.run();
                } else {
                    ChatUtils.info("item_storage #" + id + " 装不下了，换下一个");
                    putHotbarToStorage(ids, index + 1, next);
                }
            });
    }

    /** 成品进最终产物暂存盒；盒子装不下就换一个（挖出来送 item_final，再放个新的）。 */
    private void pushPutResultToStaging(Runnable next) {
        pushSync("把成品放进最终产物暂存盒", () -> {
            StationPos spot = placement(STAGING_ID);
            if (spot == null) {
                fail("找不到摆放处 #" + STAGING_ID);
                return;
            }

            pushChild("把快捷栏第 " + (RESULT_FROM + 1) + "~" + (RESULT_TO + 1) + " 格的成品放进暂存盒",
                () -> new PutHotbarItemAction(spot.pos(), RESULT_FROM, RESULT_TO, true),
                action -> {
                    PutHotbarItemAction put = (PutHotbarItemAction) action;
                    if (put.detail() != null) state.setPlacement(STAGING_ID, put.detail().all());

                    if (put.allCleared()) {
                        next.run();
                        return;
                    }

                    ChatUtils.info("暂存盒满了，先把它送去 item_final，再换一个空盒");
                    pushMineAndCollect(spot, STAGING_ID, PutTarget.FINAL);
                    pushTakeProviderBox();
                    pushPlaceHeldBox(STAGING_ID, Map.of());
                    pushPutResultToStaging(next);
                });
        });
    }

    /** 把物品栏 27 格里剩下的东西都存进 item_storage（一个满了换下一个）。 */
    private void pushStoreInventoryToStorage(Runnable next) {
        pushSync("把物品栏里的东西存进 item_storage", () -> {
            List<Integer> ids = new ArrayList<>(state.storageTargets());
            if (ids.isEmpty()) {
                fail("物资存储地都满了，放不下了");
                return;
            }
            storeToStorage(ids, 0, next);
        });
    }

    private void storeToStorage(List<Integer> ids, int index, Runnable next) {
        if (index >= ids.size()) {
            fail("物资存储地的几个容器都放不下了");
            return;
        }

        int id = ids.get(index);
        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            storeToStorage(ids, index + 1, next);
            return;
        }

        pushChild("把物品栏里的东西放进 item_storage #" + id,
            () -> new ContainerPutAction(pos.pos(), ContainerPutAction.Mode.EVERYTHING, true),
            action -> {
                ContainerPutAction put = (ContainerPutAction) action;
                if (put.detail() != null) state.setStorage(id, put.detail(), put.freeSlots());

                if (put.allCleared()) {
                    next.run();
                } else {
                    ChatUtils.info("item_storage #" + id + " 满了，换下一个");
                    storeToStorage(ids, index + 1, next);
                }
            });
    }

    // ------------------------------------------------------------------
    // 摆放盒子的通用流程（跟第一部分阶段3 一样）
    // ------------------------------------------------------------------

    /** 从空潜影盒提供处取一个空盒（快捷栏第三格）。 */
    private void pushTakeProviderBox() {
        StationPos provider = StationManager.get().single(StationPart.SHULKER_BOX_PROVIDER);
        if (provider == null) {
            pushSync("没有空潜影盒提供处", () -> fail("没有设置空潜影盒提供处"));
            return;
        }

        pushChild("从空潜影盒提供处取一个空盒",
            () -> new TakeItemAction(provider.pos(), BOX_HOTBAR,
                stack -> ShulkerUtils.isShulkerBox(stack)
                    && ItemRules.customNameOf(stack) == null
                    && ShulkerUtils.contents(stack).isEmpty(),
                "空潜影盒"),
            action -> {
                if (!((TakeItemAction) action).found()) {
                    fail("空潜影盒提供处里没有空潜影盒了");
                }
            });
    }

    /** 把快捷栏第三格的盒子放到某个摆放处（那一格被占就先挖掉送回 item_storage）。 */
    private void pushPlaceHeldBox(int placementId, Map<String, Integer> contents) {
        pushSync("看看摆放处 #" + placementId + " 上有没有盒子", () -> {
            StationPos spot = placement(placementId);
            if (spot == null) {
                fail("找不到摆放处 #" + placementId);
                return;
            }

            BlockState blockState = worldState(spot.pos());
            if (!blockState.isAir() && !(blockState.getBlock() instanceof ShulkerBoxBlock)) {
                fail("摆放处 #" + placementId + " " + spot.coordString() + " 上有别的方块（"
                    + Registries.BLOCK.getId(blockState.getBlock()) + "），先自己清掉");
                return;
            }
            if (!blockState.isAir()) {
                ChatUtils.info("摆放处 #" + placementId + " 上已经有盒子了，先挖掉送回 item_storage");
                pushMineAndCollect(spot, placementId, PutTarget.STORAGE);
            }

            pushChild("把盒子放到摆放处 #" + placementId,
                () -> new PlaceShulkerBoxAction(spot.pos(), BOX_HOTBAR),
                action -> {
                    state.setPlacement(placementId, contents);
                    advancePlacementId();
                });
        });
    }

    /** 挖掉摆放处的盒子 → 落下去捡 → move y 1 → 回站立点 → 放进目标容器。 */
    private void pushMineAndCollect(StationPos spot, int placementId, PutTarget target) {
        pushSync("准备挖 " + spot.coordString() + " 的潜影盒", () -> {
            if (!hotbarStack(PICKUP_HOTBAR).isEmpty()) {
                fail("快捷栏第二格被 " + describe(hotbarStack(PICKUP_HOTBAR)) + " 占了，先腾出来");
                return;
            }
            pickupHotbar = PICKUP_HOTBAR;
        });

        pushChild("挖掉摆放处 #" + placementId + " 的潜影盒",
            () -> {
                beginFlightHold();
                return new MineBlockAction(spot.pos(), PICKAXE_HOTBAR);
            },
            action -> {
                state.clearPlacement(placementId);
                boxesMined++;
            });

        pushMoveTo(spot.pos().up(), "走到潜影盒上面那一格 " + spot.pos().up().toShortString());
        pushChild("等自然下落并捡起潜影盒",
            () -> new WaitAction("捡起潜影盒", this::hasPickupBox, PICKUP_WAIT_TICKS, false),
            action -> {
                if (!((WaitAction) action).met()) {
                    fail("等了 " + PICKUP_WAIT_TICKS + " tick，快捷栏第二格还是没有潜影盒");
                    return;
                }
                pickupHotbar = PICKUP_HOTBAR;
                ChatUtils.info("潜影盒已经捡到快捷栏第二格（" + describe(hotbarStack(PICKUP_HOTBAR)) + "）");
            });

        pushChild("往上飞一格（move y 1）", () -> new MoveAction(Direction.Axis.Y, 1), null);
        pushMoveTo(requireStandPoint().pos(), "走回站立点");
        pushPutHotbarBox(target);
        pushSync("飞行收尾", this::endFlightHold);
    }

    /** 把捡起来的盒子（快捷栏第二格）放进 item_final / item_storage。 */
    private void pushPutHotbarBox(PutTarget target) {
        pushSync("把捡起来的盒子放进" + targetName(target), () -> {
            List<Integer> ids = new ArrayList<>(target == PutTarget.FINAL
                ? state.finalTargets() : state.storageTargets());
            if (ids.isEmpty()) {
                fail(targetName(target) + "都满了，盒子没地方放");
                return;
            }
            putHotbarTo(target, ids, 0);
        });
    }

    private void putHotbarTo(PutTarget target, List<Integer> ids, int index) {
        if (index >= ids.size()) {
            final int total = ids.size();
            pushSync("没地方放盒子了", () ->
                fail(targetName(target) + " 的 " + total + " 个容器都放不下这个盒子了"));
            return;
        }

        int id = ids.get(index);
        StationPos pos = stationPos(target == PutTarget.FINAL ? StationPart.ITEM_FINAL : StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            putHotbarTo(target, ids, index + 1);
            return;
        }

        pushChild("把盒子放进" + targetName(target) + " #" + id,
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

                if (put.allCleared()) return;
                ChatUtils.info(targetName(target) + " #" + id + " 装不下了，换下一个");
                putHotbarTo(target, ids, index + 1);
            });
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private void pushCleanup() {
        pushSync("收尾：处理最终产物暂存盒", () -> {
            List<int[]> mines = new ArrayList<>();   // {placementId, target(0=item_final,1=item_storage)}
            for (int placementId : placementIds) {
                StationPos spot = placement(placementId);
                if (spot == null) continue;
                if (!(worldState(spot.pos()).getBlock() instanceof ShulkerBoxBlock)) continue;

                if (placementId == STAGING_ID) {
                    boolean nonEmpty = !state.placement(STAGING_ID).isEmpty();
                    ChatUtils.info("暂存盒" + (nonEmpty ? "里有成品" : "是空的")
                        + "，挖掉放进" + (nonEmpty ? "item_final" : "item_storage"));
                    mines.add(new int[] {placementId, nonEmpty ? 0 : 1});
                } else {
                    ChatUtils.info("摆放处 #" + placementId + " 上还留着盒子，挖掉放回 item_storage");
                    mines.add(new int[] {placementId, 1});
                }
            }

            if (mines.isEmpty()) {
                ChatUtils.info("摆放处上没有盒子要处理");
                return;
            }
            pushMineList(mines, 0);
        });
    }

    private void pushMineList(List<int[]> mines, int index) {
        pushSync("挖第 " + (index + 1) + "/" + mines.size() + " 个盒子", () -> {
            if (index >= mines.size()) return;

            int placementId = mines.get(index)[0];
            PutTarget target = mines.get(index)[1] == 0 ? PutTarget.FINAL : PutTarget.STORAGE;
            StationPos spot = placement(placementId);
            if (spot == null) {
                pushMineList(mines, index + 1);
                return;
            }

            pushMineAndCollect(spot, placementId, target);
            pushMineList(mines, index + 1);
        });
    }

    // ------------------------------------------------------------------
    // 队列 / 移动 / 飞行的小工具
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
            queue.addLast(step);
        }
    }

    private void runWithInsertion(Runnable body) {
        executing = true;
        pending.clear();
        try {
            body.run();
        } finally {
            executing = false;
            for (int i = pending.size() - 1; i >= 0; i--) {
                queue.addFirst(pending.get(i));
            }
            pending.clear();
        }
    }

    /** 走到某个格子（跟 {@code /pc move to_position} 一样）。 */
    private void pushMoveTo(BlockPos target, String label) {
        pushSync(label, () -> {
            WaypointGraph graph = WaypointManager.get().graph();
            String dimension = org.six_coin.playerController.client.util.DimensionUtils.current();

            Waypoint to = graph.at(dimension, target);
            if (to == null) {
                WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, target);
                if (edge == null) {
                    fail("目标 " + target.toShortString() + " 既不是路径点，也不在任何边上，走不过去");
                    return;
                }
                to = WaypointManager.get().createWaypoint(dimension, target);
            }

            Waypoint from = WaypointManager.get().playerStartWaypoint();
            if (from == null) {
                fail("你现在既不在路径点上，也不在任何边上，算不了路");
                return;
            }
            if (from.id() == to.id()) return;

            List<Waypoint> path = graph.shortestPath(from.id(), to.id());
            if (path == null || path.size() < 2) {
                fail("从当前位置走不到 " + target.toShortString());
                return;
            }
            pushChild(label, () -> new PathMoveAction(path));
        });
    }

    private void beginFlightHold() {
        if (flightHeld) return;
        org.six_coin.playerController.client.feature.FlightVelocity.begin();
        flightHeld = true;
    }

    private void endFlightHold() {
        if (!flightHeld) return;
        org.six_coin.playerController.client.feature.FlightVelocity.end();
        flightHeld = false;
    }

    // ------------------------------------------------------------------
    // station_data / 世界 查询
    // ------------------------------------------------------------------

    private void advancePlacementId() {
        int max = placementIds.isEmpty() ? 1 : placementIds.get(placementIds.size() - 1);
        int next = nextPlacementId + 1;
        if (next < 2 || next > max) next = 2;
        nextPlacementId = next;
        state.setNextShulkerBoxPlacementId(nextPlacementId);
    }

    /** item_storage 里**散装**的某种物品一共有多少。 */
    private int looseCount(String itemId) {
        int total = 0;
        for (int id : state.storageIds()) {
            StationState.Storage storage = state.storage(id);
            if (storage == null) continue;
            total += storage.items().getOrDefault(itemId, 0);
        }
        return total;
    }

    /** 物品栏 27 格里有多少个这种物品。 */
    private int inventoryCount(String itemId) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        int total = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) continue;
            total += stack.getCount();
        }
        return total;
    }

    /** 这个 item_storage 里有没有散装的、item_list 还要的东西。 */
    private boolean storageHasLoose(int storageId, ItemList need) {
        StationState.Storage storage = state.storage(storageId);
        if (storage == null) return false;
        for (String key : storage.items().keySet()) {
            Item item = StockMaterials.parseItem(key);
            if (item != null && need.wants(item)) return true;
        }
        return false;
    }

    /** 这个 item_storage 里有没有装着这种物品的潜影盒。 */
    private boolean storageHasBoxWith(int storageId, String itemId) {
        StationState.Storage storage = state.storage(storageId);
        if (storage == null) return false;
        for (Map<String, Integer> box : storage.shulkerBoxes()) {
            if (containsItem(box, itemId)) return true;
        }
        return false;
    }

    private static boolean containsItem(Map<String, Integer> items, String itemId) {
        Integer count = items.get(itemId);
        return count != null && count > 0;
    }

    /** 拿之前 / 拿之后比一比，算出来被拿走的是哪个盒子、里面装着什么。 */
    private static Map<String, Integer> findTakenBox(List<Map<String, Integer>> before,
                                                     List<Map<String, Integer>> after) {
        List<Map<String, Integer>> left = new ArrayList<>(before);
        for (Map<String, Integer> box : after) left.remove(box);
        return left.isEmpty() ? Map.of() : new LinkedHashMap<>(left.get(0));
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

    private BlockState worldState(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? net.minecraft.block.Blocks.AIR.getDefaultState()
            : mc.world.getBlockState(pos);
    }

    private boolean hasPickupBox() {
        return ShulkerUtils.isShulkerBox(hotbarStack(PICKUP_HOTBAR));
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

    // ------------------------------------------------------------------
    // ItemList 小工具
    // ------------------------------------------------------------------

    private static ItemList itemListOf(String itemId, int count) {
        try {
            return ItemList.parse("{\"" + itemId + "\": " + Math.max(1, count) + "}");
        } catch (ItemList.ParseException e) {
            throw new IllegalStateException("拼不出 item_list：" + itemId + " x" + count, e);
        }
    }

    private static ItemList itemList(Map<String, Integer> items) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (!first) sb.append(',');
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append('}');
        try {
            return ItemList.parse(sb.toString());
        } catch (ItemList.ParseException e) {
            throw new IllegalStateException("拼不出 item_list：" + sb, e);
        }
    }

    /** 这个 item_list 里某种物品还要几个。 */
    private static int remainingOf(ItemList list, String itemId) {
        Item item = StockMaterials.parseItem(itemId);
        return item == null ? 0 : list.remaining(item);
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
