package org.six_coin.playerController.client.stock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备货「第二部分 阶段1」的第 3 步：把用户手写的 {@code material.json}（跟第一部分读的是同一份）
 * 再处理一遍，算出**这次要合成哪些最终产物、要去取哪些原材料**（附件 2.1.3 的那六步）。
 *
 * <pre>
 * 提前处理：把第一部分阶段3 交过来的 station_data 反推成 check.json 里 item_storage 那种
 *          最简单的 item_list，记作 current_item_storage（只在这里用，用完就扔）
 * 第一步：material.json 里去掉 final_final 已经收过的最终产物 → 2_1_process_raw.json
 * 第二步：把 2_1 里所有「原材料」（recipe_type = base）摊平成 item_list → 2_2_material_raw.json
 * 第三步：all_items 的 overall + current_item_storage 凑得齐的才留 → 2_3_material_clean.json
 * 第四步：2_1 里「用到的材料有一个不在 2_3 里」的最终产物整个删掉 → 2_4_process_clean.json
 * 第五步：把 2_4 里所有原材料摊平成 item_list → 2_5_material_cleaner.json
 * 第六步：2_5 复制成 final_material.json、2_4 复制成 final_process.json
 * </pre>
 *
 * <p>这么一套下来，留下的只有「能够全量合成」的最终产物。不一定是最优解，但够用（需求原话）。
 */
public final class StockProcessMaterials {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** 处理结果，给聊天栏汇总用。 */
    public record Result(int rawProducts,
                         int keptProducts,
                         int rawMaterialKinds,
                         int rawMaterialTotal,
                         int cleanMaterialKinds,
                         int shortMaterialKinds,
                         List<String> shortItems,
                         List<String> droppedProducts,
                         int finalMaterialKinds,
                         int finalMaterialTotal,
                         Path processRawFile,
                         Path materialRawFile,
                         Path materialCleanFile,
                         Path processCleanFile,
                         Path materialCleanerFile,
                         Path finalMaterialFile,
                         Path finalProcessFile) {
    }

    /**
     * 处理要用到的那些文件。
     *
     * <p>正常跑的时候用 {@link #filesFor(String)} 从 {@link StockManager} 拼出来；
     * 单独测这套逻辑的时候可以直接给一组临时路径。
     */
    public record StockFiles(Path material,
                             Path finalFinal,
                             Path allItems,
                             Path processRaw,
                             Path materialRaw,
                             Path materialClean,
                             Path processClean,
                             Path materialCleaner,
                             Path finalMaterial,
                             Path finalProcess) {
    }

    /** 一个备货任务对应的那些文件。 */
    public static StockFiles filesFor(String task) {
        StockManager manager = StockManager.get();
        int world = manager.loadedWorld() >= 0 ? manager.loadedWorld()
            : org.six_coin.playerController.client.config.PlayerControllerConfig.getWorld();

        return new StockFiles(
            manager.currentMaterialFile(task),
            manager.currentFinalFinalFile(task),
            ContainerCacheManager.itemsFile(world),
            manager.currentProcessRawFile(task),
            manager.currentMaterialRawFile(task),
            manager.currentMaterialCleanFile(task),
            manager.currentProcessCleanFile(task),
            manager.currentMaterialCleanerFile(task),
            manager.currentFinalMaterialFile(task),
            manager.currentFinalProcessFile(task));
    }

    private StockProcessMaterials() {
    }

