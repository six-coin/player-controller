package org.six_coin.playerController.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import org.six_coin.playerController.client.feature.MovePackets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 所有发往服务端的包都会经过这里：移动包在这里被「改写 / 去重」，顺便接住服务端的 tick 边界。
 *
 * <p>26.3 的服务端在 {@code handleMovePlayer} 里，一个 tick 内收到第二个带位置的移动包就直接断开
 * （{@code Invalid move player packet received}），而「一个 tick」的边界是
 * <b>{@code ServerboundClientTickEndPacket}</b> —— 服务端在 {@code handleClientTickEnd} 里
 * 把 {@code receivedPositionThisTick} 清零。
 *
 * <p>所以这里做两件事：
 * <ul>
 *   <li>看到 {@code ClientTickEnd} 就告诉 {@link MovePackets} 换一个窗口；</li>
 *   <li>移动包交给 {@link MovePackets#filter}：改写（y / 朝向 / 非法坐标），
 *       同一窗口的第二个直接取消。</li>
 * </ul>
 *
 * <p>注入的是 {@code ClientCommonPacketListenerImpl#send(Packet)}（{@code ClientPacketListener}
 * 的父类，{@code Minecraft.getConnection()} 返回的就是它）。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientPacketSendMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void playerController$filterMovePacket(Packet<?> packet, CallbackInfo ci) {
        // 服务端就是在这个包到达时把「这一 tick 收过位置包了吗」清零的
        if (packet instanceof ServerboundClientTickEndPacket) {
            MovePackets.endWindow();
            return;
        }
        if (!MovePackets.filter(packet)) {
            ci.cancel();
        }
    }
}
