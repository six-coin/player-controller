package org.six_coin.playerController.client.stock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.station.StationManager;
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
import java.util.Optional;

/**
 * 备货「第一部分 阶段1」的第 3 步：把用户手写的 {@code material.json} 处理成几份文件。
 *
 * <ol>
 *   <li>{@code 1_1_material_clean.json}：只留最外层的「物品 + 数量」（也就是**完整需求量**）；</li>
 *   <li>{@code 1_2_material_optimized.json}（要**去取多少**）：拿 check.json 的 {@code item_storage} 和
 *       all_items.json 的 {@code overall} 去减：
 *       <ul>
 *         <li>仓库 + 容器里的总量都不够 → 舍弃（记一条提示）；</li>
 *         <li>仓库里就够了 → 舍弃；</li>
 *         <li>否则 → 记「还需要多少」（要的数量 − 仓库数量，也就是要去容器里取的量）；</li>
 *       </ul></li>
 *   <li>把 1_2 原样复制成 {@code final_final.json}（第一部分阶段2 照它取货）；</li>
 *   <li>{@code 1_3_material_can_access_all.json}（要**装多少**）：同样是 1_1 里「仓库 + 容器凑得齐」的，
 *       但数量是**完整需求量** —— 因为分盒的时候，仓库里本来就有的那部分也要一起装进盒子；</li>
 *   <li>把 1_3 原样复制成 {@code final_pack.json}（第一部分阶段3 照它分盒）。</li>
 * </ol>
 *
 * <p>两个清单的区别：{@code final_final} 是「还缺多少、要去拿」，{@code final_pack} 是
 * 「最后要装进盒子里多少」。仓库里已经有的东西只在 final_pack 里出现，取货的时候不会再取一遍。
 */
public final class StockMaterials {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** 处理结果，给聊天栏汇总用。 */
    public record Result(int cleanKinds,
                         int cleanTotal,
                         int optimizedKinds,
                         int optimizedTotal,
                         int skippedShort,
                         int skippedCovered,
                         List<String> shortItems,
                         int packKinds,
                         int packTotal,
                         Path cleanFile,
                         Path optimizedFile,
                         Path finalFile,
                         Path canAccessAllFile,
                         Path finalPackFile) {
    }

    private StockMaterials() {
    }

    public static Result process(String task) throws IOException {
        StockManager manager = StockManager.get();
        int world = manager.loadedWorld() >= 0 ? manager.loadedWorld()
            : org.six_coin.playerController.client.config.PlayerControllerConfig.getWorld();

        // 第一步：material.json -> 1_1_material_clean.json
        Path material = manager.currentMaterialFile(task);
        if (!Files.exists(material)) {
            throw new IOException("找不到材料清单 " + material + "（需要你自己放一份进去）");
        }
        Map<Item, Integer> clean = readMaterial(material);
        Path cleanFile = manager.currentCleanFile(task);
        writeItems(cleanFile, clean);
        ChatUtils.debug("1_1 材料清单：" + clean.size() + " 种 -> " + cleanFile);

        // 第二步：用仓库（item_storage）和容器（overall）算出还要去取的量
        Map<Item, Integer> storage = readItems(readObject(StationManager.checkFile(world), "item_storage"));
        Map<Item, Integer> overall = readItems(readObject(ContainerCacheManager.itemsFile(world), "overall"));

        Map<Item, Integer> optimized = new LinkedHashMap<>();
        List<String> shortItems = new ArrayList<>();
        int skippedShort = 0;
        int skippedCovered = 0;

        for (Map.Entry<Item, Integer> entry : clean.entrySet()) {
            Item item = entry.getKey();
            int need = entry.getValue();
            int have = storage.getOrDefault(item, 0);
            int all = overall.getOrDefault(item, 0);

            if (have + all < need) {
                skippedShort++;
                shortItems.add(id(item) + "（要 " + need + "，仓库 " + have + " + 容器 " + all + " 不够）");
                continue;
            }
            if (have >= need) {
                skippedCovered++;
                continue;
            }
            optimized.put(item, need - have);
        }

        Path optimizedFile = manager.currentOptimizedFile(task);
        writeItems(optimizedFile, optimized);
        ChatUtils.debug("1_2 需要取货：" + optimized.size() + " 种 -> " + optimizedFile);

        // 第三步：复制成 final_final.json（阶段2 取货照它）
        Path finalFile = manager.currentFinalFinalFile(task);
        Files.createDirectories(finalFile.getParent());
        Files.copy(optimizedFile, finalFile, StandardCopyOption.REPLACE_EXISTING);

        // 第四步：1_3 —— 同样是「凑得齐」的，但数量是完整需求量（分盒装的是全部，不是只装取回来的那部分）
        Map<Item, Integer> canAccessAll = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : clean.entrySet()) {
            Item item = entry.getKey();
            int need = entry.getValue();
            if (storage.getOrDefault(item, 0) + overall.getOrDefault(item, 0) < need) continue;
            canAccessAll.put(item, need);
        }
        Path canAccessAllFile = manager.currentCanAccessAllFile(task);
        writeItems(canAccessAllFile, canAccessAll);
        ChatUtils.debug("1_3 能全量装盒：" + canAccessAll.size() + " 种 -> " + canAccessAllFile);