    /**
     * @param currentItemStorage 第一部分阶段3 交过来的 station_data 反推出来的「仓库里有什么」
     */
    public static Result process(StockFiles files, Map<String, Integer> currentItemStorage) throws IOException {
        // 提前处理：反推仓库（这一步只用来做第三步的判断）
        ChatUtils.debug("第二部分 current_item_storage：" + currentItemStorage.size() + " 种");

        // material.json
        if (!Files.exists(files.material())) {
            throw new IOException("找不到材料清单 " + files.material() + "（需要你自己放一份进去）");
        }
        JsonArray tree = readTree(files.material());

        // final_final（第一部分收过的最终产物）
        Map<String, Integer> collected = readItemList(files.finalFinal(), null);
        ChatUtils.debug("第二部分：final_final 里有 " + collected.size() + " 种已经收过的最终产物");

        // 第一步：去掉 final_final 已经收过的
        JsonArray raw = new JsonArray();
        List<String> droppedAtStep1 = new ArrayList<>();
        for (JsonElement element : tree) {
            String id = itemOf(element);
            if (id != null && collected.containsKey(id)) {
                droppedAtStep1.add(id);
                continue;
            }
            raw.add(element);
        }
        writeTree(files.processRaw(), raw);
        ChatUtils.debug("2_1 去掉 final_final 收过的 " + droppedAtStep1.size() + " 个最终产物："
            + describeList(droppedAtStep1) + " -> " + files.processRaw());

        // 第二步：摊平成原材料
        Map<String, Integer> materialRaw = new LinkedHashMap<>();
        collectBaseMaterials(raw, materialRaw);
        writeItemList(files.materialRaw(), materialRaw);
        ChatUtils.debug("2_2 原材料 " + materialRaw.size() + " 种 -> " + files.materialRaw());

        // 第三步：仓库 + 容器凑得齐的才留
        Map<String, Integer> overall = readItemList(files.allItems(), "overall");
        Map<String, Integer> materialClean = new LinkedHashMap<>();
        List<String> shortItems = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : materialRaw.entrySet()) {
            int need = entry.getValue();
            int haveStorage = currentItemStorage.getOrDefault(entry.getKey(), 0);
            int haveContainers = overall.getOrDefault(entry.getKey(), 0);
            if (haveStorage + haveContainers < need) {
                shortItems.add(entry.getKey() + "（要 " + need + "，仓库 " + haveStorage
                    + " + 容器 " + haveContainers + " 不够）");
                continue;
            }
            materialClean.put(entry.getKey(), need);
        }
        writeItemList(files.materialClean(), materialClean);
        ChatUtils.debug("2_3 凑得齐的原材料 " + materialClean.size() + "/" + materialRaw.size()
            + " 种 -> " + files.materialClean());

        // 第四步：用到的材料有一个不在 2_3 里的最终产物，整个删掉
        JsonArray clean = new JsonArray();
        List<String> droppedProducts = new ArrayList<>();
        for (JsonElement element : raw) {
            String missing = missingMaterial(element, materialClean);
            if (missing != null) {
                droppedProducts.add((itemOf(element) == null ? "?" : itemOf(element))
                    + "（缺 " + missing + "）");
                continue;
            }
            clean.add(element);
        }
        writeTree(files.processClean(), clean);
        ChatUtils.debug("2_4 能全量合成的最终产物 " + clean.size() + "/" + raw.size()
            + " 个 -> " + files.processClean());

        // 第五步：2_4 的原材料摊平
        Map<String, Integer> materialCleaner = new LinkedHashMap<>();
        collectBaseMaterials(clean, materialCleaner);
        writeItemList(files.materialCleaner(), materialCleaner);
        ChatUtils.debug("2_5 原材料 " + materialCleaner.size() + " 种 -> " + files.materialCleaner());

        // 第六步：复制
        Path finalMaterialFile = files.finalMaterial();
        Path finalProcessFile = files.finalProcess();
        Files.createDirectories(finalMaterialFile.getParent());
        Files.copy(files.materialCleaner(), finalMaterialFile, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(files.processClean(), finalProcessFile, StandardCopyOption.REPLACE_EXISTING);

        int rawTotal = 0;
        for (int count : materialRaw.values()) rawTotal += count;
        int cleanerTotal = 0;
        for (int count : materialCleaner.values()) cleanerTotal += count;

        return new Result(raw.size(), clean.size(),
            materialRaw.size(), rawTotal, materialClean.size(), shortItems.size(),
            shortItems, droppedProducts, materialCleaner.size(), cleanerTotal,
            files.processRaw(), files.materialRaw(), files.materialClean(), files.processClean(),
            files.materialCleaner(), finalMaterialFile, finalProcessFile);
    }

    // ------------------------------------------------------------------
    // 树的操作
    // ------------------------------------------------------------------

