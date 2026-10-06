package org.six_coin.playerController.client.util;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * 传送门方块的查找。
 *
 * <p>「记录传送门边」和「穿越传送门」必须用同一套判断，否则会出现
 * 「边建出来了但走不过去」这种对不上的情况。
 *
 * <p>规则：
 * <ul>
 *   <li><b>末地传送门</b>：只看<b>下面一格</b>（站在传送门上方的情况），
 *       外加方块自己那一格；</li>
 *   <li><b>下界传送门</b>：那一格本身必须就是下界传送门方块，不看旁边。</li>
 * </ul>
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

    /**
     * 这个位置（或者它下面一格）是不是末地传送门方块。
     *
     * <p>末地传送门是「站在传送门方块上方」放路径点的，所以下面那一格是主要判断依据。
     */
    public static boolean endPortalNear(BlockPos pos) {
        return endPortalFor(pos) != null;
    }

    /**
     * 这个位置对应的末地传送门方块。
     *
     * <p>先看自己那一格，没有再看下面一格。
     *
     * @return 传送门方块的位置，没有返回 null
     */
    @Nullable
    public static BlockPos endPortalFor(BlockPos pos) {
        if (isEndPortal(pos)) return pos;
        BlockPos below = pos.down();
        if (isEndPortal(below)) return below;
        return null;
    }

    /** 出错信息里用：这个位置实际是什么方块。 */
    public static String blockId(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return "?";
        return Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).toString();
    }
}
