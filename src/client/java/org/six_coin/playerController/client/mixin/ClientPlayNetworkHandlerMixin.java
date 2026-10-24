package org.six_coin.playerController.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.six_coin.playerController.client.feature.ServerTickRate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 记下每个「世界时间更新」包到达的时刻，用 {@link ServerTickRate} 估服务端的 tick 速率。
 *
 * <p>原版服务端每秒发一个这个包，所以两次之间的真实时间就能反推出它卡的有多厉害。
 * 不改包的内容，只是旁听一下。
 */
@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "handleSetTime", at = @At("HEAD"))
    private void playerController$onWorldTimeUpdate(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerTickRate.onWorldTimeUpdate();
    }
}
