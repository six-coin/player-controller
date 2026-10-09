package org.six_coin.playerController.client.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.station.StationState;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.stock.StockMaterials;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.ShulkerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /pc stock task <名字> start} 的「第一部分 阶段2：取货」。
 *
 * <p>流程（按需求）：
 * <ol>
 *   <li>读 check.json 的 {@code item_storage_detailed}，建一份**不落盘**的 station_data（{@link StationState}）；</li>
 *   <li>按 all_items.json 里每个容器的 {@code cost} 从低到高扫一遍，算出 need_container_list
 *       （能提供 final_final 里东西的容器；这步的扣减只在内存里）；</li>
 *   <li>重新读一份完整的 final_final；</li>
 *   <li>循环：从当前位置找 need_container_list 里**最近**的容器 → 走到它的 access_position →
 *       取货（拿回 item_list 和 all_cleared）→ 更新 final_final，拿完了就把 id 从列表里去掉；
 *       列表空了就回站立点结束；物品栏 27 格满了就先回站立点卸货（潜影盒进 item_storage，物品进 item_final）；</li>
 *   <li>把物品栏里剩下的潜影盒和物品都存进 item_storage，然后把 station_data 写到
 *       {@code config/player-controller/debug/station_data.json}，任务结束。</li>
 * </ol>
 *
 * <p>station_data 一律**主动更新**：每次 get/put 完就用动作返回的 detail 覆盖记录，
 * 不依赖「关箱子时自动刷新」那一套（会有竞态）。
 */
public class StockStage2Action extends Action {

    /** all_items.json 里的一个容器。 */
    private record NeedEntry(int id,
                             int cost,
                             String dimension,
                             BlockPos containerPos,
                             BlockPos accessPos,
                             Map<Item, Integer> items) {
    }

    private enum Stage {
        /** 挑下一个要去的容器。 */
        PICK,
        /** 走向某个容器 / 站立点（走完看 nextAfterMove）。 */
        MOVING,
        GETTING,
        DUMP_MOVING,
        DUMP_BOXES,
        DUMP_ITEMS,
        HOME_MOVING,
        HOME_BOXES,
        HOME_ITEMS,
        DONE
    }

    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60;

    private final String task;

    private StationState state;
    private ItemList need;

    private final Map<Integer, NeedEntry> entries = new LinkedHashMap<>();
    private final List<Integer> needList = new ArrayList<>();

    private Stage stage = Stage.PICK;
    private Stage nextAfterMove = Stage.PICK;
    private Action child;
    private Integer targetId;

    // 放东西阶段
    private ContainerPutAction.Mode putMode = ContainerPutAction.Mode.ALL_ITEMS;
    private StationPart putPart = StationPart.ITEM_STORAGE;
    private Stage putPhase = Stage.DUMP_BOXES;
    private Stage putNext = Stage.PICK;
    private List<Integer> putQueue = new ArrayList<>();
    private int putIndex;
    private int putTargetId = -1;

    private int totalTicks;
    private int fetched;
    private int dumped;

    public StockStage2Action(String task) {
        this.task = task;
    }

    @Override
    public String name() {
        return "备货 " + task + " 阶段2（取货）";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        int world = PlayerControllerConfig.getWorld();
        try {
            // 1. station_data（只放在内存里）
            state = StationState.load(world);

            // 2. all_items.json
            loadAllItems(world);

            // 3. 完整的 final_final
            Path finalFile = StockManager.get().currentFinalFinalFile(task);
            if (!Files.exists(finalFile)) {
                fail("找不到 " + finalFile + "（阶段1 生成的？）");
                return;
            }
            need = ItemList.parse(Files.readString(finalFile, StandardCharsets.UTF_8));
            ChatUtils.info("开始取货：final_final " + need.describe() + "；候选容器 " + entries.size() + " 个");
        } catch (Exception e) {
            fail("准备阶段2 失败：" + e.getMessage());
            return;
        }

        // 4. 算 need_container_list（扣减只在内存里）
        buildNeedList();

        if (needList.isEmpty()) {
            ChatUtils.info("所有东西仓库里都够了，直接收尾");
            startMoveToStand(Stage.HOME_MOVING);
            return;
        }
        stage = Stage.PICK;
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

        switch (stage) {
            case PICK -> pickNext();
            case DONE -> {
                if (state != null) state.saveDebug();
                ChatUtils.info("第一部分阶段2（取货）完成：去了 " + fetched + " 个容器，卸货 " + dumped + " 次");
                finish();
            }
            default -> {
                if (drivingDone()) onChildDone();
            }
        }
    }

