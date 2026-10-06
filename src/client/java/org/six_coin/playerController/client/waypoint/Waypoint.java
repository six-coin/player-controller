package org.six_coin.playerController.client.waypoint;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.DimensionUtils;

/** 一个路径点：维度 + 方块坐标 + 可选的名字。 */
public final class Waypoint {

    private int id;
    private String dimension;
    private int x;
    private int y;
    private int z;
    @Nullable
    private String name;

    /** 给 Gson 用。 */
    private Waypoint() {
    }

    Waypoint(int id, String dimension, BlockPos pos, @Nullable String name) {
        this.id = id;
        this.dimension = dimension;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.name = name;
    }

    public int id() {
        return id;
    }

    /** 维度 id，例如 minecraft:overworld。老存档里没有这个字段时补成主世界。 */
    public String dimension() {
        if (dimension == null || dimension.isEmpty()) dimension = DimensionUtils.OVERWORLD;
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

    @Nullable
    public String name() {
        return name;
    }

    public boolean hasName() {
        return name != null && !name.isEmpty();
    }

    /** 是不是那个特殊的出生点路径点（id 固定为 -1）。 */
    public boolean isSpawn() {
        return id == WaypointGraph.SPAWN_ID;
    }

    void name(@Nullable String name) {
        this.name = name;
    }

    /** 出生点路径点跟着当前出生点移动时用。 */
    void moveTo(String dimension, BlockPos pos) {
        this.dimension = dimension;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** 玩家站在这个路径点方块里时，应该对齐到的精确位置（x/z 为 n.5，y 为 n + 0.2）。 */
    public Vec3d center() {
        return org.six_coin.playerController.client.util.PlayerUtils.blockCenter(pos());
    }

    public String coordString() {
        return x + " " + y + " " + z;
    }

    @Override
    public String toString() {
        return "#" + id + " [" + DimensionUtils.display(dimension()) + " " + coordString() + "]"
            + (hasName() ? " (" + name + ")" : "");
    }
}
