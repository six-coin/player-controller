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
     * <p>等价于原版常量 {@code ServerWorld.END_SPAWN_POS}（值就是 100, 50, 0），
     * 到达时用的是 {@code refreshPositionAndAngles}，也就是 x.5 / y.n / z.5，
     * 正好和我们的「方块中心」约定一致。
     */
    private static final BlockPos END_SPAWN_POS = new BlockPos(100, 50, 0);

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

    /** 末地出生平台方块（末地传送门的落点）。 */
    public static BlockPos endSpawnPos() {
        return END_SPAWN_POS;
    }
}
