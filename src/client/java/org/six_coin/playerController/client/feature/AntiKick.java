package org.six_coin.playerController.client.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.six_coin.playerController.client.config.PlayerControllerConfig;

/**
 * 防踢：跟 meteor-client 的 Flight（anti-kick，mode = packet，off-time 默认 1 tick）行为一致。
 *
 * <p><b>为什么会被踢</b>：本模组的飞行是「客户端自己在空中悬停」，服务端并不允许飞。
 * 原版 {@code ServerPlayNetworkHandler} 会对每个移动包检查「有没有往下掉」：
 * 只要玩家在空中、而且这一包的高度没有比上一包低 {@code -0.03125} 以上，它就把
 * {@code floatingTicks} 加一，累计超过 80 tick（4 秒）就断开连接（Flying is not enabled）。
 * 悬停飞行一直不往下掉，所以时间一到必被踢；本地存档（集成服务器）不做这个检查，所以只有原版服务器会踢。
 *
 * <p><b>怎么防</b>：Meteor 的 packet 模式是【每 {@code anti_kick_delay} tick（默认 40），在
 * {@code anti_kick_off_time} tick（默认 1）的窗口里往服务端发一个「比上一次低一点点（{@link #DIP} 格）」
 * 的位置包】，让服务端那一 tick 认为玩家在往下掉，于是把 {@code floatingTicks} 清零，永远攒不到 80。
 * 两个参数都在 {@code /pc config actions} 里改。
 *
 * <p>和 Meteor 的差别只有一处：Meteor 是把客户端本来就要发的移动包改低（还会把
 * {@code ticksSinceLastPositionPacketSent} 设成 20 强制客户端发包），我们这里直接自己发一个位置包，
 * 服务端看到的东西完全一样，而且不需要额外的 mixin 去改 {@code ClientPlayerEntity} 的私有字段。
 *
 * <p>另外两点跟 Meteor 保持一致：
 * <ul>
 *   <li>玩家已经站在地上时不动（服务端站地上本来就会清零，再往下补反而像掉进地里）；</li>
 *   <li>本来就在往下掉（比上一包低够 {@link #DIP}）时也不动 —— 自然下降已经能清零了。</li>
 * </ul>
 *
 * <p>只在飞行持有期间生效（见 {@link FlightVelocity}），所以「所有移动过程」都覆盖到了，
 * 不移动的时候不会往服务端多发任何包。这里**不打任何日志**（以前每补一次刷一行，太吵）。
 */
public final class AntiKick {

    /**
     * 往下补多少格。
     *
     * <p>原版的判定门槛是 {@code >= -0.03125}，而且取的是「大于等于」，所以这里要比它再低一点点。
     */
    private static final double DIP = 0.03130D;

    /** 距离下一次「往下补」还剩多少 tick。 */
    private static int delayLeft = PlayerControllerConfig.DEFAULT_ANTI_KICK_DELAY;

    /** 这次「往下补」窗口还剩多少 tick。 */
    private static int offLeft;

    /** 上一个移动包的高度（Meteor 的 lastPacketY），没发过就是 MAX。 */
    private static double lastPacketY = Double.MAX_VALUE;

    private AntiKick() {
    }

    /** 每个客户端 tick 调一次（END_CLIENT_TICK，也就是 Meteor 的 post-tick）。 */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        if (player == null || player.networkHandler == null || mc.world == null) {
            reset();
            return;
        }
        // 没在飞（也不在移动）就什么都不做：服务端不会因为浮空踢我们
        if (!FlightVelocity.isActive()) {
            reset();
            return;
        }

        int delay = PlayerControllerConfig.getAntiKickDelay();

        if (delayLeft > 0) {
            delayLeft--;
            if (delayLeft > 0) {
                // 平时跟着客户端自己的位置走，下次要补的时候才知道该从哪儿往下补
                lastPacketY = player.getY();
                return;
            }
            offLeft = PlayerControllerConfig.getAntiKickOffTime();
        }

        if (offLeft > 0) {
            offLeft--;
            dip(player);
            return;
        }

        delayLeft = delay;
    }

    private static void dip(ClientPlayerEntity player) {
        double currentY = player.getY();

        // 第一次补的时候还不知道上一包是多少，先记下来，下一次再说
        if (lastPacketY == Double.MAX_VALUE) {
            lastPacketY = currentY;
            return;
        }

        // 站在地上：服务端本来就会清零，不用管
        if (player.isOnGround()) {
            lastPacketY = currentY;
            return;
        }

        // 本来就掉得够多了：自然下降已经能清零
        if (!shouldFlyDown(currentY, lastPacketY)) {
            lastPacketY = currentY;
            return;
        }

        double y = lastPacketY - DIP;
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            player.getX(), y, player.getZ(), player.isOnGround(), player.horizontalCollision));
    }

    /**
     * 该不该往下补。
     *
     * <p>跟 Meteor 一样：这一包比上一包高（或者持平）要补，掉得不够多（不到 {@link #DIP}）也要补。
     */
    private static boolean shouldFlyDown(double currentY, double lastY) {
        return currentY >= lastY || lastY - currentY < DIP;
    }

    private static void reset() {
        delayLeft = PlayerControllerConfig.getAntiKickDelay();
        offLeft = 0;
        lastPacketY = Double.MAX_VALUE;
    }
}
