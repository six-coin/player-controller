package org.six_coin.playerController.client.stock;

import org.six_coin.playerController.client.stock.station.StationPart;

/**
 * 备货第二部分阶段2（取货）：按 {@code final_material.json} 去容器里取货，
 * 中途卸货时潜影盒和散装材料都放 item_storage。
 *
 * <p>取货那一整套在 {@link Stage2Collect} 里；这一步接着用前面的 station_data
 * （第一部分阶段3 过完的那一份）。
 */
final class Part2Stage2Collect extends Stage2Collect {

    Part2Stage2Collect(String task, StockRun run) {
        super(task, run, "第二部分阶段2（取货）",
            StockManager.get().currentFinalMaterialFile(task),
            StationPart.ITEM_STORAGE, StationPart.ITEM_STORAGE, true);
    }
}
