package org.six_coin.playerController.client.util;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * 传送门方块的查找。
 *
 * <p>「记录传送门边」和「穿越传送门」必须用同一套判断，否则会出现
 * 「边建出来了但走不过去」这种对不上的情况。
 *
 * <p>找的时候不只看方块自己，还会看**下方 1 格**（站在传送门上方的情况）、
 * 上方 1 格、以及四个水平邻居 —— 末地回主世界的祭坛就经常站得和传送门方块差一格。
 */
public final class PortalUtils {

    private PortalUtils() {
    }

    public static boolean isEndPortal(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.world.getBlockState(pos).isOf(Blocks.END_PORTAL);
    }

    public static boolean isNetherPortal(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL);
    }

    /** 附近有没有末地传送门方块。 */
    public static boolean endPortalNear(BlockPos pos) {
        return findNear(pos, false) != null;
    }

    /** 附近有没有下界传送门方块。 */
    public static boolean netherPortalNear(BlockPos pos) {
        return findNear(pos, true) != null;
    }

    /**
     * 在这个方块自己、下方、上方、四个水平邻居里找传送门方块。
     *
     * <p>顺序有讲究：先看自己，再看下面（站在传送门上方），然后上面，最后四周。
     *
     * @param nether true 找下界传送门，false 找末地传送门
     * @return 找到的传送门方块，没有返回 null
     */
    @Nullable
    public static BlockPos findNear(BlockPos pos, boolean nether) {
        if (isPortalAt(pos, nether)) return pos;

        BlockPos below = pos.down();
        if (isPortalAt(below, nether)) return below;

        BlockPos above = pos.up();
        if (isPortalAt(above, nether)) return above;

        for (Direction direction : new Direction[]{
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighbour = pos.offset(direction);
            if (isPortalAt(neighbour, nether)) return neighbour;
        }
        return null;
    }

    private static boolean isPortalAt(BlockPos pos, boolean nether) {
        return nether ? isNetherPortal(pos) : isEndPortal(pos);
    }

    /** 出错信息里用：这个位置实际是什么方块。 */
    public static String blockId(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return "?";
        return Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).toString();
    }
}
