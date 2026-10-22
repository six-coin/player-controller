package org.six_coin.playerController.client.mine.station;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code world_<n>/mine/station.json} 的结构。
 *
 * <p>字段名（snake_case）就是文件里的键名 —— Gson 直接按字段名读写，所以别改名；
 * 没设过的单点字段是 null，Gson 会直接不写出来。
 */
public final class MineStationData {

    private MinePlace safe_point;
    private MinePlace food_provider;
    private MinePlace totem_provider;
    private List<MinePlace> item_collector = new ArrayList<>();
    private List<MinePlace> tool_cache = new ArrayList<>();
    private List<MinePlace> slime_cache = new ArrayList<>();
    private List<MinePlace> cobblestone_cache = new ArrayList<>();
    private List<MinePlace> unloading_shulker_box_placement = new ArrayList<>();

    MinePlace safePoint() {
        return safe_point;
    }

    void safePoint(MinePlace value) {
        this.safe_point = value;
    }

    MinePlace foodProvider() {
        return food_provider;
    }

    void foodProvider(MinePlace value) {
        this.food_provider = value;
    }

    MinePlace totemProvider() {
        return totem_provider;
    }

    void totemProvider(MinePlace value) {
        this.totem_provider = value;
    }

    List<MinePlace> itemCollector() {
        if (item_collector == null) item_collector = new ArrayList<>();
        return item_collector;
    }

    List<MinePlace> toolCache() {
        if (tool_cache == null) tool_cache = new ArrayList<>();
        return tool_cache;
    }

    List<MinePlace> slimeCache() {
        if (slime_cache == null) slime_cache = new ArrayList<>();
        return slime_cache;
    }

    List<MinePlace> cobblestoneCache() {
        if (cobblestone_cache == null) cobblestone_cache = new ArrayList<>();
        return cobblestone_cache;
    }

    List<MinePlace> unloadingShulkerBoxPlacement() {
        if (unloading_shulker_box_placement == null) unloading_shulker_box_placement = new ArrayList<>();
        return unloading_shulker_box_placement;
    }

    /**
     * 按「每个部分内部从 1 开始」重新编 id：单点固定 1，列表按顺序 1..n。
     *
     * <p>读文件和写文件前都会调一次，所以文件里的 id 永远是连着的。
     */
    void resequence() {
        if (safe_point != null) safe_point.id(1);
        if (food_provider != null) food_provider.id(1);
        if (totem_provider != null) totem_provider.id(1);

        int id = 1;
        for (MinePlace place : itemCollector()) place.id(id++);
        id = 1;
        for (MinePlace place : toolCache()) place.id(id++);
        id = 1;
        for (MinePlace place : slimeCache()) place.id(id++);
        id = 1;
        for (MinePlace place : cobblestoneCache()) place.id(id++);
        id = 1;
        for (MinePlace place : unloadingShulkerBoxPlacement()) place.id(id++);
    }
}
