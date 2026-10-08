package org.six_coin.playerController.client.station;

import net.minecraft.util.math.BlockPos;

/** station.json 里的一个坐标（就是 {@code {"x": .., "y": .., "z": ..}}）。 */
public final class StationPos {

    private int x;
    private int y;
    private int z;

    /** 给 Gson 用。 */
    private StationPos() {
    }

    private StationPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static StationPos of(BlockPos pos) {
        return new StationPos(pos.getX(), pos.getY(), pos.getZ());
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

    @Override
    public String toString() {
        return coordString();
    }
}
