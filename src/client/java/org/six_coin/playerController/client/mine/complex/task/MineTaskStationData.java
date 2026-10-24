package org.six_coin.playerController.client.mine.complex.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mine.station.MinePlace;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.mine.station.MineStationPart;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 挖矿任务用的**运行时**挖掘站数据（{@code station_data}）：<b>不落盘</b>。
 *
 * <p>来源是两处：
 * <ul>
 *   <li>{@code world_<n>/mine/check.json}（{@code /pc mine station check} 写的报告）——
 *       每个容器在哪、落点在哪、里面有多少什么；</li>
 *   <li>{@code station.json} 里那几个「只有一个容器」的部分（黏液块缓存、圆石缓存）的位置
 *       —— 报告里这两块只记总数，不记坐标。</li>
 * </ul>
 *
 * <p><b>要维护</b>：每从容器里拿走 / 放进东西之后调 {@link ContainerInfo#take(ItemStack)} /
 * {@link ContainerInfo#put(ItemStack)}，数量、空格数跟着变 —— 补货流程全靠它判断「还够不够」，
 * 每次都去开箱子数一遍太慢。
 *
 * <p>另外还记三个潜影盒摆放处（补充 / 卸货）每个位置上那个盒子里装着什么
 * （见 {@link #placementBox}/{@link #recordPlacementBox}）：这两个位置上的盒子会被拿来拿去，
 * 内容只在「打开拿 / 放之后」更新，报告里是没有的。
 */
public final class MineTaskStationData {

    /** 一个容器：坐标、落点、里面有什么。 */
    public static final class ContainerInfo {

        private final MineStationPart part;
        private final int id;
        private final String dimension;
        private final BlockPos pos;
        @Nullable
        private final BlockPos access;
        private final String block;
        private final int slots;
        private int emptySlots;
        private final Map<String, Integer> items;

        ContainerInfo(MineStationPart part, int id, String dimension, BlockPos pos,
                      @Nullable BlockPos access, String block, int slots, int emptySlots,
                      Map<String, Integer> items) {
            this.part = part;
            this.id = id;
            this.dimension = dimension;
            this.pos = pos.immutable();
            this.access = access == null ? null : access.immutable();
            this.block = block;
            this.slots = slots;
            this.emptySlots = emptySlots;
            this.items = new LinkedHashMap<>(items);
        }

        public MineStationPart part() {
            return part;
        }

        public int id() {
            return id;
        }

        public String dimension() {
            return dimension;
        }

        public BlockPos pos() {
            return pos;
        }

        /** 站在哪里能开它（报告里算好的落点）；没有就是 null。 */
        @Nullable
        public BlockPos access() {
            return access;
        }

        public String block() {
            return block;
        }

        public int slots() {
            return slots;
        }

        public int emptySlots() {
            return emptySlots;
        }

        /** 里面有什么（物品 id -> 个数；潜影盒算的是**盒子里**的东西，和 check.json 一致）。 */
        public Map<String, Integer> items() {
            return items;
        }

        public int count(String itemId) {
            return items.getOrDefault(itemId, 0);
        }

        /** 从里面拿走了一叠东西（潜影盒按盒子里面的东西扣）。 */
        public void take(ItemStack stack) {
            if (stack.isEmpty()) return;

            if (ShulkerUtils.isShulkerBox(stack)) {
                for (ItemStack inner : ShulkerUtils.contents(stack)) {
                    subtract(BuiltInRegistries.ITEM.getKey(inner.getItem()).toString(), inner.getCount());
                }
                emptySlots = Math.min(slots, emptySlots + 1);
                return;
            }

            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean emptied = subtract(id, stack.getCount());
            if (emptied) emptySlots = Math.min(slots, emptySlots + 1);
        }

        /** 往里放了一叠东西（补货流程用）。 */
        public void put(ItemStack stack) {
            if (stack.isEmpty()) return;

            if (ShulkerUtils.isShulkerBox(stack)) {
                for (ItemStack inner : ShulkerUtils.contents(stack)) {
                    add(BuiltInRegistries.ITEM.getKey(inner.getItem()).toString(), inner.getCount());
                }
                emptySlots = Math.max(0, emptySlots - 1);
                return;
            }

            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean wasEmpty = items.getOrDefault(id, 0) <= 0;
            add(id, stack.getCount());
            if (wasEmpty) emptySlots = Math.max(0, emptySlots - 1);
        }

        /** @return 这一种物品是不是被拿空了 */
        private boolean subtract(String itemId, int count) {
            int now = items.getOrDefault(itemId, 0) - count;
            if (now <= 0) {
                items.remove(itemId);
                return true;
            }
            items.put(itemId, now);
            return false;
        }

        private void add(String itemId, int count) {
            items.merge(itemId, count, Integer::sum);
        }

        public String describe() {
            return part.display() + " #" + id + " " + DimensionUtils.display(dimension) + " "
                + pos.toShortString() + "（" + (items.isEmpty() ? "空" : items.toString()) + "）";
        }
    }

    /** 一个潜影盒摆放处的位置。 */
    public record Placement(MineStationPart part, int id, String dimension, BlockPos pos,
                            @Nullable BlockPos access) {

        public String describe() {
            return part.display() + " #" + id + " " + DimensionUtils.display(dimension) + " "
                + pos.toShortString();
        }
    }

    private final Map<MineStationPart, List<ContainerInfo>> containers = new LinkedHashMap<>();
    private final Map<MineStationPart, List<Placement>> placements = new LinkedHashMap<>();

    /**
     * 补充 / 卸货摆放处每个位置上那个盒子里装着什么。
     *
     * <p>键是 {@link #placementKey}；值里的 map 是「物品 id -> 个数」。没记过就是没有。
     */
    private final Map<String, Map<String, Integer>> placementBoxes = new LinkedHashMap<>();

    /** 记一下是哪个世界配置的（只用来打日志）。 */
    private int world = -1;

    private MineTaskStationData() {
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 读 {@code check.json}（+ {@code station.json} 里那两个单容器部分）。
     *
     * @return null 表示报告不存在 / 没通过 —— 调用方应当直接失败
     */
    @Nullable
    public static MineTaskStationData load(MineStationManager manager, Path checkFile) {
        if (!Files.exists(checkFile)) {
            ChatUtils.error("没有挖掘站检查报告：" + checkFile + "（先跑 /pc mine station check）");
            return null;
        }

        JsonObject root;
        try (Reader reader = Files.newBufferedReader(checkFile, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                ChatUtils.error("检查报告不是一个 JSON 对象：" + checkFile);
                return null;
            }
            root = element.getAsJsonObject();
        } catch (IOException e) {
            ChatUtils.error("读检查报告失败：" + e.getMessage());
            return null;
        }

        JsonElement passed = root.get("passed");
        if (passed == null || !passed.isJsonPrimitive() || !passed.getAsBoolean()) {
            ChatUtils.error("检查报告里 passed 不是 true —— 先跑 /pc mine station check 并且通过");
            return null;
        }

        MineTaskStationData data = new MineTaskStationData();
        data.world = manager.loadedWorld();

        // 单点容器：food / totem / recycler / inventory_temp（报告里是一段对象）
        for (MineStationPart part : new MineStationPart[]{MineStationPart.FOOD_PROVIDER,
            MineStationPart.TOTEM_PROVIDER, MineStationPart.EMPTY_SHULKER_BOX_RECYCLER,
            MineStationPart.INVENTORY_TEMP}) {
            JsonElement element = root.get(part.id());
            if (element == null || !element.isJsonObject()) continue;
            ContainerInfo info = readContainer(part, 1, element.getAsJsonObject());
            if (info != null) data.add(info);
        }

        // 列表容器：tool_cache / item_temp / item_collector
        for (MineStationPart part : new MineStationPart[]{MineStationPart.TOOL_CACHE,
            MineStationPart.ITEM_TEMP, MineStationPart.ITEM_COLLECTOR}) {
            JsonElement element = root.get(part.id());
            if (element == null || !element.isJsonObject()) continue;

            JsonObject object = element.getAsJsonObject();
            // item_temp 的内容包在 containers 里，另外两个直接是 id -> {...}
            if (object.has("containers") && object.get("containers").isJsonObject()) {
                object = object.getAsJsonObject("containers");
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                int id = parseInt(entry.getKey());
                ContainerInfo info = readContainer(part, id, entry.getValue().getAsJsonObject());
                if (info != null) data.add(info);
            }
        }

        // 黏液块 / 圆石缓存：位置在 station.json 里（只有一个容器），内容在报告里
        data.addCache(manager, MineStationPart.SLIME_CACHE, root.get("slime_cache"));
        data.addCache(manager, MineStationPart.COBBLESTONE_CACHE, root.get("cobblestone_cache"));

        // 三个摆放处的位置（报告里的 positions；盒子的内容一开始是空的）
        for (MineStationPart part : new MineStationPart[]{MineStationPart.UNLOADING_PLACEMENT,
            MineStationPart.SUPPLEMENT_PLACEMENT, MineStationPart.TEMP_PLACEMENT}) {
            data.readPlacements(part, root.get(part.id()));
        }

        ChatUtils.debug("station_data 读好了（world_" + data.world + "）："
            + data.describe());
        return data;
    }

    /** 报告里的一段容器对象。 */
    @Nullable
    private static ContainerInfo readContainer(MineStationPart part, int id, JsonObject object) {
        JsonObject position = object.getAsJsonObject("position");
        if (position == null) return null;

        String dimension = stringOf(position, "dimension");
        BlockPos pos = new BlockPos(intOf(position, "x"), intOf(position, "y"), intOf(position, "z"));

        JsonObject accessObject = object.getAsJsonObject("access_position");
        BlockPos access = accessObject == null ? null
            : new BlockPos(intOf(accessObject, "x"), intOf(accessObject, "y"), intOf(accessObject, "z"));

        String block = stringOf(object, "block");
        int slots = intOf(object, "slots");
        int empty = intOf(object, "empty_slots");

        Map<String, Integer> items = new LinkedHashMap<>();
        JsonObject itemsObject = object.getAsJsonObject("items");
        if (itemsObject != null) {
            for (Map.Entry<String, JsonElement> entry : itemsObject.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                items.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        return new ContainerInfo(part, id, dimension, pos, access, block, slots, empty, items);
    }

    /** 黏液块 / 圆石缓存：坐标来自 station.json，内容来自报告里的总数。 */
    private void addCache(MineStationManager manager, MineStationPart part, @Nullable JsonElement totals) {
        MinePlace place = manager.single(part);
        if (place == null) {
            ChatUtils.error("station.json 里没有" + part.display() + "的坐标");
            return;
        }
        Map<String, Integer> items = new LinkedHashMap<>();
        if (totals != null && totals.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : totals.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                String key = entry.getKey();
                if (key.equals("total") || key.equals("boxes") || key.equals("filling_block")) continue;
                items.put(key, entry.getValue().getAsInt());
            }
        }
        int total = items.values().stream().mapToInt(Integer::intValue).sum();
        // 一盒 1728：空格的准确值不重要，这里按「一盒占一格」粗算，够用了（取货前还会真开箱子看）
        int boxes = total <= 0 ? 0 : (int) Math.ceil(total / 1728.0);
        ContainerInfo info = new ContainerInfo(part, place.id(), place.dimension(), place.pos(),
            place.access() == null ? null : place.access().pos(), part.id(), 27,
            Math.max(0, 27 - boxes), items);
        add(info);
    }

    private void readPlacements(MineStationPart part, @Nullable JsonElement element) {
        List<Placement> list = new ArrayList<>();
        if (element != null && element.isJsonObject()) {
            JsonArray positions = element.getAsJsonObject().getAsJsonArray("positions");
            if (positions != null) {
                for (JsonElement entry : positions) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject object = entry.getAsJsonObject();
                    String dimension = stringOf(object, "dimension");
                    BlockPos pos = new BlockPos(intOf(object, "x"), intOf(object, "y"), intOf(object, "z"));
                    BlockPos access = object.has("access_x")
                        ? new BlockPos(intOf(object, "access_x"), intOf(object, "access_y"),
                            intOf(object, "access_z"))
                        : null;
                    list.add(new Placement(part, intOf(object, "id"), dimension, pos, access));
                }
            }
        }
        placements.put(part, list);
    }

    private void add(ContainerInfo info) {
        containers.computeIfAbsent(info.part(), key -> new ArrayList<>()).add(info);
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    public List<ContainerInfo> containers(MineStationPart part) {
        return List.copyOf(containers.getOrDefault(part, List.of()));
    }

    public List<Placement> placements(MineStationPart part) {
        return List.copyOf(placements.getOrDefault(part, List.of()));
    }

    /**
     * 这一部分里第一个「有满足条件的物品」的容器；没有返回 null。
     *
     * @param itemTest 对物品 id 的判断（例如「是不是某种工具」）
     */
    @Nullable
    public ContainerInfo firstContainerWith(MineStationPart part, Predicate<String> itemTest) {
        for (ContainerInfo info : containers(part)) {
            for (String id : info.items().keySet()) {
                if (itemTest.test(id)) return info;
            }
        }
        return null;
    }

    /** 这一部分第一个容器（单容器部分用）。 */
    @Nullable
    public ContainerInfo firstContainer(MineStationPart part) {
        List<ContainerInfo> list = containers(part);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------
    // 摆放处的盒子内容
    // ------------------------------------------------------------------

    private static String placementKey(MineStationPart part, MinePlace place) {
        return part.id() + "|" + place.dimension() + "|" + place.pos().asLong();
    }

    /** 某个摆放处位置上那个盒子里有什么（没记过就是空 map）。 */
    public Map<String, Integer> placementBox(MineStationPart part, MinePlace place) {
        return placementBoxes.getOrDefault(placementKey(part, place), Map.of());
    }

    /** 记下某个摆放处位置上那个盒子里有什么（每次从这些盒子拿 / 放之后调）。 */
    public void recordPlacementBox(MineStationPart part, MinePlace place, List<ItemStack> contents) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), Integer::sum);
        }
        placementBoxes.put(placementKey(part, place), counts);
    }

    /** 盒子被拿走了（这个位置又空了）。 */
    public void clearPlacementBox(MineStationPart part, MinePlace place) {
        placementBoxes.remove(placementKey(part, place));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static int intOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
    }

    /** 一行汇总（日志 / 聊天栏用）。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (MineStationPart part : MineStationPart.values()) {
            int containerCount = containers.getOrDefault(part, List.of()).size();
            int placementCount = placements.getOrDefault(part, List.of()).size();
            int count = containerCount + placementCount;
            if (count == 0) continue;
            if (text.length() > 0) text.append("，");
            text.append(part.display()).append(' ').append(count);
        }
        return text.length() == 0 ? "（空）" : text.toString();
    }

}
