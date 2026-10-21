package org.six_coin.playerController.client.feature;

import net.minecraft.client.MinecraftClient;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 换维度（走传送门）之后的等待。
 *
 * <p>刚被传过去的那一刻，服务端那边玩家位置 / 区块都还没同步稳，这时候接着飞往往会被拉回去
 * （表现就是卡一下、人被拽回传送门附近）。所以这里记一个「还有多少 tick 不许动」，
 * 移动类代码（{@link FlightVelocity} / {@code MoveDriver}）看到它就先原地悬停。
 *
 * <p>等待时间 = 配置里的 {@code actions.wait_ticks_after_dimension_teleport}（默认 40 tick = 2 秒）。
 * 只挡「移动」，不影响开箱子 / 合成那些动作。
 */
public final class DimensionWait {

    /** 上一次看到的维度（null = 还没进世界 / 刚进世界，第一次不算「传送」）。 */
    private static String lastDimension;

    /** 还要等多少 tick 才允许移动。 */
    private static int waitTicksLeft;

    private DimensionWait() {
    }

    /** 每个客户端 tick 调一次（END_CLIENT_TICK）。 */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            // 退出世界 / 还在加载：清干净，免得下次进世界被算成「换维度」
            lastDimension = null;
            waitTicksLeft = 0;
            return;
        }

        if (waitTicksLeft > 0) waitTicksLeft--;

        String now = DimensionUtils.current();
        if (lastDimension != null && !lastDimension.equals(now)) {
            int wait = PlayerControllerConfig.getMoveDimensionWaitTicks();
            waitTicksLeft = Math.max(0, wait);
            if (waitTicksLeft > 0) {
                ChatUtils.debug("换维度了（" + DimensionUtils.display(lastDimension) + " → "
                    + DimensionUtils.display(now) + "），先等 " + waitTicksLeft
                    + " tick 再动（服务端还没同步好，马上走容易被拉回去）");
            } else {
                ChatUtils.debug("换维度了（" + DimensionUtils.display(lastDimension) + " → "
                    + DimensionUtils.display(now) + "），配置里等待是 0 tick，直接继续");
            }
        }
        lastDimension = now;
    }

    /** 现在是不是还在「换维度之后的等待」里。 */
    public static boolean waiting() {
        return waitTicksLeft > 0;
    }

    /** 还要等多少 tick。 */
    public static int ticksLeft() {
        return waitTicksLeft;
    }
}
