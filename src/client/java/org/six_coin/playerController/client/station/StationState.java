package org.six_coin.playerController.client.station;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.util.ChatUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 备货任务期间**不落盘**维护的工作站状态（station_data）。
 *
 * <p>内容：
 * <pre>
 * item_storage            每个存储容器：散装物品 + 每个潜影盒的内容 + 还剩几个空格
 * item_final              每个最终产物容器存满了没
 * shulker_box_placement   摆放处 id -> 那个潜影盒里的东西（没放盒子的不记）
 * next_shulker_box_placement_id  下一个用哪个摆放处（第一部分阶段3 交给第二部分阶段3）
 * </pre>
 *
 * <p><b>这份数据永远不落盘</b>：只在这一轮任务的内存里传递、用完就没了
 * （所以第二部分只能从 {@code /pc stock task <名字> start} 一路跑下来）。
 *
 * <p>更新时机很重要：只要是**我们**改了 item_storage / item_final / 摆放处里的东西，就要在那一刻主动更新，
 * 不能靠关闭箱子时的自动监听（会有竞态：关箱之后我们可能马上就要用这份数据）。
 */
public final class StationState {

    /** 一个 item_storage 容器：散装物品 + 每个潜影盒的内容 + 还剩几个空格。 */
    public static final class Storage {
        private final int id;
        private Map<String, Integer> items = new TreeMap<>();
        private List<Map<String, Integer>> shulkerBoxes = new ArrayList<>();
        /** 还剩几个空格；-1 表示还不知道（卸货时会先试它，put 返回后就有准数了）。 */
        private int freeSlots = -1;

        Storage(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public Map<String, Integer> items() {
            return items;
        }

        public List<Map<String, Integer>> shulkerBoxes() {
            return shulkerBoxes;
        }

        public int freeSlots() {
            return freeSlots;
        }

        void set(Map<String, Integer> items, List<Map<String, Integer>> boxes, int freeSlots) {
            this.items = new TreeMap<>(items);
            this.shulkerBoxes = new ArrayList<>();
            for (Map<String, Integer> box : boxes) this.shulkerBoxes.add(new TreeMap<>(box));
            this.freeSlots = freeSlots;
        }
    }

    private final Map<Integer, Storage> itemStorage = new TreeMap<>();
    private final Map<Integer, Boolean> itemFinalFull = new TreeMap<>();
    private final Map<Integer, Map<String, Integer>> placements = new TreeMap<>();
    /** 下一个用哪个摆放处（初始 2；第一部分阶段3 结束时把值留给第二部分阶段3）。 */
    private int nextShulkerBoxPlacementId = 2;

    private StationState() {
    }

    // ------------------------------------------------------------------
    // 建 / 读
    // ------------------------------------------------------------------

    /** 从 station.json（有哪些容器）+ check.json 的 item_storage_detailed（里面有什么）建一份。 */
    public static StationState load(int world) {
        StationState state = new StationState();

        for (StationPos pos : StationManager.get().list(StationPart.ITEM_STORAGE)) {
            state.itemStorage.put(pos.id(), new Storage(pos.id()));
        }
        // 检查要求最终产物地全空，所以一开始都是没满
        for (StationPos pos : StationManager.get().list(StationPart.ITEM_FINAL)) {
            state.itemFinalFull.put(pos.id(), false);
        }

        Path checkFile = StationManager.checkFile(world);
        if (!Files.exists(checkFile)) {
            ChatUtils.debug("没有 " + checkFile + "，station_data 里 item_storage 先按空的算");
            return state;
        }

        try {
            JsonElement root = JsonParser.parseString(Files.readString(checkFile, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return state;
            JsonElement detailed = root.getAsJsonObject().get("item_storage_detailed");
            if (detailed == null || !detailed.isJsonObject()) return state;

            for (Map.Entry<String, JsonElement> entry : detailed.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject object = entry.getValue().getAsJsonObject();

                int id = object.has("id") ? object.get("id").getAsInt() : parseInt(entry.getKey());
                if (id <= 0) continue;

                Storage storage = state.itemStorage.computeIfAbsent(id, Storage::new);
                int freeSlots = object.has("free_slots") ? object.get("free_slots").getAsInt() : -1;
                storage.set(readItems(object.get("items")), readBoxes(object.get("shulker_boxes")), freeSlots);
            }
            ChatUtils.debug("station_data：从 check.json 读到 " + state.itemStorage.size() + " 个存储容器");
        } catch (Exception e) {
            ChatUtils.error("读 " + checkFile + " 失败：" + e.getMessage());
        }
        return state;
    }

    // ------------------------------------------------------------------
    // 查询 / 更新
    // ------------------------------------------------------------------

    /** item_storage 的所有 id：**从 1 开始**按 id 升序，只留还有空格的（free_slots 未知 -1 的也算可用）。 */
    public List<Integer> storageTargets() {
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, Storage> entry : itemStorage.entrySet()) {
            if (entry.getValue().freeSlots() == 0) continue;
            ids.add(entry.getKey());
        }
        return ids;
    }

    /** item_final 里还没满的 id。 */
    public List<Integer> finalTargets() {
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> entry : itemFinalFull.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) ids.add(entry.getKey());
        }
        return ids;
    }

