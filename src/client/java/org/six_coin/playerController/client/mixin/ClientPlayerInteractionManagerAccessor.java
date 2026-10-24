package org.six_coin.playerController.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把原版那个私有的 {@code startPrediction} 借出来用。
 *
 * <p>发包挖方块（START / STOP_DESTROY_BLOCK）要带上原版的 sequence 号：客户端用它把
 * 服务端回推的方块更新对上，否则同一 tick 连挖很多方块会被自己的更新包搅乱。
 * 原版只在 {@code MultiPlayerGameMode} 内部用这个方法，所以这里只能借一下。
 *
 * <p>1.21.11 里它叫 {@code sendSequencedPacket(ClientWorld, SequencedPacketCreator)}，
 * 26.3 改成了 {@code startPrediction(ClientLevel, PredictiveAction)}（{@code PredictiveAction}
 * 就是「给我 sequence，我返回要发的包」这个函数式接口），语义一样。
 */
@Mixin(MultiPlayerGameMode.class)
public interface ClientPlayerInteractionManagerAccessor {

    @Invoker("startPrediction")
    void playerController$startPrediction(ClientLevel level, PredictiveAction action);
}
