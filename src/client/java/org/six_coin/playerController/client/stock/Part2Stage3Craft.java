package org.six_coin.playerController.client.stock;

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
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.ContainerGetAction;
import org.six_coin.playerController.client.action.ContainerPutAction;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.recipe.CraftAction;
import org.six_coin.playerController.client.recipe.CraftPlan;
import org.six_coin.playerController.client.recipe.MaxCraft;
import org.six_coin.playerController.client.recipe.RecipePlanner;
import org.six_coin.playerController.client.stock.materials.MaterialsRecipes;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationPart;
import org.six_coin.playerController.client.stock.station.StationPos;
import org.six_coin.playerController.client.stock.station.StationState;
import org.six_coin.playerController.client.util.ChatUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 备货第二部分阶段3（合成）：把收集来的材料按需求一步步合成，成品装盒交付。
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
final class Part2Stage3Craft extends StockStep {

    /** 最终产物暂存处固定用摆放处 id 1。 */
    static final int STAGING_ID = StationManager.STAGING_PLACEMENT_ID;

    /** 挖盒子用快捷栏第一格的钻石镐。 */
    private static final int PICKAXE_HOTBAR = StockTask.PICKAXE_HOTBAR;

    /** 捡起来的潜影盒落在快捷栏第二格。 */
    private static final int PICKUP_HOTBAR = 1;

    /** 临时放潜影盒用快捷栏第三格。 */
    private static final int BOX_HOTBAR = ContainerGetAction.HOTBAR_BOX_INDEX;

    /** 合成成品会在快捷栏这几格（4~9）。 */
    private static final int RESULT_FROM = RecipePlanner.RESULT_HOTBAR_FROM;
    private static final int RESULT_TO = RecipePlanner.RESULT_HOTBAR_TO;

    private static final int PICKUP_WAIT_TICKS = 20 * 15;

    /** 往暂存盒放成品时，「物品栏放不下 → 先放进暂存盒腾地方 → 接着取」最多来回几次。 */
    private static final int MAX_STAGING_FLUSHES = 8;

    /** 取材料时物品栏满了，「先倒一批用不上的东西回 item_storage → 接着取」最多来回几次。 */
    private static final int MAX_INVENTORY_DUMPS = 6;

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

    private StationState state;
    private final List<StepData> steps = new ArrayList<>();
    private final List<Integer> placementIds = new ArrayList<>();
    private String unreachableJson = "{}";

    private int nextPlacementId = 2;
    private int stepIndex;
    private int craftedSegments;
    private int craftedCount;

    /**
     * 当前这一步做出来的东西，后面的步骤一共还要用多少个（sub_materials 求和）。
     *
     * <p>大于 0 就走「留着不存」的特判，见 {@link #pushPutResult}。
     */
    private int currentLaterNeed;
    /** 这一步为了「后面马上要用」已经留在物品栏里的数量（分段合成时累计）。 */
    private int keptForLater;

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
    /** 正在跑的那个子动作在步骤队列里的说法（出错时用来交代走哪一步挂的）。 */
    private String runningLabel = "";

    /** 飞行持有（挖盒子那一段「挖 → 落 → 捡 → 飞上来 → 走回去」中途不关）。 */
    private boolean flightHeld;

    private int totalTicks;
    private int pickupHotbar = PICKUP_HOTBAR;
    private int boxesMined;
    /** 刚刚那次合成**实际**做出来多少个（数量不是单次产量整数倍时会比要求的多）。 */
    private int lastCraftActual;
    /** 刚从 item_storage 拿出来的那个盒子里装着什么（拿去记账）。 */
    private Map<String, Integer> takenBoxContents = Map.of();
    /** 拿盒子之前那个 item_storage 里有哪些盒子。 */
    private List<Map<String, Integer>> boxesBeforeTake = List.of();

