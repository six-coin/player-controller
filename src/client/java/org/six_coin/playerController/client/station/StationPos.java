package org.six_coin.playerController.client.station;

import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.DimensionUtils;

/** station.json 里的一个坐标：{@code {"id": .., "dimension": "..", "x": .., "y": .., "z": ..}}。 */
public final class StationPos {

    /** 每个部分内部从 1 开始编号（单点固定 1，列表按顺序 1..n），写文件 / 读文件前统一重编。 */
    private int id;
    private String dimension;
    private int x;
    private int y;
    private int z;

    /** 给 Gson 用。 */
    private StationPos() {
    }

    private StationPos(String dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static StationPos of(String dimension, BlockPos pos) {
        return new StationPos(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    public int id() {
        return id;
    }

    void id(int value) {
        this.id = value;
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
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

    public String coordString() {
        return x + " " + y + " " + z;
    }

    /** 带维度的显示，比如「主世界 1 2 3」。 */
    public String describe() {
        return DimensionUtils.display(dimension) + " " + coordString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StationPos pos)) return false;
        return x == pos.x && y == pos.y && z == pos.z
            && dimension != null && dimension.equals(pos.dimension);
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
