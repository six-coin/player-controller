package org.six_coin.playerController.client.mine.station;

/**
 * 挖掘站的各个部分：json 里的字段名、中文名、高亮颜色、是单点还是一个列表。
 *
 * <p>颜色是 {@code /pc mine complex station show} 用的 ARGB（随手挑的，要改就改这里的数字）：
 * 安全点青色、食物补给橙色、图腾补给黄色、产物收集绿色、工具缓存蓝色、黏液块缓存紫色。
 */
public enum MineStationPart {

    SAFE_POINT("safe_point", "安全点", 0xFF00E5FF, false),
    FOOD_PROVIDER("food_provider", "食物补给", 0xFFFF9800, false),
    TOTEM_PROVIDER("totem_provider", "图腾补给", 0xFFFFFF00, false),
    ITEM_COLLECTOR("item_collector", "产物收集", 0xFF00FF00, true),
    TOOL_CACHE("tool_cache", "工具缓存", 0xFF3399FF, true),
    SLIME_CACHE("slime_cache", "黏液块缓存", 0xFFAB47BC, true);

    private final String id;
    private final String display;
    private final int color;
    private final boolean list;

    MineStationPart(String id, String display, int color, boolean list) {
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
