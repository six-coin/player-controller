package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.concurrent.CompletableFuture;

/**
 * 坐标参数的自动填充。
 *
 * <p>跟原版 {@code /setblock <pos>} 的坐标补全一样：一次补全就把整串「x y z」填进去，
 * 而不是一个分量一个分量地补。
 *
 * <p>原版那边是服务端算的：{@code BlockPosArgumentType.listSuggestions} 拿到
 * {@code CommandSource.getBlockPositionSuggestions()} 给的 {@code RelativePosition}，
 * 再用 {@code CommandSource.suggestPositions} 拼成整串建议，客户端发
 * {@code RequestCommandCompletionC2SPacket} 去要。{@code /pc} 是客户端命令，
 * 服务端根本不知道它，所以这里用同一个思路自己造整串建议
 * （brigadier 的建议替换范围是整个参数，所以一次就能把坐标填满）。
 *
 * <p>补全用的方块取准星看到的方块（{@link PlayerUtils#lookedAtBlock()}，距离用配置里的
 * {@code actions.interaction_range}），没看到就退而用玩家脚下那一格。
 */
public final class LookSuggestions {

    private LookSuggestions() {
    }

    /** x 参数：补「x y z」，一次填满三个坐标。 */
    public static <S> CompletableFuture<Suggestions> x(CommandContext<S> context, SuggestionsBuilder builder) {
        BlockPos pos = target();
        if (pos == null) return builder.buildFuture();
        return builder.suggest(keep(builder, pos.getX()) + " " + pos.getY() + " " + pos.getZ())
            .buildFuture();
    }

    /** y 参数：补「y z」（x 已经打好了）。 */
    public static <S> CompletableFuture<Suggestions> y(CommandContext<S> context, SuggestionsBuilder builder) {
        BlockPos pos = target();
        if (pos == null) return builder.buildFuture();
        return builder.suggest(keep(builder, pos.getY()) + " " + pos.getZ()).buildFuture();
    }

    /** z 参数：补「z」（x y 已经打好了）。 */
    public static <S> CompletableFuture<Suggestions> z(CommandContext<S> context, SuggestionsBuilder builder) {
        BlockPos pos = target();
        if (pos == null) return builder.buildFuture();
        return builder.suggest(keep(builder, pos.getZ())).buildFuture();
    }

    // ------------------------------------------------------------------

    /** 补全的目标方块：准星看到的方块，没看到就用玩家站的这一格。 */
    private static BlockPos target() {
        BlockPos looked = PlayerUtils.lookedAtBlock();
        return looked != null ? looked : PlayerUtils.currentBlockPos();
    }

    /**
     * 玩家已经打了一半的坐标：是合法整数就沿用他自己打的，否则用建议值
     * （这样 {@code -} 之类打错的半截会被整串坐标替换掉）。
     */
    private static String keep(SuggestionsBuilder builder, int suggested) {
        String typed = builder.getRemaining().trim();
        return isInteger(typed) ? typed : Integer.toString(suggested);
    }

    private static boolean isInteger(String text) {
        if (text.isEmpty()) return false;
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
