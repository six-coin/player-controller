package org.six_coin.playerController.client.util;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 一条“物品 + 数量”的请求。
 *
 * @param item   物品
 * @param id     物品 id（只用于展示）
 * @param count  需要的数量
 */
public record ItemRequest(Item item, Identifier id, int count) {

    public String displayName() {
        return id.toString() + " x" + count;
    }
}
