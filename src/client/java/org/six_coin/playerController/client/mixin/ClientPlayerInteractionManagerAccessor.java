package org.six_coin.playerController.client.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把原版那个私有的 {@code sendSequencedPacket} 借出来用。
 *
 * <p>发包挖方块（START / STOP_DESTROY_BLOCK）要带上原版的 sequence 号：客户端用它把
 * 服务端回推的方块更新对上，否则同一 tick 连挖很多方块会被自己的更新包搅乱。
 * 原版只在 {@code ClientPlayerInteractionManager} 内部用这个方法，所以这里只能借一下。
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {

    @Invoker("sendSequencedPacket")
    void playerController$sendSequencedPacket(ClientWorld world, SequencedPacketCreator creator);
}
