package org.six_coin.playerController.client.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.six_coin.playerController.client.feature.MovePackets;

/**
 * 暴击：攻击之前先让服务端以为我们是从高处落下来打的。
 *
 * <p>原版判暴击的条件（{@code Player.isCriticalHit}）就这几条：
 *
 * <pre>
 * fallDistance &gt; 0  &amp;&amp; !onGround() &amp;&amp; !onClimbable() &amp;&amp; !isUnderWater()
 *                    &amp;&amp; !hasEffect(BLINDNESS) &amp;&amp; !isPassenger() &amp;&amp; !isSprinting()
 * </pre>
 *
 * <p><b>26.3 的做法</b>：以前是「自己额外发两个位置包」假装先抬 0.0625 格再掉回原地，
 * 但 26.3 的服务端一个 tick 只接受一个带位置的移动包，第二包直接断开
 * （{@code Invalid move player packet received}，见 {@link MovePackets}）。
 * 所以现在改成<b>改写原版自己要发的那个包</b>，把整个动作摊到三个 tick 上：
 *
 * <ol>
 *   <li>第 1 tick：原版的位置包被改成 {@code y + 0.0625}、{@code onGround = false}
 *       —— 服务端认为我们正悬在 0.0625 格高处；</li>
 *   <li>第 2 tick：位置包被改回真实高度、{@code onGround = false}
 *       —— 服务端算出「往下掉了 0.0625」，{@code fallDistance = 0.0625}，而且仍然不在地上；</li>
 *   <li>第 3 tick：这时才真的发攻击包 —— 服务端按暴击结算。</li>
 * </ol>
 *
 * <p>客户端自己的位置和速度一点没变，屏幕上什么都看不出来。代价是每次暴击多等 2 tick（100ms）。
 *
 * <p>注意那个 {@code !isSprinting()}：**跑着打永远不会暴击**，这是原版规则，跟补不补包无关。
 *
 * <p>补包的条件见 {@link #canCrit}：站得稳稳的时候才补 —— 站在地上、不在水里、不在岩浆里、
 * 没在爬、没在滑翔、没骑着东西。
 *
 * <ul>
 *   <li>水里 / 岩浆里 / 爬着 / 骑着的时候原版本来就不给暴击，补了也白补；</li>
 *   <li>滑翔和骑乘的时候位置是服务端那边按鞘翅 / 载具算出来的，自己插一个假位移
 *       会跟它的校验打架（容易被拉回去）。</li>
 * </ul>
 *
 * <p>不补包的时候这一下老老实实当普通攻击。
 *
 * <p>用哪些生物请看 profile 里那条的 {@code critical}。
 */
public final class SilentCriticals {

    /** 假装先抬这么高（经典值：够触发服务端的「正在下落」判定，又小到不会撞到天花板）。 */
    private static final double LIFT = 0.0625;

    /** 0 = 还没开始；1 = 抬高的包已经发出去了；2 = 掉回来的包已经发出去了（这一 tick 可以打）。 */
    private static int stage;

    private SilentCriticals() {
    }

    /**
     * 推进一次暴击准备，由 {@code AttackManager} 每 tick 调一次。
     *
     * @return {@code true} = 现在可以打（要么本来就不用补包，要么两个包都已经出去了）；
     *         {@code false} = 这一 tick 只补包，先别打
     */
    public static boolean step(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null || !canCrit(mc)) {
            // 补不了（在水里 / 在爬 / 骑着 / 不在地上……）就老老实实打，别拖着
            stage = 0;
            return true;
        }

        switch (stage) {
            case 0 -> {
                // 第 1 tick：让下一个位置包假装我们悬在 LIFT 高处
                MovePackets.overrideNext(player.getY() + LIFT, false);
                MovePackets.forceNext();
                stage = 1;
                return false;
            }
            case 1 -> {
                // 第 2 tick：让下一个位置包回到真实高度 —— 服务端就算出「刚往下掉了 0.0625」
                MovePackets.overrideNext(player.getY(), false);
                MovePackets.forceNext();
                stage = 2;
                return false;
            }
            default -> {
                // 第 3 tick：两个包都到了，这一下算暴击
                stage = 0;
                return true;
            }
        }
    }

    /** 现在这个状态补暴击包合不合适。 */
    public static boolean canCrit(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) return false;

        return player.onGround()
            && !player.isUnderWater()
            && !player.isInLava()
            && !player.onClimbable()
            && !player.isFallFlying()
            && !player.isPassenger();
    }

    /** 目标没了 / 被打断的时候把状态清掉，免得下次一上来就直接进第 3 步。 */
    public static void reset() {
        stage = 0;
    }
}
