package org.six_coin.playerController.client.waypoint;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** 一个路径点：方块坐标 + 可选的名字。 */
public final class Waypoint {

    private int id;
    private int x;
    private int y;
    private int z;
    @Nullable
    private String name;

    /** 给 Gson 用。 */
    private Waypoint() {
    }

    Waypoint(int id, BlockPos pos, @Nullable String name) {
        this.id = id;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.name = name;
    }

    public int id() {
        return id;
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

    void name(@Nullable String name) {
        this.name = name;
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** 玩家站在这个路径点方块里时，应该对齐到的精确位置（x/z 为 n.5，y 为 n.0）。 */
    public net.minecraft.util.math.Vec3d center() {
        return new net.minecraft.util.math.Vec3d(x + 0.5, y, z + 0.5);
    }

    public String coordString() {
        return x + " " + y + " " + z;
    }

    @Override
    public String toString() {
        return "#" + id + " [" + coordString() + "]" + (hasName() ? " (" + name + ")" : "");
    }
}
