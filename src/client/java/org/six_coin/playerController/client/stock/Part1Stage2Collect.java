package org.six_coin.playerController.client.stock;

import org.six_coin.playerController.client.stock.station.StationPart;

/**
 * 备货第一部分阶段2（取货）：按 {@code final_final.json} 去容器里取货，
 * 中途卸货时散装成品放 item_final（潜影盒放 item_storage）。
 *
 * <p>取货那一整套在 {@link Stage2Collect} 里。
 */
final class Part1Stage2Collect extends Stage2Collect {

    Part1Stage2Collect(String task, StockRun run) {
        super(task, run, "第一部分阶段2（取货）", null,
            StationPart.ITEM_STORAGE, StationPart.ITEM_FINAL, false);
    }
}
