package org.six_coin.playerController.client.mine;

import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 一次 simple 挖掘任务的范围：一个维度 + 两个角（含端点）+ 方块过滤器。
 *
 * <p>两个角谁大谁小都行，{@link #of} 会先换成 min / max。
 */
public record MineRegion(String dimension, BlockPos min, BlockPos max, MineFilter filter) {

    public static MineRegion of(String dimension, BlockPos first, BlockPos second, MineFilter filter) {
        BlockPos min = new BlockPos(
            Math.min(first.getX(), second.getX()),
            Math.min(first.getY(), second.getY()),
            Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(
            Math.max(first.getX(), second.getX()),
            Math.max(first.getY(), second.getY()),
            Math.max(first.getZ(), second.getZ()));
        return new MineRegion(dimension, min, max, filter);
    }

    /** 这个方块在不在框里（端点算在里面）。 */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** 框里一共多少格（含端点）。 */
    public long volume() {
        return (long) (max.getX() - min.getX() + 1)
            * (max.getY() - min.getY() + 1)
            * (max.getZ() - min.getZ() + 1);
    }

    /** 「主世界 [0, 64, 0] ~ [10, 70, 10]」这种一行。 */
    public String describe() {
        return DimensionUtils.display(dimension) + " " + min.toShortString() + " ~ " + max.toShortString()
            + "，" + filter.describe();
    }
}