    /** 读 material.json（最外层必须是数组，原样保留每一棵树）。 */
    private static JsonArray readTree(Path file) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!root.isJsonArray()) {
            throw new IOException(file.getFileName() + " 最外层应该是一个数组 [ ... ]");
        }
        return root.getAsJsonArray();
    }

    /**
     * 把一棵棵树里所有「原材料」（{@code recipe_type = "base"}）摊平累加。
     *
     * <p>base 当叶子看：它自己算一份，不再往里递归（正常格式里它的 sub_materials 本来就是空的）。
     */
    private static void collectBaseMaterials(JsonArray trees, Map<String, Integer> out) {
        for (JsonElement element : trees) {
            collectBaseMaterials(element, out);
        }
    }

    private static void collectBaseMaterials(JsonElement element, Map<String, Integer> out) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();

        if (isBase(object)) {
            String id = canonicalId(object);
            if (id == null) return;
            int count = countOf(object);
            if (count <= 0) return;
            out.merge(id, count, Integer::sum);
            return;
        }

        JsonElement subs = object.get("sub_materials");
        if (subs == null || !subs.isJsonArray()) return;
        for (JsonElement sub : subs.getAsJsonArray()) {
            collectBaseMaterials(sub, out);
        }
    }

    /**
     * 这棵最终产物树有没有用到「不在 keep 里的原材料」。
     *
     * @return 用到的那种材料的 id；都没问题返回 null
     */
    @Nullable
    private static String missingMaterial(JsonElement element, Map<String, Integer> keep) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();

        if (isBase(object)) {
            String id = canonicalId(object);
            if (id == null) return "认不出的物品";
            return keep.containsKey(id) ? null : id;
        }

        JsonElement subs = object.get("sub_materials");
        if (subs == null || !subs.isJsonArray()) return null;
        for (JsonElement sub : subs.getAsJsonArray()) {
            String missing = missingMaterial(sub, keep);
            if (missing != null) return missing;
        }
        return null;
    }

    private static boolean isBase(JsonObject object) {
        JsonElement type = object.get("recipe_type");
        return type != null && type.isJsonPrimitive() && "base".equals(type.getAsString());
    }

    /** 这一棵树的物品 id（规范化过）；连 id 字段都没有就返回 null。 */
    @Nullable
    private static String canonicalId(JsonObject object) {
        JsonElement item = object.get("item");
        if (item == null || !item.isJsonPrimitive()) return null;
        return canonicalId(item.getAsString());
    }

    /** 这一棵树自己那个物品的 id（规范化过）；日志和「收过没有」判断用。 */
    @Nullable
    private static String itemOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();

        JsonElement item = object.get("item");
        if (item == null || !item.isJsonPrimitive()) return null;
        return canonicalId(item.getAsString());
    }

    private static int countOf(JsonObject object) {
        JsonElement count = object.get("count");
        if (count == null) return 0;
        try {
            return count.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /** 读一个 item_list 文件（{@code {"minecraft:mud": 56}} 整个文件就是一个 item_list）；没有 / 读不了就返回空表。 */
    private static Map<String, Integer> readItemList(Path file) {
        return readItemList(file, null);
    }

    /**
     * 读文件里某个键下面的 item_list（比如 all_items.json 的 {@code overall}）。
     *
     * @param key null 表示整个文件就是一个 item_list
     */
    private static Map<String, Integer> readItemList(Path file, @Nullable String key) {
        Map<String, Integer> items = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            ChatUtils.debug("没有 " + file + "，这一项按空的算");
            return items;
        }

        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return items;

            JsonObject object = root.getAsJsonObject();
            if (key != null) {
                JsonElement nested = object.get(key);
                if (nested == null || !nested.isJsonObject()) {
                    ChatUtils.debug(file + " 里没有 " + key + "，这一项按空的算");
                    return items;
                }
                object = nested.getAsJsonObject();
            }

            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String id = canonicalId(entry.getKey());
                if (id == null) continue;
                try {
                    int count = entry.getValue().getAsInt();
                    if (count > 0) items.merge(id, count, Integer::sum);
                } catch (Exception e) {
                    ChatUtils.debug("跳过 " + entry.getKey() + "：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            ChatUtils.error("读 " + file + " 失败：" + e.getMessage());
        }
        return items;
    }

    /**
     * 把一个物品 id 规范化：没有命名空间的补上 {@code minecraft:}，再顺手查一下注册表
     * （查得到就用注册表里的正式写法；认不出来的物品会打一行错误，但**不丢弃** ——
     * 让它进到第三步，以「凑不齐」的形式出现在日志和产物文件里，比悄悄消失好）。
     */
    private static String canonicalId(String raw) {
        String id = raw.trim();
        if (id.isEmpty()) return id;
        if (!id.contains(":")) id = "minecraft:" + id;

        try {
            Item parsed = StockMaterials.parseItem(id);
            if (parsed != null) return Registries.ITEM.getId(parsed).toString();
        } catch (Throwable ignored) {
            // 注册表没 bootstrap（离线测这套逻辑）就只按字符串规范化
        }
        return id;
    }

    /** 写一个 item_list。 */
    private static void writeItemList(Path file, Map<String, Integer> items) throws IOException {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            object.addProperty(entry.getKey(), entry.getValue());
        }

        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(object, writer);
        }
    }

    /** 写一棵棵树（原样保留结构）。 */
    private static void writeTree(Path file, JsonArray tree) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(tree, writer);
        }
    }

    /** 日志用：最多列几个名字。 */
    private static String describeList(List<String> list) {
        if (list.isEmpty()) return "（没有）";
        int max = 6;
        if (list.size() <= max) return String.join("、", list);
        return String.join("、", list.subList(0, max)) + " 等 " + list.size() + " 个";
    }
}