    Part2Stage3Craft(String task, StockRun run) {
        super(task, run, "第二部分阶段3（合成）");
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
            state = run.state();
            if (state == null) {
                fail("station_data 只在这一轮任务的内存里传，得先有前面取货 / 分盒那几步建起来");
                return;
            }
            loadSteps();
            loadUnreachable();
        } catch (Exception e) {
            fail("准备合成失败：" + e.getMessage());
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
                fail(runningLabel + " → " + done.failureReason());
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
            runningLabel = childStep.label();
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
            currentLaterNeed = laterNeedOf(step, stepIndex);
            keptForLater = 0;
            ChatUtils.info("第 " + stepIndex + "/" + steps.size() + " 步：" + step.itemId()
                + " x" + step.count() + (step.isFinal() ? "（最终产物）" : "")
                + "，材料 " + step.materials()
                + (currentLaterNeed > 0 ? "；后面的步骤还要用 " + currentLaterNeed + " 个 " + step.itemId() : ""));
            pushMaterialLoop(step, new ArrayList<>(step.materials().entrySet()), 0);
        });
    }

    /**
     * 从第 {@code fromIndex} 步开始，后面的步骤一共要用多少个 {@code step.itemId()}。
     *
     * <p>就是把这些步骤的 {@code sub_materials} 里这种物品的数量加起来 —— 大于 0 说明
     * 这一步的产物是「下一步马上要用」的材料，可以留着不存（见 {@link #pushPutResult}）。
     */
    private int laterNeedOf(StepData step, int fromIndex) {
        int total = 0;
        for (int i = fromIndex; i < steps.size(); i++) {
            Integer need = steps.get(i).materials().get(step.itemId());
            if (need != null && need > 0) total += need;
        }
        return total;
    }

    /** 逐个材料把「潜影盒里的」掏到 item_storage 的散装里。 */
    private void pushMaterialLoop(StepData step, List<Map.Entry<String, Integer>> materials, int index) {        pushSync("处理材料 " + (index + 1) + "/" + materials.size(), () -> {
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
     *
     * <p>物品栏（27 格）里已经有的那部分也算数：上一步的中间产物被特意留在物品栏里时就靠这一条 ——
     * 手上的够了就不用再去盒子里掏，后面的取货也会相应少取。
     */
    private void pushTakeFromBoxes(String itemId, int need, Runnable next) {
        pushSync("看看 " + itemId + " 够不够 " + need + " 个", () -> {
            int loose = looseCount(itemId);
            int inHand = inventoryCount(itemId);
            if (loose + inHand >= need) {
                ChatUtils.debug("item_storage 散装的 " + itemId + " 有 " + loose + " 个，物品栏里还有 "
                    + inHand + " 个，加起来够 " + need + " 个，不用去盒子里掏");
                next.run();
                return;
            }

            int left = need - loose - inHand;
            ChatUtils.debug("item_storage 散装的 " + itemId + " 只有 " + loose + " 个，物品栏里 "
                + inHand + " 个，还差 " + left + "，去盒子里掏");
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
                    pushStoreItemToStorage(itemId, () -> {
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
                        pushStoreItemToStorage(itemId, () -> {
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
            MaterialsRecipes.Kind kind = stonecutter
                ? MaterialsRecipes.Kind.STONECUTTER : MaterialsRecipes.Kind.CRAFTING_TABLE;

            MaxCraft.Result max = stonecutter
                ? MaxCraft.stonecutter(item, available)
                : MaxCraft.craftingTable(item, available);
            if (max == null) {
                // 配方挑不出来（没定偏好 / 标签没定）和「材料对不上」要分开说，不然没法照做
                String problem = RecipePlanner.decisionProblem(item, kind);
                if (problem != null) {
                    fail(problem);
                    return;
                }
                List<String> missing = new ArrayList<>();
                for (Item needed : RecipePlanner.recipeMaterials(item, kind)) {
                    if (!available.contains(needed)) {
                        missing.add(Registries.ITEM.getId(needed).toString());
                    }
                }
                fail("配方要的材料和这一步的材料对不上：缺 "
                    + (missing.isEmpty() ? "（配方没定？）" : String.join("、", missing))
                    + "；这一步只有 " + step.materials().keySet()
                    + "（材料清单是旧的？重新 generate_materials_list 再跑一次）");
                return;
            }
            ChatUtils.info("单背包最大合成：" + MaxCraft.describe(item, max));

            // 数量不做任何取整：要 53 个就切 53 个一段，最后一段不是单次产量的整数倍也没关系
            //（合成的时候会向上取整，多出来的由 final=true 的步骤放回 item_storage）
            List<Integer> segments = new ArrayList<>();
            int left = step.count();
            while (left > 0) {
                int size = Math.min(max.resultCount(), left);
                if (size <= 0) break;
                segments.add(size);
                left -= size;
            }
            if (segments.isEmpty()) {
                fail(step.count() + " 个 " + step.itemId() + " 分不出合法的一段");
                return;
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
            // （合成次数是**向上取整**的，因为数量可能不是单次产量的整数倍）
            int craftTimes = (want + perCraft - 1) / perCraft;
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
            // 缩小的那一段就按整数倍来（不想再产生多余的产物）
            int segmentWant = crafts == craftTimes ? want : crafts * perCraft;

            Map<String, Integer> actualNeed = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : perCraftNeed.entrySet()) {
                actualNeed.put(entry.getKey(), entry.getValue() * crafts);
            }

            // 27 格物品栏里已经有的就不用再去 item_storage 取了 ——
            // 上一步特意留下的中间产物走的就是这条，省掉一次「去仓库收集」。
            Map<String, Integer> toFetch = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : actualNeed.entrySet()) {
                int have = inventoryCount(entry.getKey());
                int left = entry.getValue() - Math.min(have, entry.getValue());
                if (left > 0) toFetch.put(entry.getKey(), left);
            }

            ItemList fetch = toFetch.isEmpty() ? ItemList.empty() : itemList(toFetch);
            pushFetchFromStorage(fetch, toFetch, () -> {
                pushCraft(step, segmentWant, () -> pushPutResult(step, segmentWant, lastCraftActual, () -> {
                    craftedSegments++;
                    craftedCount += segmentWant;
                    pushSegmentLoop(step, max, segments, index + 1);
                }));
            });
        });
    }

    /** 从 item_storage 把材料取到物品栏（只拿物品形态的）。 */
    private void pushFetchFromStorage(ItemList fetch, Map<String, Integer> need, Runnable next) {
        pushSync("从 item_storage 取材料 " + need, () -> {
            fetchFromStorage(fetch, need, state.storageIds(), 0, 0, next);
        });
    }

    private void fetchFromStorage(ItemList fetch, Map<String, Integer> need,
                                  List<Integer> ids, int index, int dumps, Runnable next) {
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
            fetchFromStorage(fetch, need, ids, index + 1, dumps, next);
            return;
        }

        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            fetchFromStorage(fetch, need, ids, index + 1, dumps, next);
            return;
        }

        pushChild("从 item_storage #" + id + " 取材料（only_item）",
            () -> new ContainerGetAction(pos.pos(), fetch, true, ContainerGetAction.Mode.ONLY_ITEM),
            action -> {
                ContainerGetAction get = (ContainerGetAction) action;
                if (get.detail() != null) state.setStorage(id, get.detail(), get.freeSlots());

                if (get.allCleared()) {
                    ChatUtils.debug("取完还差 " + fetch.describe());
                    fetchFromStorage(fetch, need, ids, index + 1, dumps, next);
                    return;
                }

                // 物品栏 27 格塞满了：先把手边「这一步用不上的东西」倒回 item_storage 腾地方，再接着取。
                // 这一步要用的材料（need）留着，不然又得重新去仓库拿一遍。
                if (!get.stoppedForInventory() || dumps >= MAX_INVENTORY_DUMPS) {
                    ChatUtils.error("物品栏放不下这些材料了（还差 " + fetch.describe()
                        + "）；收尾会把物品栏倒回 item_storage，之后重新执行 start_collect 即可");
                    fetchFromStorage(fetch, need, ids, index + 1, dumps, next);
                    return;
                }
                ChatUtils.info("物品栏满了，先把用不上的东西倒回 item_storage 再接着取材料");
                pushDumpInventoryExcept(need, () ->
                    fetchFromStorage(fetch, need, ids, index, dumps + 1, next));
            });
    }

    /**
     * 把物品栏 27 格里「这一步用不上的东西」倒回 item_storage，腾出空位。
     *
     * <p>{@code keep} 里的材料不动（那是这一步马上要用的）。
     */
    private void pushDumpInventoryExcept(Map<String, Integer> keep, Runnable next) {
        pushSync("物品栏满了：把用不上的东西倒回 item_storage 腾地方", () -> {
            List<Integer> ids = new ArrayList<>(state.storageTargets());
            if (ids.isEmpty()) {
                fail("物资存储地都满了，物品栏里的东西倒不回去");
                return;
            }
            dumpInventoryExcept(keep, ids, 0, next);
        });
    }

    private void dumpInventoryExcept(Map<String, Integer> keep, List<Integer> ids, int index, Runnable next) {
        if (index >= ids.size()) {
            ChatUtils.error("item_storage 都塞满了，物品栏里的东西倒不回去（先清一下仓库）");
            next.run();
            return;
        }

        int id = ids.get(index);
        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            dumpInventoryExcept(keep, ids, index + 1, next);
            return;
        }

        ItemList keepList = keep.isEmpty() ? ItemList.empty() : itemList(keep);
        pushChild("把物品栏里用不上的东西放进 item_storage #" + id,
            () -> ContainerPutAction.everythingExcept(pos.pos(), keepList, false, -1, true),
            action -> {
                ContainerPutAction put = (ContainerPutAction) action;
                if (put.detail() != null) state.setStorage(id, put.detail(), put.freeSlots());

                if (put.allCleared()) {
                    next.run();
                    return;
                }
                ChatUtils.info("item_storage #" + id + " 也装不下了，换下一个");
                dumpInventoryExcept(keep, ids, index + 1, next);
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
            lastCraftActual = plan.actualCount();
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

    /**
     * 把快捷栏 4~9 格的成品放进目标容器。
     *
     * <p>{@code final=true} 的步骤：这次合多了（{@code crafted > requested}，数量不是单次产量整数倍时
     * 就会这样）的话，<b>先</b>把成品整批放进 item_storage（多出来的就留在仓库里），<b>再</b>把要交付的
     * {@code requested} 个取回来放进最终产物暂存盒 —— 暂存盒里从头到尾都只有要求的数量，
     * 不会「先塞进去再往外掏」。见 {@link #pushStoreThenStaging}。
     *
     * <p>{@code final=false} 的步骤有个特判：这一步做出来的东西要是<b>后面的步骤马上要用</b>
     * （在它们的 {@code sub_materials} 里），就<b>不</b>整批塞进 item_storage，而是把多出来的那部分
     * 存进 item_storage、把要留的那些 QuickMove 进自己的 27 格物品栏 —— 下一步合成直接用手边的材料，
     * 省掉一次「去仓库收集」。见 {@link #pushKeepForLater}。
     */
    private void pushPutResult(StepData step, int requested, int crafted, Runnable next) {
        if (!step.isFinal()) {
            int keep = Math.max(0, Math.min(crafted, currentLaterNeed - keptForLater));
            if (keep > 0) {
                keptForLater += keep;
                pushKeepForLater(step.itemId(), crafted, keep, crafted - keep, next);
                return;
            }

            // 后面的步骤用不到（或者已经留够了）：成品在快捷栏 4~9 格，放进 item_storage
            pushSync("把中间产物放进 item_storage", () -> {
                List<Integer> ids = new ArrayList<>(state.storageTargets());
                if (ids.isEmpty()) {
                    fail("物资存储地都满了，放不下中间产物");
                    return;
                }
                putHotbarToStorage(ids, 0, next);
            });
            return;
        }

        int surplus = crafted - requested;

        // 多出来的那部分**先回 item_storage**，然后才往暂存盒里放：
        // 暂存盒只收到「要交付的」那些，不会先把整批塞进去再往外掏
        // （那样要求暂存盒先装得下全部，多绕一趟，而且中途还会短暂多占格子）。
        //
        // 放完再把物品栏 27 格里剩下的东西（过程材料、合多了的中间产物）倒回 item_storage：
        // 这一步是某个最终产物的收尾，手边剩下的都不再需要了，留着只会把 27 格占满。
        if (surplus > 0) {
            pushStoreThenStaging(step.itemId(), requested, () -> pushDumpInventory(next));
            return;
        }
        pushPutResultToStaging(() -> pushDumpInventory(next));
    }

    /** 把物品栏 27 格里剩下的东西（过程材料、多合出来的中间产物）倒回 item_storage。 */
    private void pushDumpInventory(Runnable next) {
        pushSync("把物品栏里剩下的东西倒回 item_storage", () -> {
            if (!hasMainInventoryItems()) {
                next.run();
                return;
            }
            List<Integer> ids = new ArrayList<>(state.storageTargets());
            if (ids.isEmpty()) {
                ChatUtils.error("物资存储地都满了，物品栏里剩下的东西先留着");
                next.run();
                return;
            }
            dumpInventoryExcept(Map.of(), ids, 0, next);
        });
    }

    /** 物品栏 27 格（不含快捷栏）里有没有东西。 */
    private static boolean hasMainInventoryItems() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        for (int i = 9; i < 36; i++) {
            if (!player.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    /**
     * {@code final=true} 且这次合多了：快捷栏 4~9 格的成品整批 QuickMove 进 item_storage
     * （多出来的自然就留在仓库里），再把要交付的 {@code requested} 个取回来放进暂存盒。
     */
    private void pushStoreThenStaging(String itemId, int requested, Runnable next) {
        pushSync("先把这次合出来的 " + itemId + " 放进 item_storage（多出来的留在仓库里）", () -> {
            List<Integer> ids = new ArrayList<>(state.storageTargets());
            if (ids.isEmpty()) {
                fail("物资存储地都满了，多出来的成品没地方放");
                return;
            }
            putHotbarToStorage(ids, 0, () -> pushFetchForStaging(itemId, requested, next));
        });
    }

    /** 从 item_storage 把要交付的那部分取回物品栏（下一步就放进暂存盒）。 */
    private void pushFetchForStaging(String itemId, int requested, Runnable next) {
        ItemList want = itemListOf(itemId, requested);
        pushSync("把要交付的 " + requested + " 个 " + itemId + " 从 item_storage 取回来", () ->
            fetchForStaging(want, itemId, requested, new ArrayList<>(state.storageIds()), 0, 0, next));
    }

    private void fetchForStaging(ItemList want, String itemId, int requested,
                                 List<Integer> ids, int index, int flushes, Runnable next) {
        if (want.isEmpty()) {
            pushPutRequestedToStaging(itemId, requested, next);
            return;
        }
        if (index >= ids.size()) {
            fail("item_storage 里没找到刚放进去的 " + want.describe() + "，没法往暂存盒里放");
            return;
        }

        int id = ids.get(index);
        if (!storageHasLoose(id, want)) {
            fetchForStaging(want, itemId, requested, ids, index + 1, flushes, next);
            return;
        }

        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            fetchForStaging(want, itemId, requested, ids, index + 1, flushes, next);
            return;
        }

        pushChild("从 item_storage #" + id + " 把 " + want.describe() + " 取回来（only_item）",
            () -> new ContainerGetAction(pos.pos(), want, true, ContainerGetAction.Mode.ONLY_ITEM),
            action -> {
                ContainerGetAction get = (ContainerGetAction) action;
                if (get.detail() != null) state.setStorage(id, get.detail(), get.freeSlots());

                if (get.allCleared()) {
                    fetchForStaging(want, itemId, requested, ids, index + 1, flushes, next);
                    return;
                }

                // 物品栏塞不下要交付的全部：先把手边**用不上的**东西倒回 item_storage
                // （过程材料、合多了的中间产物），再把已经取到的放进暂存盒，然后从同一个容器接着取。
                // 最多来回 MAX_STAGING_FLUSHES 次，免得「取不到又放不出去」的时候空转。
                if (flushes >= MAX_STAGING_FLUSHES) {
                    fail("物品栏放不下要交付的 " + want.describe() + " 了");
                    return;
                }
                StationPos spot = placement(STAGING_ID);
                if (spot == null) {
                    fail("找不到摆放处 #" + STAGING_ID);
                    return;
                }
                ChatUtils.info("物品栏放不下更多了：先把用不上的东西倒回 item_storage，再接着取");
                pushDumpInventoryExcept(Map.of(itemId, Math.max(1, requested)), () ->
                    pushPutWantedToStaging(spot, itemListOf(itemId, requested), () ->
                        fetchForStaging(want, itemId, requested, ids, index, flushes + 1, next)));
            });
    }

    /** 把物品栏里那部分成品放进暂存盒（只放 item_list 要的，放完正好是 requested 个）。 */
    private void pushPutRequestedToStaging(String itemId, int requested, Runnable next) {
        pushSync("把要交付的 " + requested + " 个 " + itemId + " 放进最终产物暂存盒", () -> {
            StationPos spot = placement(STAGING_ID);
            if (spot == null) {
                fail("找不到摆放处 #" + STAGING_ID);
                return;
            }
            pushPutWantedToStaging(spot, itemListOf(itemId, requested), next);
        });
    }

    private void pushPutWantedToStaging(StationPos spot, ItemList want, Runnable next) {
        pushChild("把物品栏里的成品放进暂存盒（只要 item_list 里的）",
            () -> new ContainerPutAction(spot.pos(), ContainerPutAction.Mode.WANTED, true, want),
            action -> {
                ContainerPutAction put = (ContainerPutAction) action;
                if (put.detail() != null) state.setPlacement(STAGING_ID, put.detail().all());

                if (put.allCleared()) {
                    next.run();
                    return;
                }

                ChatUtils.info("暂存盒满了，先把它送去 item_final，再换一个空盒");
                pushMineAndCollect(spot, STAGING_ID, PutTarget.FINAL);
                pushTakeProviderBox();
                pushPlaceHeldBox(STAGING_ID, Map.of());
                pushPutWantedToStaging(spot, want, next);
            });
    }

    /**
     * 特判：这一步做出来的中间产物正好是后面步骤要用的。
     *
     * <p>{@code keep} 个留在物品栏（下一步直接用），多出来的 {@code excess} 个照常存进 item_storage。
     * 没有多余的（{@code excess <= 0}）就是最省事的情况：不碰任何容器，直接把快捷栏 4~9 格
     * QuickMove 进 27 格物品栏。
     */
    private void pushKeepForLater(String itemId, int crafted, int keep, int excess, Runnable next) {
        pushSync("中间产物 " + itemId + " 后面还要 " + keep + " 个（这次做了 " + crafted
            + " 个）：留 " + keep + " 个在物品栏，"
            + (excess > 0 ? "多出来的 " + excess + " 个存进 item_storage" : "没有多余的"), () -> {
            if (excess <= 0) {
                pushMoveResultsToInventory(next);
                return;
            }

            List<Integer> ids = new ArrayList<>(state.storageTargets());
            if (ids.isEmpty()) {
                fail("物资存储地都满了，放不下中间产物");
                return;
            }
            // 先把整批存进 item_storage，再把要留的那部分取回来（容器放物只能整叠搬，分开不好控制）
            putHotbarToStorage(ids, 0, () -> pushFetchBack(itemId, keep, next));
        });
    }

    /**
     * 把快捷栏 4~9 格的成品 QuickMove 进自己的 27 格物品栏（不打开任何界面，见
     * {@link HotbarQuickMoveAction}）。
     *
     * <p>27 格实在放不下（很少见）就把剩下的照旧存进 item_storage，下一步会照旧去收集。
     */
    private void pushMoveResultsToInventory(Runnable next) {
        pushChild("把快捷栏第 " + (RESULT_FROM + 1) + "~" + (RESULT_TO + 1)
                + " 格的成品挪进 27 格物品栏",
            () -> new HotbarQuickMoveAction(RESULT_FROM, RESULT_TO),
            action -> {
                HotbarQuickMoveAction move = (HotbarQuickMoveAction) action;
                if (move.allMoved()) {
                    next.run();
                    return;
                }

                ChatUtils.info("27 格物品栏放不下" + move.leftovers() + "，这部分照旧存进 item_storage");
                List<Integer> ids = new ArrayList<>(state.storageTargets());
                if (ids.isEmpty()) {
                    fail("物资存储地都满了，放不下这些成品");
                    return;
                }
                putHotbarToStorage(ids, 0, next);
            });
    }

    /** 把刚存进 item_storage 的中间产物取回 {@code keep} 个到物品栏（取不齐就记一句，下一步照旧会去取）。 */
    private void pushFetchBack(String itemId, int keep, Runnable next) {
        if (keep <= 0) {
            next.run();
            return;
        }

        ItemList fetch = itemListOf(itemId, keep);
        pushSync("把要留的 " + keep + " 个 " + itemId + " 从 item_storage 取回物品栏", () ->
            fetchBack(fetch, state.storageIds(), 0, next));
    }

    private void fetchBack(ItemList fetch, List<Integer> ids, int index, Runnable next) {
        if (fetch.isEmpty()) {
            next.run();
            return;
        }
        if (index >= ids.size()) {
            ChatUtils.error("item_storage 里没找到刚存进去的 " + fetch.describe() + "（下一步会照旧去收集）");
            next.run();
            return;
        }

        int id = ids.get(index);
        if (!storageHasLoose(id, fetch)) {
            fetchBack(fetch, ids, index + 1, next);
            return;
        }

        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            fetchBack(fetch, ids, index + 1, next);
            return;
        }

        pushChild("从 item_storage #" + id + " 把 " + fetch.describe() + " 取回来（only_item）",
            () -> new ContainerGetAction(pos.pos(), fetch, true, ContainerGetAction.Mode.ONLY_ITEM),
            action -> {
                ContainerGetAction get = (ContainerGetAction) action;
                if (get.detail() != null) state.setStorage(id, get.detail(), get.freeSlots());
                if (!get.allCleared()) {
                    ChatUtils.error("物品栏放不下要留的 " + fetch.describe() + " 了，剩下的留在 item_storage");
                    next.run();
                    return;
                }
                fetchBack(fetch, ids, index + 1, next);
            });
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

            // 往暂存盒里放一律 QuickMove：放哪儿、能放多少由服务端决定，满没满也由它说了算
            pushChild("把快捷栏第 " + (RESULT_FROM + 1) + "~" + (RESULT_TO + 1) + " 格的成品放进暂存盒",
                () -> ContainerPutAction.hotbarRange(spot.pos(), RESULT_FROM, RESULT_TO, true),
                action -> {
                    ContainerPutAction put = (ContainerPutAction) action;
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

    /**
     * 从盒子/摆放处掏完材料之后，把**这种材料**（散装的）存回 item_storage。
     *
     * <p>只存这一种：掏盒子只往物品栏里拿了这一种材料，存它一个就等价；而 {@code EVERYTHING}
     * 会把物品栏里**别的东西**也一起扫走 —— 比如上一步特意留下来给下一步用的中间产物，
     * 那就白留了（下一步还得跑去 item_storage 收集一趟）。
     */
    private void pushStoreItemToStorage(String itemId, Runnable next) {
        pushSync("把掏出来的 " + itemId + " 存进 item_storage", () -> {
            List<Integer> ids = new ArrayList<>(state.storageTargets());
            if (ids.isEmpty()) {
                fail("物资存储地都满了，放不下了");
                return;
            }
            storeItemToStorage(ids, 0, itemId, next);
        });
    }

    private void storeItemToStorage(List<Integer> ids, int index, String itemId, Runnable next) {
        if (index >= ids.size()) {
            fail("物资存储地的几个容器都放不下了");
            return;
        }

        int id = ids.get(index);
        StationPos pos = stationPos(StationPart.ITEM_STORAGE, id);
        if (pos == null) {
            storeItemToStorage(ids, index + 1, itemId, next);
            return;
        }

        pushChild("把掏出来的 " + itemId + " 放进 item_storage #" + id,
            () -> new ContainerPutAction(pos.pos(), ContainerPutAction.Mode.WANTED, true,
                itemListOf(itemId, 1)),
            action -> {
                ContainerPutAction put = (ContainerPutAction) action;
                if (put.detail() != null) state.setStorage(id, put.detail(), put.freeSlots());

                if (put.allCleared()) {
                    next.run();
                } else {
                    ChatUtils.info("item_storage #" + id + " 满了，换下一个");
                    storeItemToStorage(ids, index + 1, itemId, next);
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
