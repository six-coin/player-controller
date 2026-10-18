package org.six_coin.playerController.client.mine.station;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * station.json 里的一个坐标（单点或者列表里的一项）。
 *
 * <p>格式（和备货的 {@code stock/station/station.json} 一样，坐标是平的）：
 * <pre>
 * {
 *   "id": 1,                                  每个部分内部从 1 开始
 *   "item_id": "minecraft:oak_log",           只有 item_collector 有
 *   "dimension": "minecraft:overworld",
 *   "x": 0, "y": 64, "z": 0,
 *   "access_position": {"dimension": "..", "x": .., "y": .., "z": ..}   check 算出来的落点
 * }
 * </pre>
 */
public final class MinePlace {

    private int id;
    private String item_id;
    private String dimension;
    private int x;
    private int y;
    private int z;
    private MineAccessPos access_position;

    /** 给 Gson 用。 */
    private MinePlace() {
    }

    private MinePlace(String dimension, int x, int y, int z, @Nullable String itemId) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.item_id = itemId;
    }

    public static MinePlace of(String dimension, BlockPos pos, @Nullable String itemId) {
        return new MinePlace(dimension, pos.getX(), pos.getY(), pos.getZ(), itemId);
    }

    public int id() {
        return id;
    }

    void id(int value) {
        this.id = value;
    }

    /** 只有产物收集有；别的部分返回 null。 */
    @Nullable
    public String itemId() {
        return item_id;
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public String coordString() {
        return x + " " + y + " " + z;
    }

    /** 落点；check 还没跑过就是 null。 */
    @Nullable
    public MineAccessPos access() {
        return access_position;
    }

    public void access(@Nullable MineAccessPos value) {
        this.access_position = value;
    }

    public String describe() {
        return DimensionUtils.display(dimension) + " " + coordString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MinePlace place)) return false;
        return x == place.x && y == place.y && z == place.z
            && dimension != null && dimension.equals(place.dimension);
    }

    @Override
    public int hashCode() {
        return ((dimension == null ? 0 : dimension.hashCode()) * 31 + x) * 31 + y * 31 + z;
    }

    @Override
    public String toString() {
        return describe();
    }
}
