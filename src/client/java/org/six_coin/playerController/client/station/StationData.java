package org.six_coin.playerController.client.station;

import java.util.ArrayList;
import java.util.List;

/**
 * station.json 的结构。
 *
 * <p>字段名（snake_case）就是文件里的键名 —— Gson 直接按字段名读写，所以别改名；
 * 没设过的单点字段是 null，Gson 会直接不写出来。
 */
public final class StationData {

    private StationPos stand_point;
    private StationPos crafting_table;
    private StationPos stonecutter;
    private StationPos item_temp;
    private StationPos shulker_box_provider;
    private List<StationPos> shulker_box_placement = new ArrayList<>();
    private List<StationPos> item_storage = new ArrayList<>();
    private List<StationPos> item_final = new ArrayList<>();

    StationPos standPoint() {
        return stand_point;
    }

    void standPoint(StationPos value) {
        this.stand_point = value;
    }

    StationPos craftingTable() {
        return crafting_table;
    }

    void craftingTable(StationPos value) {
        this.crafting_table = value;
    }

    StationPos stonecutter() {
        return stonecutter;
    }

    void stonecutter(StationPos value) {
        this.stonecutter = value;
    }

    StationPos itemTemp() {
        return item_temp;
    }

    void itemTemp(StationPos value) {
        this.item_temp = value;
    }

    StationPos shulkerBoxProvider() {
        return shulker_box_provider;
    }

    void shulkerBoxProvider(StationPos value) {
        this.shulker_box_provider = value;
    }

    List<StationPos> shulkerBoxPlacement() {
        if (shulker_box_placement == null) shulker_box_placement = new ArrayList<>();
        return shulker_box_placement;
    }

    List<StationPos> itemStorage() {
        if (item_storage == null) item_storage = new ArrayList<>();
        return item_storage;
    }

    List<StationPos> itemFinal() {
        if (item_final == null) item_final = new ArrayList<>();
        return item_final;
    }

    /**
     * 按「每个部分内部从 1 开始」重新编 id：单点固定 1，列表按顺序 1..n。
     *
     * <p>读文件和写文件前都会调一次，所以文件里的 id 永远是连着的。
     */
    void resequence() {
        if (stand_point != null) stand_point.id(1);
        if (crafting_table != null) crafting_table.id(1);
        if (stonecutter != null) stonecutter.id(1);
        if (item_temp != null) item_temp.id(1);
        if (shulker_box_provider != null) shulker_box_provider.id(1);

        int id = 1;
        for (StationPos pos : shulkerBoxPlacement()) pos.id(id++);
        id = 1;
        for (StationPos pos : itemStorage()) pos.id(id++);
        id = 1;
        for (StationPos pos : itemFinal()) pos.id(id++);
    }
}