    public Storage storage(int id) {
        return itemStorage.get(id);
    }

    /** 所有 item_storage 的 id（从 1 开始，升序）。 */
    public List<Integer> storageIds() {
        return new ArrayList<>(itemStorage.keySet());
    }

    /**
     * 把所有 item_storage 里的东西（散装 + 每个潜影盒里的）加在一起。
     *
     * <p>就是 {@code check.json} 里 {@code item_storage} 那种最简单的 item_list ——
     * 第二部分阶段1 要用它反推一份「现在的仓库里有什么」。
     */
    public Map<String, Integer> storageItems() {
        Map<String, Integer> total = new TreeMap<>();
        for (Storage storage : itemStorage.values()) {
            merge(total, storage.items());
            for (Map<String, Integer> box : storage.shulkerBoxes()) merge(total, box);
        }
        return total;
    }

    private static void merge(Map<String, Integer> total, Map<String, Integer> items) {
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            total.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    /**
     * 除了 {@code excludeId} 之外，所有摆放处上的潜影盒里的东西加起来。
     *
     * <p>第二部分阶段1 算「我到底有多少东西」的时候要把这些也算上：
     * 摆在摆放处上的盒子里的东西就在手边，后面的阶段随手就能掏出来用。
     */
    public Map<String, Integer> placementItemsExcept(int excludeId) {
        Map<String, Integer> total = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : placements.entrySet()) {
            if (entry.getKey() == excludeId) continue;
            merge(total, entry.getValue());
        }
        return total;
    }

    /**
     * 下一个用哪个潜影盒摆放处（第一部分阶段3 用完之后交给第二部分阶段3 接着用）。
     *
     * <p>station_data 不落盘，这个值只在一次任务的内存里传递。
     */
    public int nextShulkerBoxPlacementId() {
        return nextShulkerBoxPlacementId;
    }

    public void setNextShulkerBoxPlacementId(int id) {
        this.nextShulkerBoxPlacementId = Math.max(2, id);
    }

    /** 用「刚数出来的」结果覆盖某个存储容器的记录（含还剩几个空格）。 */
    public void setStorage(int id, ContainerCacheManager.Breakdown breakdown, int freeSlots) {
        Storage storage = itemStorage.computeIfAbsent(id, Storage::new);
        storage.set(breakdown.items(), breakdown.shulkerBoxes(), freeSlots);
    }

    public void setFinalFull(int id, boolean full) {
        itemFinalFull.put(id, full);
    }

    /** 摆放处放上/换了潜影盒。 */
    public void setPlacement(int id, Map<String, Integer> contents) {
        placements.put(id, new TreeMap<>(contents));
    }

    /** 摆放处的潜影盒被拿走了。 */
    public void clearPlacement(int id) {
        placements.remove(id);
    }

    public boolean hasPlacement(int id) {
        return placements.containsKey(id);
    }

    public Map<String, Integer> placement(int id) {
        return placements.getOrDefault(id, Map.of());
    }

    public Map<Integer, Map<String, Integer>> placements() {
        return placements;
    }

    // ------------------------------------------------------------------
    // json 小工具
    // ------------------------------------------------------------------

    private static Map<String, Integer> readItems(JsonElement element) {
        Map<String, Integer> items = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) return items;

        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            try {
                int count = entry.getValue().getAsInt();
                if (count > 0) items.put(entry.getKey(), count);
            } catch (Exception ignored) {
                // 不是数字就跳过
            }
        }
        return items;
    }

    private static List<Map<String, Integer>> readBoxes(JsonElement element) {
        List<Map<String, Integer>> boxes = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return boxes;
        for (JsonElement each : element.getAsJsonArray()) {
            boxes.add(readItems(each));
        }
        return boxes;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
