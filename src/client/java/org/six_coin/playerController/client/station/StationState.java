package org.six_coin.playerController.client.station;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Writer;
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
 * <p>格式见需求附件 1.2.1：
 * <pre>
 * {
 *   "item_storage": { "1": { "id": 1, "items": {...}, "shulker_boxes": [ {...} ] }, ... },
 *   "item_final": { "1": true, "2": false },           // 每个 item_final 容器存满了没
 *   "shulker_box_placement": { "1": {...} }            // 摆放处 id -> 那个潜影盒里的东西（没放盒子的不记）
 * }
 * </pre>
 *
 * <p>更新时机很重要：只要是**我们**改了 item_storage / item_final / 摆放处里的东西，就要在那一刻主动更新，
 * 不能靠关闭箱子时的自动监听（会有竞态：关箱之后我们可能马上就要用这份数据）。
 *
 * <p>任务做完会写到 {@code config/player-controller/debug/station_data.json}。
 */
public final class StationState {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

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
     * 从 {@code debug/station_data.json} 读一份（单独跑第二部分的时候用）。
     *
     * @return 文件不存在 / 读不了就返回 null
     */
    @Nullable
    public static StationState loadDebug() {
        Path file = debugFile();
        if (!Files.exists(file)) return null;

        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;

            StationState state = new StationState();

            JsonElement storage = root.getAsJsonObject().get("item_storage");
            if (storage != null && storage.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : storage.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject object = entry.getValue().getAsJsonObject();

                    int id = object.has("id") ? object.get("id").getAsInt() : parseInt(entry.getKey());
                    if (id <= 0) continue;

                    Storage target = state.itemStorage.computeIfAbsent(id, Storage::new);
                    int freeSlots = object.has("free_slots") ? object.get("free_slots").getAsInt() : -1;
                    target.set(readItems(object.get("items")), readBoxes(object.get("shulker_boxes")), freeSlots);
                }
            }

            JsonElement finals = root.getAsJsonObject().get("item_final");
            if (finals != null && finals.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : finals.getAsJsonObject().entrySet()) {
                    int id = parseInt(entry.getKey());
                    if (id <= 0) continue;
                    state.itemFinalFull.put(id, entry.getValue().getAsBoolean());
                }
            }

            JsonElement placement = root.getAsJsonObject().get("shulker_box_placement");
            if (placement != null && placement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : placement.getAsJsonObject().entrySet()) {
                    int id = parseInt(entry.getKey());
                    if (id <= 0) continue;
                    state.placements.put(id, readItems(entry.getValue()));
                }
            }

            ChatUtils.debug("从 " + file + " 读到了 station_data：item_storage "
                + state.itemStorage.size() + " 个，item_final " + state.itemFinalFull.size() + " 个");
            return state;
        } catch (Exception e) {
            ChatUtils.error("读 " + file + " 失败：" + e.getMessage());
            return null;
        }
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
    // 落盘
    // ------------------------------------------------------------------

    public static Path debugFile() {
        return PlayerControllerConfig.configDir().resolve("debug").resolve("station_data.json");
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();

        JsonObject storage = new JsonObject();
        for (Storage entry : itemStorage.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.id());
            object.add("items", itemsJson(entry.items()));

            com.google.gson.JsonArray boxes = new com.google.gson.JsonArray();
            for (Map<String, Integer> box : entry.shulkerBoxes()) boxes.add(itemsJson(box));
            object.add("shulker_boxes", boxes);
            object.addProperty("free_slots", entry.freeSlots());

            storage.add(String.valueOf(entry.id()), object);
        }
        root.add("item_storage", storage);

        JsonObject finals = new JsonObject();
        for (Map.Entry<Integer, Boolean> entry : itemFinalFull.entrySet()) {
            finals.addProperty(String.valueOf(entry.getKey()), Boolean.TRUE.equals(entry.getValue()));
        }
        root.add("item_final", finals);

        JsonObject placement = new JsonObject();
        for (Map.Entry<Integer, Map<String, Integer>> entry : placements.entrySet()) {
            placement.add(String.valueOf(entry.getKey()), itemsJson(entry.getValue()));
        }
        root.add("shulker_box_placement", placement);

        return root;
    }

    /** 写到 config/player-controller/debug/station_data.json。 */
    public void saveDebug() {
        Path file = debugFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(toJson(), writer);
            }
            ChatUtils.info("station_data 已写到 " + file);
        } catch (IOException e) {
            ChatUtils.error("写 station_data 失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // json 小工具
    // ------------------------------------------------------------------

    public static JsonObject itemsJson(Map<String, Integer> items) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object;
    }

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
