package org.six_coin.playerController.client.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 用来修改移动包里被 final 修饰的 onGround 字段（NoFall 需要）。 */
@Mixin(PlayerMoveC2SPacket.class)
public interface PlayerMoveC2SPacketAccessor {

    @Mutable
    @Accessor("onGround")
    void playerController$setOnGround(boolean onGround);
}
