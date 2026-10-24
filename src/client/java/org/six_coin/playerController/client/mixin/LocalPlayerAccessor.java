package org.six_coin.playerController.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把 {@code LocalPlayer.positionReminder} 借出来，用来「强制原版下一 tick 发一个位置包」。
 *
 * <p>原版 {@code sendPosition()} 只在位置变了、或者 {@code positionReminder >= 20} 的时候发包；
 * 站着不动的时候一个包都不发，那 {@link org.six_coin.playerController.client.feature.MovePackets}
 * 想改的 y 就没机会出去。把它顶到 20 就能逼出一个包（Meteor 用的是同一招）。
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {

    @Accessor("positionReminder")
    void playerController$setPositionReminder(int value);
}
