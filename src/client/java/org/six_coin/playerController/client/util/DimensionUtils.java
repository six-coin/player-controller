package org.six_coin.playerController.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.DimensionType;

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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return OVERWORLD;
        Identifier id = mc.level.dimension().identifier();
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

    /**
     * 这个维度的建筑高度限制：<b>最高能放方块的那一格 y</b>（主世界是 319）。
     *
     * <p>当前维度直接用客户端的 {@code level.getMaxY() - 1}；别的维度（{@code dim} 参数写的不是
     * 自己站的这个）去注册表里查 {@code LevelStem -> DimensionType}，查不到就退回当前维度的值。
     */
    public static int buildMaxY(String dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 319;

        int here = mc.level.getMaxY() - 1;
        if (dimension == null || dimension.equals(current())) return here;

        Identifier id = Identifier.tryParse(dimension);
        if (id == null) return here;

        try {
            var lookup = mc.level.registryAccess().lookup(Registries.LEVEL_STEM);
            if (lookup.isEmpty()) return here;

            var stem = lookup.get().get(ResourceKey.create(Registries.LEVEL_STEM, id));
            if (stem.isEmpty()) return here;

            DimensionType type = stem.get().value().type().value();
            return type.minY() + type.height() - 1;
        } catch (Exception e) {
            ChatUtils.debug("查不到维度 " + dimension + " 的高度限制（" + e + "），按当前维度的算");
            return here;
        }
    }
}
