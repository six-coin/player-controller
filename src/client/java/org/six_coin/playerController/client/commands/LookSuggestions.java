package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.concurrent.CompletableFuture;

/**
 * 坐标参数的自动填充：默认给出准星看到的那个方块的分量。
 *
 * <p>三个分量都只建议「看到的方块」对应的那一项，所以依次 Tab 三下就是完整坐标。
 */
public final class LookSuggestions {

    private LookSuggestions() {
    }

    public static <S> CompletableFuture<Suggestions> x(CommandContext<S> context, SuggestionsBuilder builder) {
        BlockPos looked = PlayerUtils.lookedAtBlock();
        if (looked != null) builder.suggest(looked.getX());
        return builder.buildFuture();
    }

    public static <S> CompletableFuture<Suggestions> y(CommandContext<S> context, SuggestionsBuilder builder) {
        BlockPos looked = PlayerUtils.lookedAtBlock();
        if (looked != null) builder.suggest(looked.getY());
        return builder.buildFuture();
    }

    public static <S> CompletableFuture<Suggestions> z(CommandContext<S> context, SuggestionsBuilder builder) {
        BlockPos looked = PlayerUtils.lookedAtBlock();
        if (looked != null) builder.suggest(looked.getZ());
        return builder.buildFuture();
    }
}
