package org.six_coin.playerController.client.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * item_list：一个 JSON 对象，键是物品 id，值是还差多少个。
 *
 * <pre>
 *   {"minecraft:cobblestone": 65, "minecraft:grass_block": 13}
 * </pre>
 *
 * <p>玩家写的键会原样保留（顺序也保留），{@link #toJson()} 输出的时候还是这些键，
 * 只是把已经拿到的数量扣掉了。没有命名空间的键（比如 {@code cobblestone}）按
 * {@code minecraft:} 补全去查物品，但输出时仍然用玩家写的那个键。
 *
 * <p>扣减规则：从前往后扣，每个键最多扣到 0，不会扣成负数。
 */
public final class ItemList {

    /** 解析失败。 */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    /** 玩家写的键（保持顺序）。 */
    private final List<String> keys = new ArrayList<>();
    /** 键 -> 还差多少个。 */
    private final Map<String, Integer> remaining = new LinkedHashMap<>();
    /** 键 -> 物品。 */
    private final Map<String, Item> itemOfKey = new LinkedHashMap<>();
    /** 物品 -> 涉及它的键（同一件物品被写了多个键时按顺序都记下来）。 */
    private final Map<Item, List<String>> keysOfItem = new LinkedHashMap<>();

    private ItemList() {
    }

    public static ItemList parse(String raw) throws ParseException {
        if (raw == null || raw.isBlank()) {
            throw new ParseException("item_list 是空的");
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (Exception e) {
            throw new ParseException("不是合法 JSON：" + e.getMessage());
        }
        if (!root.isJsonObject()) {
            throw new ParseException("item_list 必须是一个 JSON 对象，例如 "
                + "{\"minecraft:cobblestone\": 65}");
        }

        ItemList list = new ItemList();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new ParseException(key + " 的数量必须是整数");
            }

            int count;
            try {
                count = Integer.parseInt(value.getAsString().trim());
            } catch (NumberFormatException e) {
                throw new ParseException(key + " 的数量必须是整数（现在是 "
                    + value.getAsString() + "）");
            }
            if (count <= 0) {
                throw new ParseException(key + " 的数量必须大于 0（现在是 " + count + "）");
            }

            Item item = resolve(key);
            list.keys.add(key);
            list.remaining.put(key, count);
            list.itemOfKey.put(key, item);
            list.keysOfItem.computeIfAbsent(item, k -> new ArrayList<>()).add(key);
        }

        if (list.keys.isEmpty()) {
            throw new ParseException("item_list 里一个物品都没有");
        }
        return list;
    }

    /** 查物品：没有命名空间的补 minecraft:。 */
    private static Item resolve(String key) throws ParseException {
        String idText = key.contains(":") ? key : "minecraft:" + key;
        Identifier id = Identifier.tryParse(idText);
        if (id == null || !Registries.ITEM.containsId(id)) {
            throw new ParseException("未知物品 id: " + key);
        }
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) {
            throw new ParseException("物品 id 无效: " + key);
        }
        return item;
    }

    // ------------------------------------------------------------------

    /** 这种物品还需要多少个（同一件物品写了多个键时会加起来）。 */
    public int remaining(Item item) {
        int total = 0;
        for (String key : keysOfItem.getOrDefault(item, List.of())) {
            total += remaining.getOrDefault(key, 0);
        }
        return total;
    }

    public boolean wants(Item item) {
        return remaining(item) > 0;
    }

    /**
     * 扣掉已经拿到手的数量：从前往后扣，每个键最多扣到 0。
     *
     * @return 实际扣掉的数量
     */
    public int take(Item item, int amount) {
        int left = amount;
        for (String key : keysOfItem.getOrDefault(item, List.of())) {
            if (left <= 0) break;
            int have = remaining.getOrDefault(key, 0);
            int cut = Math.min(have, left);
            if (cut <= 0) continue;
            remaining.put(key, have - cut);
            left -= cut;
        }
        return amount - left;
    }

    /** 是不是所有东西都拿够了。 */
    public boolean isEmpty() {
        for (int value : remaining.values()) {
            if (value > 0) return false;
        }
        return true;
    }

    /** 修改后的 item_list JSON 文本（键的顺序和玩家写的一样）。 */
    public String toJson() {
        JsonObject object = new JsonObject();
        for (String key : keys) {
            object.addProperty(key, remaining.getOrDefault(key, 0));
        }
        return object.toString();
    }

    /** 给日志 / 提示用的一行文字。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(key).append(" x").append(remaining.getOrDefault(key, 0));
        }
        return sb.toString();
    }
}
