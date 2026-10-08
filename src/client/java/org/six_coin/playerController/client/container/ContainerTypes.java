package org.six_coin.playerController.client.container;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
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

    /** 大箱子两半可能的水平方向。 */
    public static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private ContainerTypes() {
    }

    /** 这个方块是不是支持的容器类型。 */
    public static boolean isSupported(Block block) {
        return SUPPORTED.contains(Registries.BLOCK.getId(block).toString());
    }

    /** 方块 id 文本（缓存里存的就是这个）。 */
    public static String idOf(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    /** 一句话描述支持的类型，给提示用。 */
    public static String describeSupported() {
        return String.join("、", SUPPORTED);
    }

    /**
     * 这两个位置是不是同一个大箱子的两半。
     *
     * <p>用的是原版的连接判断（只看方块状态 / 相邻方块，和里面的东西无关，所以客户端也能用）。
     */
    public static boolean sameDoubleChest(World world, BlockPos a, BlockPos b) {
        Inventory first = chestInventory(world, a);
        if (first == null) return false;
        return first == chestInventory(world, b);
    }

    /** 这个位置的箱子（含它连着的另一半）的物品栏；不是箱子返回 null。 */
    @Nullable
    private static Inventory chestInventory(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return null;
        return ChestBlock.getInventory(chestBlock, state, world, pos, true);
    }
}
