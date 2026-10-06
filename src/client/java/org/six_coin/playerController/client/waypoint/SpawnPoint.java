package org.six_coin.playerController.client.waypoint;

import net.minecraft.util.math.BlockPos;

/**
 * 一个候选出生点。
 *
 * <p>末地祭坛回到主世界会落在玩家的重生点，所以这里让玩家自己登记若干个
 * 「可能的出生点」，其中一个被选为当前出生点。
 */
public final class SpawnPoint {

    private String name;
    private String dimension;
    private int x;
    private int y;
    private int z;

    /** 给 Gson 用。 */
    private SpawnPoint() {
    }

    SpawnPoint(String name, String dimension, BlockPos pos) {
        this.name = name;
        this.dimension = dimension;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public String name() {
        return name;
    }

    public String dimension() {
        if (dimension == null || dimension.isEmpty()) {
            dimension = org.six_coin.playerController.client.util.DimensionUtils.OVERWORLD;
        }
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

    void moveTo(String dimension, BlockPos pos) {
        this.dimension = dimension;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public String coordString() {
        return x + " " + y + " " + z;
    }
}
