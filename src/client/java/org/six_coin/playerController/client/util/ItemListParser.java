package org.six_coin.playerController.client.util;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 item_list 参数。
 *
 * <p>格式（刻意做得很简单）：
 * <pre>
 *   item[=数量][,item[=数量]]...
 * </pre>
 * 例如：
 * <pre>
 *   /pc container 100 64 200 stone=64,dirt=32,iron_ingot
 *   /pc container 100 64 200 minecraft:oak_log=16, minecraft:torch=64
 * </pre>
 * 省略数量时默认为 1；不带命名空间的 id 默认补 {@code minecraft:}。
 */
public final class ItemListParser {

    private ItemListParser() {
    }

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    public static List<ItemRequest> parse(String raw) throws ParseException {
        List<ItemRequest> requests = new ArrayList<>();
        if (raw == null) return requests;

        // 允许玩家在逗号后面加空格，所以先把空白全部去掉
        String cleaned = raw.replaceAll("\\s+", "");
        if (cleaned.isEmpty()) return requests;

        for (String token : cleaned.split(",")) {
            if (token.isEmpty()) continue;

            String idPart = token;
            int count = 1;

            int eq = token.indexOf('=');
            if (eq >= 0) {
                idPart = token.substring(0, eq);
                String countPart = token.substring(eq + 1);
                try {
                    count = Integer.parseInt(countPart);
                } catch (NumberFormatException e) {
                    throw new ParseException("数量不是合法整数: '" + token + "'");
                }
                if (count <= 0) {
                    throw new ParseException("数量必须大于 0: '" + token + "'");
                }
            }

            if (idPart.isEmpty()) {
                throw new ParseException("物品 id 为空: '" + token + "'");
            }
            if (!idPart.contains(":")) {
                idPart = "minecraft:" + idPart;
            }

            Identifier id = Identifier.tryParse(idPart);
            if (id == null || !Registries.ITEM.containsId(id)) {
                throw new ParseException("未知物品 id: '" + idPart + "'");
            }

            Item item = Registries.ITEM.get(id);
            if (item == Items.AIR) {
                throw new ParseException("物品 id 无效: '" + idPart + "'");
            }

            requests.add(new ItemRequest(item, id, count));
        }

        if (requests.isEmpty()) {
            throw new ParseException("物品列表为空");
        }
        return requests;
    }
}