        // 第五步：复制成 final_pack.json（阶段3 分盒照它）
        Path finalPackFile = manager.currentFinalPackFile(task);
        Files.copy(canAccessAllFile, finalPackFile, StandardCopyOption.REPLACE_EXISTING);

        int cleanTotal = 0;
        for (int count : clean.values()) cleanTotal += count;
        int optimizedTotal = 0;
        for (int count : optimized.values()) optimizedTotal += count;
        int packTotal = 0;
        for (int count : canAccessAll.values()) packTotal += count;

        return new Result(clean.size(), cleanTotal, optimized.size(), optimizedTotal,
            skippedShort, skippedCovered, shortItems, canAccessAll.size(), packTotal,
            cleanFile, optimizedFile, finalFile, canAccessAllFile, finalPackFile);
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 读 material.json 的最外层：物品 -> 数量（重复的会加在一起，嵌套的 sub_materials 不看）。 */
    private static Map<Item, Integer> readMaterial(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(raw);
        if (!root.isJsonArray()) {
            throw new IOException("material.json 最外层应该是一个数组 [ ... ]");
        }

        Map<Item, Integer> items = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            if (!object.has("item") || !object.has("count")) continue;

            Item item = parseItem(object.get("item").getAsString());
            if (item == null) continue;
            int count = object.get("count").getAsInt();
            if (count <= 0) continue;
            items.merge(item, count, Integer::sum);
        }
        return items;
    }

    /** 从某个 json 文件里取一个对象出来（比如 check.json 的 item_storage）；没有就当空的。 */
    private static JsonObject readObject(Path file, String key) {
        if (!Files.exists(file)) {
            ChatUtils.debug("没有 " + file + "，这一项按空的算");
            return new JsonObject();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return new JsonObject();
            JsonElement element = root.getAsJsonObject().get(key);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            ChatUtils.error("读 " + file + " 失败：" + e.getMessage());
            return new JsonObject();
        }
    }

    private static Map<Item, Integer> readItems(JsonObject object) {
        Map<Item, Integer> items = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            Item item = parseItem(entry.getKey());
            if (item == null) continue;
            try {
                int count = entry.getValue().getAsInt();
                if (count > 0) items.merge(item, count, Integer::sum);
            } catch (Exception e) {
                ChatUtils.debug("跳过 " + entry.getKey() + "：" + e.getMessage());
            }
        }
        return items;
    }

    /** 解析一个物品 id（带不带 minecraft: 都行）；认不出来返回 null。 */
    @Nullable
    public static Item parseItem(String raw) {
        Identifier identifier = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (identifier == null) {
            ChatUtils.error("认不出的物品 id：" + raw);
            return null;
        }
        Optional<Item> item = Registries.ITEM.getOptionalValue(identifier);
        if (item.isEmpty() || item.get() == Items.AIR) {
            ChatUtils.error("没有这个物品：" + raw);
            return null;
        }
        return item.get();
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 写成一个 item_list（顺序就是传进来的顺序，数量 ≤ 0 的丢掉）。 */
    private static void writeItems(Path file, Map<Item, Integer> items) throws IOException {
        JsonObject object = new JsonObject();
        for (Map.Entry<Item, Integer> entry : items.entrySet()) {
            if (entry.getValue() <= 0) continue;
            object.addProperty(id(entry.getKey()), entry.getValue());
        }

        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(object, writer);
        }
    }

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
