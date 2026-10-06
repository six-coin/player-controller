package org.six_coin.playerController.client.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.six_coin.playerController.client.mixin.PlayerMoveC2SPacketAccessor;

/**
 * 参考 Meteor 的 NoFall（packet 模式）实现。
 *
 * <p>核心做法：在 {@code PlayerMoveC2SPacket} 发出之前，把包里的 {@code onGround}
 * 强制改成 {@code true}，服务端就会把落地距离清零，从而不掉血。
 *
 * <p>用引用计数管理开关：控制器移动时会 {@link #acquire()}，
 * 移动结束会 {@link #release()}，也就是“移动时自动开启 NoFall”。
 */
public final class NoFall {

    private static int holders = 0;

    private NoFall() {
    }

    public static void acquire() {
        holders++;
    }

    public static void release() {
        if (holders > 0) holders--;
    }

    public static boolean isEnabled() {
        return holders > 0;
    }

    /**
     * 由 {@code ClientConnectionMixin} 在发包前调用。
     */
    public static void onSendPacket(Packet<?> packet) {
        if (holders <= 0) return;
        if (!(packet instanceof PlayerMoveC2SPacket movePacket)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        // 与 Meteor 一致：创造模式不管，拿锤子时不管
        if (player.getAbilities().creativeMode) return;
        if (player.getMainHandStack().getItem() instanceof MaceItem) return;

        if (FlightVelocity.isActive()) {
            // 飞行中：无条件上报 onGround，防止服务端踢人（复刻 Meteor 的分支）
            forceOnGround(movePacket);
            return;
        }

        if (player.isGliding()) return;
        if (player.getVelocity().y > -0.5) return;
        forceOnGround(movePacket);
    }

    private static void forceOnGround(PlayerMoveC2SPacket packet) {
        if (packet.isOnGround()) return;
        ((PlayerMoveC2SPacketAccessor) packet).playerController$setOnGround(true);
    }
}
