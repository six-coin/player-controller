package org.six_coin.playerController.client.attack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * 暴击：攻击之前先给服务端补两个位置包，让它以为我们是从高处落下来打的。
 *
 * <p>原版判暴击的条件（{@code PlayerEntity.isCriticalHit}）就这几条：
 *
 * <pre>
 * fallDistance &gt; 0  &amp;&amp; !isOnGround() &amp;&amp; !isClimbing() &amp;&amp; !isTouchingWater()
 *                    &amp;&amp; !hasBlindnessEffect() &amp;&amp; !hasVehicle() &amp;&amp; !isSprinting()
 * </pre>
 *
 * <p>这两个包就是假装「刚才在 0.0625 格高处、现在掉回原地」：服务端收到攻击包的时候
 * 认为我们离地、而且 fallDistance = 0.0625，于是算暴击。客户端自己的位置和速度一点没变。
 *
 * <p>注意那个 {@code !isSprinting()}：**跑着打永远不会暴击**，这是原版规则，跟补不补包无关。
 *
 * <p>下面两种情况不补包（补了有害）：
 *
 * <ul>
 *   <li>骑着东西（船 / 矿车 / 马）：位置由载具驱动，自己报位置会被服务端当成「移动异常」，
 *       而且原版本来就不让骑乘状态暴击；</li>
 *   <li>滑翔中：位置和下落是服务端按鞘翅的飞行模型算的，插假位移容易跟飞行速度校验打架
 *       （被拉回去）；而且滑翔时本来就离地 + {@code fallDistance > 0}，原版直接就给暴击。</li>
 * </ul>
 *
 * <p>其余情况（站在地上、泡在水里、在岩浆里、爬梯子）补包都没坏处：水里 / 爬着的时候
 * 原版本来就不给暴击，补了只是白费两个包，攻击照样命中，所以不拦。
 *
 * <p>用哪些生物请看 profile 里那条的 {@code critical}。
 */
public final class SilentCriticals {

    /** 假装先抬这么高（经典值：够触发服务端的「正在下落」判定，又小到不会撞到天花板）。 */
    private static final double LIFT = 0.0625;

    private SilentCriticals() {
    }

    /** 补两个位置包，让接下来的攻击算暴击。条件不满足就什么都不做。 */
    public static void crit(MinecraftClient mc) {
        if (!canCrit(mc)) return;
        sendY(mc, LIFT);
        sendY(mc, 0.0);
    }

    /** 现在补暴击包会不会出事。 */
    public static boolean canCrit(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.networkHandler == null) return false;

        return !player.hasVehicle() && !player.isGliding();
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
