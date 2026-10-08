package org.six_coin.playerController.client.station;

/**
 * 工作站的各个部分：json 里的字段名、中文名、高亮颜色、是单点还是一个列表。
 *
 * <p>颜色是 {@code /pc station show} 用的 ARGB：
 * 空潜影盒提供处绿色、物资存储地黄色、最终产物地蓝色、工作台和潜影盒摆放处灰色
 * （这三组是需求里指定的）；站立点青色、切石机橙色、任务前物品暂存处洋红是我随手挑的，
 * 要改就改这里的数字。
 */
public enum StationPart {

    STAND_POINT("stand_point", "站立点", 0xFF00E5FF, false),
    CRAFTING_TABLE("crafting_table", "工作台", 0xFF9E9E9E, false),
    STONECUTTER("stonecutter", "切石机", 0xFFFF9800, false),
    ITEM_TEMP("item_temp", "任务前物品暂存处", 0xFFFF00FF, false),
    SHULKER_BOX_PROVIDER("shulker_box_provider", "空潜影盒提供处", 0xFF00FF00, false),
    SHULKER_BOX_PLACEMENT("shulker_box_placement", "潜影盒摆放处", 0xFF9E9E9E, true),
    ITEM_STORAGE("item_storage", "物资存储地", 0xFFFFFF00, true),
    ITEM_FINAL("item_final", "最终产物地", 0xFF3399FF, true);

    private final String id;
    private final String display;
    private final int color;
    private final boolean list;

    StationPart(String id, String display, int color, boolean list) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.list = list;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public int color() {
        return color;
    }

    public boolean isList() {
        return list;
    }
}
