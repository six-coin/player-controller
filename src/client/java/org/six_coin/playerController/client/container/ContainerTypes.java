package org.six_coin.playerController.client.container;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 容器缓存支持的方块类型。
 *
 * <p>现在只支持箱子和木桶；以后要加类型，往 {@link #SUPPORTED} 里加方块 id 就行
 * （别的逻辑不用动，都走这个常量判断）。
 */
public final class ContainerTypes {

    /** 支持的容器方块 id。 */
    public static final List<String> SUPPORTED = List.of(
        "minecraft:chest",
        "minecraft:barrel"
    );

    /** 大箱子两半的方向就按方块状态算，见 {@link #otherHalf(Level, BlockPos)}。 */

    private ContainerTypes() {
    }

    /** 这个方块是不是支持的容器类型。 */
    public static boolean isSupported(Block block) {
        return SUPPORTED.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
    }

    /** 方块 id 文本（缓存里存的就是这个）。 */
    public static String idOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** 一句话描述支持的类型，给提示用。 */
    public static String describeSupported() {
        return String.join("、", SUPPORTED);
    }

    /**
     * 大箱子的另一半在哪一格。
     *
     * <p>直接看方块状态（跟原版一个算法）：
     * <ul>
     *   <li>{@code type=left} → 另一半在 {@code facing} 顺时针 90° 那边
     *       （比如 facing=north + left → 东边，也就是 x+1）；</li>
     *   <li>{@code type=right} → 另一半在 {@code facing} 逆时针 90° 那边。</li>
     * </ul>
     *
     * @return 另一半的位置；这个位置不是箱子、或者箱子是单格的返回 null
     */
    @Nullable
    public static BlockPos otherHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;

        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return null;

        Direction facing = state.getValue(ChestBlock.FACING);
        Direction direction = type == ChestType.LEFT
            ? facing.getClockWise()
            : facing.getCounterClockWise();
        return pos.relative(direction);
    }
}
