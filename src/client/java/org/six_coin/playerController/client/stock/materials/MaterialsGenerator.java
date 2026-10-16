package org.six_coin.playerController.client.stock.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /pc stock task <名字> generate_materials_list}：把投影导出的 {@code original.json}
 * 处理成备货用的 {@code materials.json}。
 *
 * <ol>
 *   <li>读 {@code world_<n>/stock/tasks/<名字>/original.json}；</li>
 *   <li>按 {@code replace_materials.json} 把**最外层**的物品替换掉（重复的合并、数量相加）；</li>
 *   <li>拿本地完整配方表递归追溯（见 {@link MaterialTreeBuilder}）；</li>
 *   <li>一个问题都没有才写 {@code materials.json} —— 有任何一处没定下来就整份都不写，
 *       把问题汇总出来让用户去改配置。</li>
 * </ol>
 */
public final class MaterialsGenerator {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /**
     * 结果。
     *
     * @param file 写出来的文件名；有 {@code issues} 时是 null（没写）
     */
    public record Result(List<MaterialTreeNode> roots, List<MaterialTreeBuilder.Issue> issues,
                         int originalKinds, int originalTotal, int replacedKinds,
                         @Nullable Path file) {

        public boolean ok() {
            return issues.isEmpty() && file != null;
        }

        public int nodeCount() {
            int total = 0;
            for (MaterialTreeNode root : roots) total += root.size();
            return total;
        }

        public int deepest() {
            int deepest = 0;
            for (MaterialTreeNode root : roots) deepest = Math.max(deepest, root.depth());
            return deepest;
        }
    }

    private MaterialsGenerator() {
    }

    public static Result generate(String task) throws IOException {
        StockManager manager = StockManager.get();
        Path originalFile = manager.currentOriginalFile(task);

        OriginalMaterials.Original original = OriginalMaterials.read(originalFile);
        ChatUtils.debug("original.json："
            + (original.name() == null ? "（没写名字）" : "「" + original.name() + "」")
            + " " + original.items().size() + " 种 / " + original.total() + " 个");

        MaterialsConfig config = MaterialsConfig.load();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            throw new IOException("还没有进世界，读不到本地配方表");
        }
        MaterialsRecipes index = MaterialsRecipes.build(mc);
        if (index.size() == 0) {
            throw new IOException("本地配方表是空的（没连上服务器 / 还没进世界？）");
        }

        Replaced wanted = replace(original.items(), config);

        MaterialTreeBuilder.Result built = MaterialTreeBuilder.build(wanted.items(), index, config);
        if (!built.issues().isEmpty()) {
            return new Result(built.roots(), built.issues(), original.items().size(),
                original.total(), wanted.kinds(), null);
        }

        Path file = manager.currentMaterialFile(task);
        write(file, built.roots());

        ChatUtils.debug("materials.json：" + file + "（" + wanted.items().size() + " 个最终产物，"
            + nodeCount(built.roots()) + " 个节点）");
        return new Result(built.roots(), List.of(), original.items().size(), original.total(),
            wanted.kinds(), file);
    }

    /** 替换结果：替换完的清单 + 换了多少种 + 合并掉了多少种。 */
    private record Replaced(Map<Item, Integer> items, int kinds, int merged) {
    }

    /**
     * 按 {@code replace_materials.json} 替换**最外层**的物品。
     *
     * <p>替换只做一次（不做链式），替换完撞上已有的就合并、数量相加。
     */
    private static Replaced replace(Map<Item, Integer> from, MaterialsConfig config) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        int replacedKinds = 0;

        for (Map.Entry<Item, Integer> entry : from.entrySet()) {
            Item target = config.replacement(entry.getKey());
            if (target != null) {
                replacedKinds++;
                ChatUtils.debug("替换材料：" + MaterialsConfig.itemId(entry.getKey())
                    + " -> " + MaterialsConfig.itemId(target) + " x" + entry.getValue());
            }
            result.merge(target == null ? entry.getKey() : target, entry.getValue(), Integer::sum);
        }

        int merged = from.size() - result.size();
        if (merged > 0) {
            ChatUtils.debug("替换之后合并掉了 " + merged + " 种重复的物品");
        }
        ChatUtils.debug("替换材料：改了 " + replacedKinds + " 种，最终 " + result.size() + " 种最终产物");
        return new Replaced(result, replacedKinds, merged);
    }

    private static int nodeCount(List<MaterialTreeNode> roots) {
        int total = 0;
        for (MaterialTreeNode root : roots) total += root.size();
        return total;
    }

    private static void write(Path file, List<MaterialTreeNode> roots) throws IOException {
        JsonArray array = new JsonArray();
        for (MaterialTreeNode root : roots) array.add(root.toJson());

        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(array, writer);
        }
    }
}
