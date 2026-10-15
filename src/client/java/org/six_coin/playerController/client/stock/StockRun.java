package org.six_coin.playerController.client.stock;

import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.stock.station.StationState;

/**
 * 一次备货任务里，各步共享的运行期数据。
 *
 * <p>现在只有 station_data（{@link StationState}）：它**不落盘**，取货和分盒会往容器里放东西、
 * 从容器里拿东西，这些改动只记在这一份内存数据里；后面的步骤接着用同一份，
 * 才看得到前面刚放进去的潜影盒、算得准哪里还剩多少格。
 */
final class StockRun {

    @Nullable
    private StationState state;

    /** 现在这份 station_data；还没建起来就是 null。 */
    @Nullable
    StationState state() {
        return state;
    }

    void state(StationState state) {
        this.state = state;
    }
}
