package org.six_coin.playerController.client.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.six_coin.playerController.client.feature.NoFall;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jetbrains.annotations.Nullable;

/** 在发包前给 NoFall 一个修改包内容的机会。 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
    private void playerController$onSend(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo ci) {
        NoFall.onSendPacket(packet);
    }
}
