package org.six_coin.playerController.client.stock.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 材料清单生成要用的那五份配置，放在 {@code config/player-controller/materials/}：
 *
 * <pre>
 * terminal_items.json        ["minecraft:iron_block", ...]        到此为止，不再往里追溯的物品
 * recipe_preferences.json    {"minecraft:mossy_cobblestone": "minecraft:mossy_cobblestone_from_moss_block"}
 *                                                                 一种产物有多种合成方式时用哪一条配方
 * tag_preferences.json       {"#minecraft:planks": "minecraft:oak_planks"}
 *                                                                 配方里写的是标签时用标签里的哪个物品
 * ingredient_preferences.json{"minecraft:torch#0": "minecraft:coal"}
 *                                                                 配方里写的是「一串可选的物品」时用哪一个
 * replace_materials.json     {"minecraft:stripped_oak_log": "minecraft:oak_log"}
 *                                                                 能用什么替代什么（只作用于投影导出那一层）
 * </pre>
 *
 * <p>配方名一律写成**完整的配方 id**（{@code minecraft:mossy_cobblestone_from_moss_block}）；
 * 手写的时候带不带 {@code minecraft:}、带不带 {@code .json} 都认，写回去的时候统一成完整 id。
 *
 * <p>标签一律写成 {@code #minecraft:planks}。
 *
 * <p>「一串可选的物品」那种格子的键是 {@code <配方 id>#<第几处>}：一处 = 同一组候选物品，
 * 所以一个配方里同一组候选物品不管重复用了几格，都只算一处。
 *
 * <p>每次用的时候都**重新读一遍盘**（{@link #load()}）：这几个文件很小，而且改完配置马上重跑
 * 是常态，缓存住反而会让人以为改了没用。改配置的命令是先 {@link #load()}、改、再 {@link #save()}。
 */
public final class MaterialsConfig {

    /** 这五份配置所在的文件夹：{@code config/player-controller/materials/}。 */
    public static final String FOLDER_NAME = "materials";

    private static final String TERMINAL_FILE = "terminal_items.json";
    private static final String RECIPE_FILE = "recipe_preferences.json";
    private static final String TAG_FILE = "tag_preferences.json";
    private static final String INGREDIENT_FILE = "ingredient_preferences.json";
    private static final String REPLACE_FILE = "replace_materials.json";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** 「到此为止」的物品（规范化过的 id）。 */
    private final Set<String> terminalItems = new LinkedHashSet<>();

    /** 产物 id -> 用哪条配方。 */
    private final Map<String, String> recipePreferences = new LinkedHashMap<>();

    /** {@code #标签 id} -> 用标签里的哪个物品。 */
    private final Map<String, String> tagPreferences = new LinkedHashMap<>();

    /** {@code <配方 id>#<第几处>} -> 这一处用哪个物品。 */
    private final Map<String, String> ingredientPreferences = new LinkedHashMap<>();

    /** 被替换的物品 id -> 换成什么。 */
    private final Map<String, String> replaceMaterials = new LinkedHashMap<>();

    private MaterialsConfig() {
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    public static Path dir() {
        return PlayerControllerConfig.configDir().resolve(FOLDER_NAME);
    }

    public static Path terminalFile() {
        return dir().resolve(TERMINAL_FILE);
    }

    public static Path recipeFile() {
        return dir().resolve(RECIPE_FILE);
    }

    public static Path tagFile() {
        return dir().resolve(TAG_FILE);
    }

    public static Path ingredientFile() {
        return dir().resolve(INGREDIENT_FILE);
    }

    public static Path replaceFile() {
        return dir().resolve(REPLACE_FILE);
    }

    // ------------------------------------------------------------------
    // 读 / 写
    // ------------------------------------------------------------------

    /** 重新读一遍盘；缺的文件会建出来（空表）。 */
    public static MaterialsConfig load() {
        MaterialsConfig config = new MaterialsConfig();

        for (String id : readArray(terminalFile())) {
            String canonical = canonicalItem(id);
            if (canonical == null) {
                ChatUtils.error("terminal_items.json 里认不出的物品 id：" + id);
                continue;
            }
            config.terminalItems.add(canonical);
        }

        readMap(recipeFile(), (key, value) -> {
            String item = canonicalItem(key);
            String recipe = canonicalRecipe(value);
            if (item == null || recipe == null) {
                ChatUtils.error("recipe_preferences.json 里认不出的一行：" + key + " -> " + value);
                return;
            }
            config.recipePreferences.put(item, recipe);
        });

        readMap(tagFile(), (key, value) -> {
            String tag = canonicalTag(key);
            String item = canonicalItem(value);
            if (tag == null || item == null) {
                ChatUtils.error("tag_preferences.json 里认不出的一行：" + key + " -> " + value);
                return;
            }
            config.tagPreferences.put(tag, item);
        });

        readMap(ingredientFile(), (key, value) -> {
            String slot = canonicalIngredientKey(key);
            String item = canonicalItem(value);
            if (slot == null || item == null) {
                ChatUtils.error("ingredient_preferences.json 里认不出的一行：" + key + " -> " + value);
                return;
            }
            config.ingredientPreferences.put(slot, item);
        });

        readMap(replaceFile(), (key, value) -> {
            String from = canonicalItem(key);
            String to = canonicalItem(value);
            if (from == null || to == null) {
                ChatUtils.error("replace_materials.json 里认不出的一行：" + key + " -> " + value);
                return;
            }
            config.replaceMaterials.put(from, to);
        });

        config.warnUnknown();
        return config;
    }

    /** 五份配置全写一遍（目录不存在会自动创建）。 */
    public void save() {
        writeArray(terminalFile(), terminalItems);
        writeMap(recipeFile(), recipePreferences);
        writeMap(tagFile(), tagPreferences);
        writeMap(ingredientFile(), ingredientPreferences);
        writeMap(replaceFile(), replaceMaterials);
    }

    /** 认不出的物品 / 标签统一提醒一次，免得散在各处。 */
    private void warnUnknown() {
        Set<String> unknown = new TreeSet<>();
        for (String id : terminalItems) {
            if (itemOf(id) == null) unknown.add(id);
        }
        for (Map.Entry<String, String> entry : recipePreferences.entrySet()) {
            if (itemOf(entry.getKey()) == null) unknown.add(entry.getKey());
        }
        for (Map.Entry<String, String> entry : tagPreferences.entrySet()) {
            if (itemOf(entry.getValue()) == null) unknown.add(entry.getValue());
        }
        for (Map.Entry<String, String> entry : ingredientPreferences.entrySet()) {
            if (itemOf(entry.getValue()) == null) unknown.add(entry.getValue());
        }
        for (Map.Entry<String, String> entry : replaceMaterials.entrySet()) {
            if (itemOf(entry.getKey()) == null) unknown.add(entry.getKey());
            if (itemOf(entry.getValue()) == null) unknown.add(entry.getValue());
        }
        if (!unknown.isEmpty()) {
            ChatUtils.debug("材料配置里有 " + unknown.size() + " 个现在认不出的物品 id（版本不一样？）："
                + String.join("、", unknown.size() > 8 ? unknown.stream().limit(8).toList() : unknown));
        }
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    /** 这个物品是不是「到此为止」。 */
    public boolean isTerminal(Item item) {
        return terminalItems.contains(itemId(item));
    }

    public Set<String> terminalItems() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(terminalItems));
    }

    /** 这个产物偏好用哪条配方（配方 id）；没设过返回 null。 */
    @Nullable
    public Identifier recipePreference(Item result) {
        String raw = recipePreferences.get(itemId(result));
        return raw == null ? null : Identifier.tryParse(raw);
    }

    public Map<String, String> recipePreferences() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(recipePreferences));
    }

    /** 这个标签用哪个物品；没设过返回 null。 */
    @Nullable
    public Item tagPreference(TagKey<Item> tag) {
        return itemOf(tagPreferences.get(tagId(tag)));
    }

    public Map<String, String> tagPreferences() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tagPreferences));
    }

    /** 这一处「可选的物品」用哪个；没设过返回 null。 */
    @Nullable
    public Item ingredientPreference(String slotKey) {
        return itemOf(ingredientPreferences.get(slotKey));
    }

    public Map<String, String> ingredientPreferences() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ingredientPreferences));
    }

    /** 这个东西可以直接换成什么；没设过返回 null。 */
    @Nullable
    public Item replacement(Item item) {
        return itemOf(replaceMaterials.get(itemId(item)));
    }

    public Map<String, String> replaceMaterials() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(replaceMaterials));
    }

    // ------------------------------------------------------------------
    // 改（改完调用方自己 save()）
    // ------------------------------------------------------------------

    public boolean addTerminal(Item item) {
        return terminalItems.add(itemId(item));
    }

    public boolean delTerminal(Item item) {
        return terminalItems.remove(itemId(item));
    }

    public boolean addRecipePreference(Item result, Identifier recipe) {
        String before = recipePreferences.put(itemId(result), recipe.toString());
        return !recipe.toString().equals(before);
    }

    public boolean delRecipePreference(Item result) {
        return recipePreferences.remove(itemId(result)) != null;
    }

    public boolean addTagPreference(TagKey<Item> tag, Item item) {
        String before = tagPreferences.put(tagId(tag), itemId(item));
        return !itemId(item).equals(before);
    }

    public boolean delTagPreference(TagKey<Item> tag) {
        return tagPreferences.remove(tagId(tag)) != null;
    }

    public boolean addIngredientPreference(String slotKey, Item item) {
        String before = ingredientPreferences.put(slotKey, itemId(item));
        return !itemId(item).equals(before);
    }

    public boolean delIngredientPreference(String slotKey) {
        return ingredientPreferences.remove(slotKey) != null;
    }

    public boolean addReplaceMaterial(Item replaced, Item replacement) {
        String before = replaceMaterials.put(itemId(replaced), itemId(replacement));
        return !itemId(replacement).equals(before);
    }

    public boolean delReplaceMaterial(Item replaced) {
        return replaceMaterials.remove(itemId(replaced)) != null;
    }

    // ------------------------------------------------------------------
    // id 的写法
    // ------------------------------------------------------------------

    public static String itemId(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    /** {@code #minecraft:planks} 这种写法。 */
    public static String tagId(TagKey<Item> tag) {
        return "#" + tag.id();
    }

    /** 把 {@code oak_planks} / {@code minecraft:oak_planks} 都认出来（认不出返回 null）。 */
    @Nullable
    public static Item itemOf(@Nullable String raw) {
        if (raw == null) return null;
        Identifier identifier = Identifier.tryParse(withNamespace(raw));
        if (identifier == null) return null;
        Optional<Item> item = Registries.ITEM.getOptionalValue(identifier);
        return item.isEmpty() || item.get() == Items.AIR ? null : item.get();
    }

    /** 物品 id 规范化：补命名空间、能查到就用注册表里的正式写法；认不出来就补齐命名空间原样返回。 */
    @Nullable
    public static String canonicalItem(@Nullable String raw) {
        if (raw == null) return null;
        String id = withNamespace(raw.trim());
        if (id.isEmpty()) return null;

        Item item = itemOf(id);
        return item == null ? id : itemId(item);
    }

    /**
     * 配方 id 规范化：补命名空间，顺手容忍结尾的 {@code .json}
     * （litematica 导出的名字 / 数据包里的文件名都带它）。
     */
    @Nullable
    public static String canonicalRecipe(@Nullable String raw) {
        if (raw == null) return null;
        String id = raw.trim();
        if (id.isEmpty()) return null;
        if (id.length() > 5 && id.regionMatches(true, id.length() - 5, ".json", 0, 5)) {
            id = id.substring(0, id.length() - 5);
        }
        id = withNamespace(id);
        return id.isEmpty() ? null : id;
    }

    /** 标签写法规范化：一定是 {@code #namespace:path}。 */
    @Nullable
    public static String canonicalTag(@Nullable String raw) {
        if (raw == null) return null;
        String id = raw.trim();
        if (id.startsWith("#")) id = id.substring(1);
        id = withNamespace(id);
        return id.isEmpty() ? null : "#" + id;
    }

    /** {@code minecraft:torch#0} 这种「配方 + 第几处」的键；认不出返回 null。 */
    @Nullable
    public static String canonicalIngredientKey(@Nullable String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        int at = key.lastIndexOf('#');
        if (at <= 0) return null;

        String recipe = canonicalRecipe(key.substring(0, at));
        if (recipe == null) return null;
        try {
            int index = Integer.parseInt(key.substring(at + 1).trim());
            if (index < 0) return null;
            return recipe + "#" + index;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 {@code minecraft:torch#0} 里拆出配方 id（{@code minecraft:torch}）；
     * 键不合法返回 null。
     */
    @Nullable
    public static Identifier recipeOfSlotKey(String slotKey) {
        String canonical = canonicalIngredientKey(slotKey);
        if (canonical == null) return null;
        return Identifier.tryParse(canonical.substring(0, canonical.lastIndexOf('#')));
    }

    /** {@code #minecraft:planks} -> 物品标签。 */
    @Nullable
    public static TagKey<Item> tagOf(@Nullable String raw) {
        String canonical = canonicalTag(raw);
        if (canonical == null) return null;
        Identifier identifier = Identifier.tryParse(canonical.substring(1));
        return identifier == null ? null : TagKey.of(net.minecraft.registry.RegistryKeys.ITEM, identifier);
    }

    /** 没有命名空间就补 {@code minecraft:}。 */
    private static String withNamespace(String raw) {
        String id = raw.trim();
        if (id.isEmpty()) return id;
        return id.contains(":") ? id : "minecraft:" + id;
    }

    // ------------------------------------------------------------------
    // 文件读写
    // ------------------------------------------------------------------

    private interface EntryConsumer {
        void accept(String key, String value);
    }

    /** 读一个「物品 id -> 值」的对象；文件不在就建一个空的。 */
    private static void readMap(Path path, EntryConsumer consumer) {
        if (!Files.exists(path)) {
            writeMap(path, new LinkedHashMap<>());
            ChatUtils.debug("已创建空的材料配置：" + path);
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                ChatUtils.error(path.getFileName() + " 最外层应该是一个对象 { ... }，这一项按空的算");
                return;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive()) continue;
                consumer.accept(entry.getKey(), value.getAsString());
            }
        } catch (Exception e) {
            ChatUtils.error("读 " + path + " 失败：" + e.getMessage());
        }
    }

    /** 读一个物品 id 的数组。 */
    private static java.util.List<String> readArray(Path path) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (!Files.exists(path)) {
            writeArray(path, new LinkedHashSet<>());
            ChatUtils.debug("已创建空的材料配置：" + path);
            return ids;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                ChatUtils.error(path.getFileName() + " 最外层应该是一个数组 [ ... ]，这一项按空的算");
                return ids;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (element == null || !element.isJsonPrimitive()) continue;
                ids.add(element.getAsString());
            }
        } catch (Exception e) {
            ChatUtils.error("读 " + path + " 失败：" + e.getMessage());
        }
        return ids;
    }

    private static void writeMap(Path path, Map<String, String> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        write(path, object);
    }

    private static void writeArray(Path path, Set<String> ids) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (String id : ids) array.add(id);
        write(path, array);
    }

    private static void write(Path path, JsonElement element) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(element, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("写 " + path + " 失败：" + e.getMessage());
        }
    }
}
