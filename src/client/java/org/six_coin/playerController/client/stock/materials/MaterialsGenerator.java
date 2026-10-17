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
import java.util.ArrayList;
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
 *   <li>把 {@code blacklist.json} 里那些最终产物去掉（只去最外层，中间材料不受影响）；</li>
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
     * @param skipped 被 {@code blacklist.json} 滤掉的最终产物（只滤最外层）
     * @param file    写出来的文件名；有 {@code issues} 时是 null（没写）
     */
    public record Result(List<MaterialTreeNode> roots, List<MaterialTreeBuilder.Issue> issues,
                         int originalKinds, int originalTotal, int replacedKinds,
                         List<Item> skipped, @Nullable Path file) {

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

        // 先把能替换的替换掉，再看黑名单（黑名单认的是替换之后的物品）
        Replaced wanted = replace(original.items(), config);
        Blacklisted filtered = blacklist(wanted.items(), config);

        MaterialTreeBuilder.Result built = MaterialTreeBuilder.build(filtered.kept(), index, config);
        if (!built.issues().isEmpty()) {
            return new Result(built.roots(), built.issues(), original.items().size(),
                original.total(), wanted.kinds(), filtered.skipped(), null);
        }

        Path file = manager.currentMaterialFile(task);
        write(file, built.roots());

        ChatUtils.debug("materials.json：" + file + "（" + filtered.kept().size() + " 个最终产物，"
            + nodeCount(built.roots()) + " 个节点）");
        return new Result(built.roots(), List.of(), original.items().size(), original.total(),
            wanted.kinds(), filtered.skipped(), file);
    }

    /** 滤黑名单之后的结果。 */
    private record Blacklisted(Map<Item, Integer> kept, List<Item> skipped) {
    }

    /**
     * 把 {@code blacklist.json} 里的最终产物去掉。
     *
     * <p>只管最外层：黑名单里的东西要是成了别的产物的材料，照样会被追溯、照样进清单。
     */
    private static Blacklisted blacklist(Map<Item, Integer> from, MaterialsConfig config)
        throws IOException {
        Map<Item, Integer> kept = new LinkedHashMap<>();
        List<Item> skipped = new ArrayList<>();

        for (Map.Entry<Item, Integer> entry : from.entrySet()) {
            if (config.isBlacklisted(entry.getKey())) {
                skipped.add(entry.getKey());
                continue;
            }
            kept.put(entry.getKey(), entry.getValue());
        }

        if (!skipped.isEmpty()) {
            ChatUtils.debug("黑名单：跳过 " + skipped.size() + " 种最终产物："
                + skipped.stream().map(MaterialsConfig::itemId).toList());
        }
        if (kept.isEmpty()) {
            throw new IOException("黑名单把 " + skipped.size()
                + " 种最终产物全滤掉了，没有东西要备货（/pc materials blacklist 看看是不是写多了）");
        }
        return new Blacklisted(kept, skipped);
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
