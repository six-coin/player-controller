package org.six_coin.playerController.client.feature;

import net.minecraft.util.Mth;

import java.util.Arrays;

/**
 * 服务端的 tick 速率估算，用来判断「服务端是不是卡了」。
 *
 * <p>原版服务端每秒（20 tick）会发一个 {@code WorldTimeUpdateS2CPacket} 下来
 * （见 {@code mixin/ClientPlayNetworkHandlerMixin}），所以只要量两次之间的真实时间，
 * 就能反推出它的 tick 有多快：
 *
 * <ul>
 *   <li>正常是 1 秒一个包 → 20 tick/s；</li>
 *   <li>服务端卡了，包子晚到 → {@link #timeSinceLastTick()} 变大。</li>
 * </ul>
 *
 * <p>攻击（{@code /pc attack}）在服务端卡着的时候不继续打（{@code pause on lag}）。
 */
public final class ServerTickRate {

    /** 记最近这么多个包用来算平均。 */
    private static final int SAMPLES = 20;

    /** 刚进世界的前几秒数据不可信（那时候还没有足够的包），直接当成不卡。 */
    private static final long WARM_UP_MS = 4000L;

    /** 正常是 1 秒一个时间包；超过这个秒数还没来就算卡了。 */
    public static final float LAG_THRESHOLD_SECONDS = 1.5F;

    private static final float[] TICK_RATES = new float[SAMPLES];

    private static int next;
    private static long lastTimeUpdate = -1L;
    private static long joined = System.currentTimeMillis();

    private ServerTickRate() {
    }

    /** 收到一个「世界时间更新」包（由 mixin 调）。 */
    public static void onWorldTimeUpdate() {
        long now = System.currentTimeMillis();
        if (lastTimeUpdate > 0L) {
            float elapsedSeconds = (now - lastTimeUpdate) / 1000.0F;
            TICK_RATES[next] = elapsedSeconds <= 0.0F
                ? 20.0F
                : Mth.clamp(20.0F / elapsedSeconds, 0.0F, 20.0F);
            next = (next + 1) % SAMPLES;
        }
        lastTimeUpdate = now;
    }

    /** 进世界的时候清一遍（换服 / 重连之后上次的数据不算数）。 */
    public static void onJoin() {
        Arrays.fill(TICK_RATES, 0.0F);
        next = 0;
        joined = System.currentTimeMillis();
        lastTimeUpdate = joined;
    }

    /** 估算出来的服务端 tick/s（没有任何样本的时候返回 20）。 */
    public static float tickRate() {
        if (System.currentTimeMillis() - joined < WARM_UP_MS) return 20.0F;

        int count = 0;
        float sum = 0.0F;
        for (float rate : TICK_RATES) {
            if (rate <= 0.0F) continue;
            sum += rate;
            count++;
        }
        return count == 0 ? 20.0F : sum / count;
    }

    /** 距离上一个时间包过了多少秒。 */
    public static float timeSinceLastTick() {
        if (System.currentTimeMillis() - joined < WARM_UP_MS) return 0.0F;
        if (lastTimeUpdate < 0L) return 0.0F;
        return (System.currentTimeMillis() - lastTimeUpdate) / 1000.0F;
    }

    /** 服务端现在算不算卡了。 */
    public static boolean isLagging() {
        return timeSinceLastTick() >= LAG_THRESHOLD_SECONDS;
    }
}