    @Override
    protected void onEnd() {
        if (failureReason() != null) {
            ChatUtils.error(name() + "没做完：" + failureReason());
            if (state != null) {
                ChatUtils.info("把当前的 station_data 也写一份，方便看进度");
                state.saveDebug();
            }
        }
    }

    // ------------------------------------------------------------------
    // 准备
    // ------------------------------------------------------------------

    private void loadAllItems(int world) throws Exception {
        Path file = ContainerCacheManager.itemsFile(world);
        if (!Files.exists(file)) throw new IllegalStateException("找不到 " + file + "（先跑 get_all_items？）");

        JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) throw new IllegalStateException(file + " 不是 JSON 对象");
        JsonElement detail = root.getAsJsonObject().get("detail");
        if (detail == null || !detail.isJsonObject()) throw new IllegalStateException(file + " 里没有 detail");

        for (Map.Entry<String, JsonElement> each : detail.getAsJsonObject().entrySet()) {
            if (!each.getValue().isJsonObject()) continue;
            JsonObject object = each.getValue().getAsJsonObject();

            int id = object.has("id") ? object.get("id").getAsInt() : -1;
            if (id <= 0) continue;

            JsonObject containerPos = object.getAsJsonObject("container_position");
            JsonObject accessPos = object.getAsJsonObject("access_position");
            if (containerPos == null || accessPos == null) continue;

            String dimension = containerPos.get("dimension").getAsString();
            Map<Item, Integer> items = new LinkedHashMap<>();
            JsonElement itemsElement = object.get("items");
            if (itemsElement != null && itemsElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> item : itemsElement.getAsJsonObject().entrySet()) {
                    Item parsed = StockMaterials.parseItem(item.getKey());
                    if (parsed == null) continue;
                    items.put(parsed, item.getValue().getAsInt());
                }
            }

