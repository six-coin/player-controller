package org.six_coin.playerController.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** 维度相关的常量与换算。 */
public final class DimensionUtils {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";

    /**
     * 通过末地传送门后固定落地的方块。
     *
     * <p>原版常量 {@code ServerWorld.END_SPAWN_POS} 是 (100, 50, 0)，观测下来实际要记录的是
     * 它下面一格，所以这里是 (100, 49, 0)。
     */
    private static final BlockPos END_SPAWN_POS = new BlockPos(100, 49, 0);

    private DimensionUtils() {
    }

    /** 玩家当前所在维度的 id。 */
    public static String current() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return OVERWORLD;
        Identifier id = mc.world.getRegistryKey().getValue();
        return id == null ? OVERWORLD : id.toString();
    }

    /** 把维度 id 显示成中文短名。 */
    public static String display(String dimension) {
        if (dimension == null) return "未知";
        return switch (dimension) {
            case OVERWORLD -> "主世界";
            case NETHER -> "下界";
            case END -> "末地";
            default -> dimension;
        };
    }

    /**
     * 排序用的维度优先级：主世界 0、下界 1、末地 2，其他维度排最后。
     *
     * <p>{@code optimize} / 缓存重排都用它，好让同一维度的东西挨在一起。
     */
    public static int orderOf(String dimension) {
        if (dimension == null) return 3;
        return switch (dimension) {
            case OVERWORLD -> 0;
            case NETHER -> 1;
            case END -> 2;
            default -> 3;
        };
    }

    /** 末地出生平台方块（末地传送门的落点）。 */
    public static BlockPos endSpawnPos() {
        return END_SPAWN_POS;
    }
}
