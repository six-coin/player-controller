package org.six_coin.playerController.client.mending;

import net.minecraft.core.BlockPos;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * mend.json 里的一个坐标：{@code {"dimension": "..", "x": .., "y": .., "z": ..}}。
 *
 * <p>和 {@code stock.station.StationPos} 存的内容一样，但这里没有「编号」那套东西：
 * mending 只有两个固定的位置（站立点、末影箱），不需要排序。
 */
public final class MendingPos {

    private String dimension;
    private int x;
    private int y;
    private int z;

    /** 给 Gson 用。 */
    private MendingPos() {
    }

    private MendingPos(String dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static MendingPos of(String dimension, BlockPos pos) {
        return new MendingPos(dimension, pos.getX(), pos.getY(), pos.getZ());
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
        if (!(other instanceof MendingPos pos)) return false;
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
