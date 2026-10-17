package org.six_coin.playerController.client.attack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * 暴击：攻击之前先给服务端补两个位置包，让它以为我们是从高处落下来打的。
 *
 * <p>原版判定暴击的条件之一是「玩家在往下掉、而且脚下不沾地」。这两个包就是假装
 * 「刚才在 0.0625 格高处、现在掉回原地」：服务端收到攻击包的时候认为我们在下落，于是算暴击，
 * 而客户端自己的位置和速度一点没变（不真的跳）。
 *
 * <p>站不在地上的时候不能这么干（本来就浮空 / 在水里 / 在岩浆里 / 在爬 / 在飞），那就不补包，
 * 这一下老老实实当普通攻击。
 *
 * <p>用哪些生物请看 profile 里那条的 {@code critical}。
 */
public final class SilentCriticals {

    /** 假装先抬这么高（经典值，够触发原版的「正在下落」判定，又不会撞到天花板）。 */
    private static final double LIFT = 0.0625;

    private SilentCriticals() {
    }

    /** 补两个位置包，让接下来的攻击算暴击。条件不满足就什么都不做。 */
    public static void crit(MinecraftClient mc) {
        if (!canCrit(mc)) return;
        sendY(mc, LIFT);
        sendY(mc, 0.0);
    }

    /** 现在能不能补暴击包。 */
    public static boolean canCrit(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.networkHandler == null) return false;

        return player.isOnGround()
            && !player.isSubmergedInWater()
            && !player.isInLava()
            && !player.isClimbing()
            && !player.isGliding()
            && !player.hasVehicle();
    }

    /** 不带动朝向、只改高度的一个位置包。 */
    private static void sendY(MinecraftClient mc, double deltaY) {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.networkHandler == null) return;

        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            player.getX(), player.getY() + deltaY, player.getZ(),
            false, player.horizontalCollision));
    }
}
