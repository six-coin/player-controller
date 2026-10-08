package org.six_coin.playerController.client.container;

import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.TreeMap;

/** 缓存里的一个容器。 */
public final class CachedContainer {

    private int id;
    /** 方块 id，比如 minecraft:chest。 */
    private String type;
    private String dimension;
    private int x;
    private int y;
    private int z;
    /** 物品 id -> 数量（按 id 排好序，存的是箱子里的东西 + 潜影盒里的东西）。 */
    private Map<String, Integer> items = new TreeMap<>();

    /** 给 Gson 用。 */
    private CachedContainer() {
    }

    CachedContainer(int id, String type, String dimension, BlockPos pos, Map<String, Integer> items) {
        this.id = id;
        this.type = type;
        this.dimension = dimension;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.items = new TreeMap<>(items);
    }

    public int id() {
        return id;
    }

    /** optimize / 重新编号时用。 */
    void id(int id) {
        this.id = id;
    }

    public String type() {
        return type;
    }

    public String dimension() {
        return dimension;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public String coordString() {
        return x + " " + y + " " + z;
    }

    public Map<String, Integer> items() {
        return items;
    }

    void items(Map<String, Integer> value) {
        this.items = new TreeMap<>(value);
    }

    /** 物品总个数（日志 / 提示用）。 */
    public int totalCount() {
        int total = 0;
        for (int count : items.values()) total += count;
        return total;
    }

    /** items 这一段的 JSON 文本（列表里点「复制物品」复制的就是这个）。 */
    public String itemsJson() {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object.toString();
    }

    /** 物品概览，给日志用。 */
    public String itemsSummary() {
        return items.size() + " 种 / " + totalCount() + " 个";
    }

    @Override
    public String toString() {
        return "#" + id + " " + type + " " + dimension + " " + coordString() + " (" + itemsSummary() + ")";
    }
}