            entries.put(id, new NeedEntry(id,
                object.has("cost") ? object.get("cost").getAsInt() : 0,
                dimension, readPos(containerPos), readPos(accessPos), items));
        }
    }

    /** 按 cost 从低到高扫一遍，算出要去哪些容器（内存里的扣减，不落盘）。 */
    private void buildNeedList() {
        List<NeedEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingInt(NeedEntry::cost));

        ItemList probe;
        try {
            probe = ItemList.parse(need.toJson());   // 副本，随便扣
        } catch (ItemList.ParseException e) {
            fail("自己生成的 item_list 都解析不了：" + e.getMessage());
            return;
        }
        for (NeedEntry entry : sorted) {
            boolean useful = false;
            for (Map.Entry<Item, Integer> item : entry.items().entrySet()) {
                int remaining = probe.remaining(item.getKey());
                if (remaining <= 0) continue;
                useful = true;
                probe.take(item.getKey(), Math.min(remaining, item.getValue()));
            }
            if (!useful) continue;

            needList.add(entry.id());
            if (probe.isEmpty()) break;
        }

        ChatUtils.info("按 cost 需要跑 " + needList.size() + " 个容器：" + needList
            + "；模拟扣完 final_final " + (probe.isEmpty() ? "清空了（符合预期）" : ("还剩 " + probe.describe())));
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private void pickNext() {
        if (needList.isEmpty()) {
            ChatUtils.info("需要的容器都取完了，回站立点收尾");
            startMoveToStand(Stage.HOME_MOVING);
            return;
        }

        Waypoint here = WaypointManager.get().playerStartWaypoint();
        if (here == null) {
            fail("你现在既不在路径点上，也不在任何边上，算不了路");
            return;
        }

        Map<Integer, Double> dist = WaypointManager.get().graph().distancesFrom(here.id());

        Integer bestId = null;
        Waypoint bestNode = null;
        double bestCost = Double.MAX_VALUE;
        for (int id : needList) {
            NeedEntry entry = entries.get(id);
            if (entry == null) continue;

            Waypoint node = WaypointManager.get().graph().at(entry.dimension(), entry.accessPos());
            if (node == null) {
                ChatUtils.debug("容器 #" + id + " 的落点不是路径点，跳过");
                continue;
            }
            Double cost = dist.get(node.id());
            if (cost == null || cost >= bestCost) continue;

            bestCost = cost;
            bestId = id;
            bestNode = node;
        }

        if (bestId == null || bestNode == null) {
            fail("剩下的 " + needList.size() + " 个容器从当前位置都走不到");
            return;
        }

        targetId = bestId;
        ChatUtils.info("下一个去容器 #" + bestId + "（距离约 " + Math.round(bestCost) + "），还剩 "
            + needList.size() + " 个要跑");
        startMove(bestNode, Stage.GETTING);
    }

    /** 子动作跑完了的统一收尾。 */
    private void onChildDone() {
        String failure = child == null ? null : child.failureReason();
        Stage done = stage;
        if (failure != null) {
            fail("子任务失败（" + done + "）：" + failure);
            return;
        }

        switch (done) {
            case MOVING -> {
                child = null;
                if (nextAfterMove == Stage.GETTING) {
                    startGet();
                } else {
                    beginPhase(nextAfterMove);
                }
            }
            case GETTING -> afterGet();
            case DUMP_MOVING -> beginPhase(Stage.DUMP_BOXES);
            case DUMP_BOXES, DUMP_ITEMS, HOME_BOXES, HOME_ITEMS -> afterPut();
            case HOME_MOVING -> beginPhase(Stage.HOME_BOXES);
            default -> { }
        }
    }

    /** 开始某个阶段：put 阶段要先挑容器，其它阶段直接切过去。 */
    private void beginPhase(Stage next) {
        child = null;
        switch (next) {
            case DUMP_BOXES -> startPutPhase(ContainerPutAction.Mode.ALL_SHULKER_BOXES,
                StationPart.ITEM_STORAGE, Stage.DUMP_BOXES, Stage.DUMP_ITEMS);
            case DUMP_ITEMS -> startPutPhase(ContainerPutAction.Mode.ALL_ITEMS,
                StationPart.ITEM_FINAL, Stage.DUMP_ITEMS, Stage.PICK);
            case HOME_BOXES -> startPutPhase(ContainerPutAction.Mode.ALL_SHULKER_BOXES,
                StationPart.ITEM_STORAGE, Stage.HOME_BOXES, Stage.HOME_ITEMS);
            case HOME_ITEMS -> startPutPhase(ContainerPutAction.Mode.ALL_ITEMS,
                StationPart.ITEM_STORAGE, Stage.HOME_ITEMS, Stage.DONE);
            default -> stage = next;
        }
    }

    private void startGet() {
        NeedEntry entry = entries.get(targetId);
        if (entry == null) {
            fail("容器 #" + targetId + " 的记录不见了");
            return;
        }

        ChatUtils.info("到容器 #" + targetId + " 了，开始取货（final_final " + need.describe() + "）");
        child = new ContainerGetAction(entry.containerPos(), need, true);
        child.start();
        stage = Stage.GETTING;
    }

    private void afterGet() {
        if (!(child instanceof ContainerGetAction get)) {
            fail("内部错误：取货动作没了");
            return;
        }

        boolean cleared = get.allCleared();
        fetched++;

        ChatUtils.info("容器 #" + targetId + " 取完：all_cleared=" + cleared
            + "，final_final 还差 " + need.describe());

        updateStorageFromGet(get, targetId);

        if (cleared) {
            needList.remove(Integer.valueOf(targetId));
        } else {
            ChatUtils.debug("容器 #" + targetId + " 没拿完（多半是物品栏满了），留在待取列表里");
        }
        targetId = null;
        child = null;

        // 物品栏 27 格满了就回站立点卸货
        if (freeMainSlots() == 0) {
            ChatUtils.info("物品栏 27 格满了，回站立点卸货");
            startMoveToStand(Stage.DUMP_MOVING);
            return;
        }
        stage = Stage.PICK;
    }

    // ------------------------------------------------------------------
    // 走 / 放
    // ------------------------------------------------------------------

    private void startMoveToStand(Stage next) {
        StationPos stand = StationManager.get().single(StationPart.STAND_POINT);
        if (stand == null) {
            fail("没有设置站立点");
            return;
        }
        Waypoint node = WaypointManager.get().graph().at(stand.dimension(), stand.pos());
        if (node == null) {
            fail("站立点还不是路径点");
            return;
        }
        startMove(node, next);
    }

    /** 走到某个路径点；到了以后进 {@code next} 阶段。 */
    private void startMove(Waypoint node, Stage next) {
        Waypoint here = WaypointManager.get().playerStartWaypoint();
        if (here == null) {
            fail("你现在既不在路径点上，也不在任何边上");
            return;
        }

        nextAfterMove = next;
        if (here.id() == node.id()) {
            if (next == Stage.GETTING) {
                startGet();
            } else {
                beginPhase(next);
            }
            return;
        }

        List<Waypoint> path = WaypointManager.get().graph().shortestPath(here.id(), node.id());
        if (path == null || path.size() < 2) {
            fail("从当前位置走不到 #" + node.id() + " " + node.coordString());
            return;
        }

        child = new PathMoveAction(path);
        child.start();
        stage = Stage.MOVING;
    }

    /** 开始一个「往某个部分的容器里放东西」的阶段：一个放满了就试下一个。 */
    private void startPutPhase(ContainerPutAction.Mode mode, StationPart part, Stage phase, Stage next) {
        putMode = mode;
        putPart = part;
        putPhase = phase;
        putNext = next;
        putQueue = candidates(part);
        putIndex = 0;
        nextPut();
    }

    private void nextPut() {
        if (!inventoryHas(putMode)) {
            ChatUtils.debug("物品栏里没有要放的 " + putMode + " 了，" + putPart.display() + " 这一阶段结束");
            beginPhase(putNext);
            return;
        }

        if (putIndex >= putQueue.size()) {
            fail(putPart.display() + " 里没有能放下的容器了（都满了？）");
            return;
        }

        int id = putQueue.get(putIndex++);
        StationPos pos = stationPos(putPart, id);
        if (pos == null) {
            nextPut();
            return;
        }

        putTargetId = id;
        dumped++;
        ChatUtils.info("把 " + putMode + " 放进 " + putPart.display() + " #" + id + " "
            + pos.coordString() + "（" + pos.dimension() + "）");
        child = new ContainerPutAction(pos.pos(), putMode, true);
        child.start();
        stage = putPhase;
    }

    /** put 子动作跑完：用返回的 detail 更新 station_data，然后继续放 / 换下一个 / 进下一阶段。 */
    private void afterPut() {
        if (!(child instanceof ContainerPutAction put)) {
            fail("内部错误：放物动作没了");
            return;
        }

        if (put.detail() != null) {
            if (putPart == StationPart.ITEM_STORAGE) {
                state.setStorage(putTargetId, put.detail());
            } else if (putPart == StationPart.ITEM_FINAL) {
                state.setFinalFull(putTargetId, put.containerFull());
            }
        }

        if (put.allCleared()) {
            ChatUtils.debug(putPart.display() + " #" + putTargetId + " 都放下了");
            beginPhase(putNext);
            return;
        }

        ChatUtils.info(putPart.display() + " #" + putTargetId + " 满了，换下一个");
        nextPut();
    }

    private List<Integer> candidates(StationPart part) {
        return part == StationPart.ITEM_FINAL ? state.finalTargets() : state.storageTargets();
    }

    @Nullable
    private StationPos stationPos(StationPart part, int id) {
        for (StationPos pos : StationManager.get().list(part)) {
            if (pos.id() == id) return pos;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // station_data 更新
    // ------------------------------------------------------------------

    /** 取货的容器如果本身就是 item_storage 之一，就用返回的 detail 更新 station_data。 */
    private void updateStorageFromGet(ContainerGetAction get, int containerId) {
        NeedEntry entry = entries.get(containerId);
        if (entry == null || get.detail() == null) return;

        for (StationPos pos : StationManager.get().list(StationPart.ITEM_STORAGE)) {
            if (!pos.dimension().equals(entry.dimension())) continue;
            if (!pos.pos().equals(entry.containerPos())) continue;

            state.setStorage(pos.id(), get.detail());
            ChatUtils.debug("顺手更新了 station_data 里 item_storage #" + pos.id());
            return;
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private int freeMainSlots() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;

        int free = 0;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    /** 物品栏 27 格里有没有要放的这类东西。 */
    private boolean inventoryHas(ContainerPutAction.Mode mode) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (mode == ContainerPutAction.Mode.EVERYTHING) return true;

            boolean shulker = ShulkerUtils.isShulkerBox(stack);
            if (mode == ContainerPutAction.Mode.ALL_SHULKER_BOXES ? shulker : !shulker) return true;
        }
        return false;
    }

    private static BlockPos readPos(JsonObject object) {
        return new BlockPos(object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt());
    }

    /** 驱动子动作；结束返回 true（不清理引用，让处理函数自己读结果）。 */
    private boolean drivingDone() {
        if (child == null) return true;
        child.update();
        if (!child.isFinished()) return false;
        child.cleanup();
        return true;
    }
}
