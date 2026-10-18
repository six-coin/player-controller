package org.six_coin.playerController.client.mine.station;

import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 一个容器的落点：{@code {"dimension": "...", "x": .., "y": .., "z": ..}}。
 *
 * <p>落点 = 站在哪里能右键到这个容器（在容器的触及范围内、而且在路径点图上），
 * 由 {@code /pc mine complex station check} 算出来写进 station.json。
 */
public final class MineAccessPos {

    private String dimension;
    private int x;
    private int y;
    private int z;

    /** 给 Gson 用。 */
    private MineAccessPos() {
    }

    private MineAccessPos(String dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static MineAccessPos of(String dimension, BlockPos pos) {
        return new MineAccessPos(dimension, pos.getX(), pos.getY(), pos.getZ());
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

    public String describe() {
        return DimensionUtils.display(dimension) + " " + coordString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
