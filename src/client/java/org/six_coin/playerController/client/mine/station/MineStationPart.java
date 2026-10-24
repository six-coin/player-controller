package org.six_coin.playerController.client.mine.station;

/**
 * 挖掘站的各个部分：json 里的字段名、中文名、高亮颜色、是单点还是一个列表、是不是可选的。
 *
 * <p>颜色是 {@code /pc mine station show} 用的 ARGB（随手挑的，要改就改这里的数字）：
 * 安全点青色、食物补给橙色、图腾补给黄色、产物收集绿色、工具缓存蓝色、黏液块缓存紫色、
 * 圆石缓存灰色、卸货潜影盒摆放处橙红色。
 *
 * <p>{@code optional} 只影响 {@code check} 的「有没有配置」那一关：可选的（产物收集）
 * 不配就不检查，别的部分不配一律算问题。
 *
 * <p>{@code container} 说明这个部分是不是「要打开数内容」的容器：
 * 卸货潜影盒摆放处不是容器（只要求那几个位置空着），所以是 {@code false}。
 */
public enum MineStationPart {

    SAFE_POINT("safe_point", "安全点", 0xFF00E5FF, false, false, false),
    FOOD_PROVIDER("food_provider", "食物补给", 0xFFFF9800, false, false, true),
    TOTEM_PROVIDER("totem_provider", "图腾补给", 0xFFFFFF00, false, false, true),
    ITEM_COLLECTOR("item_collector", "产物收集", 0xFF00FF00, true, true, true),
    TOOL_CACHE("tool_cache", "工具缓存", 0xFF3399FF, true, false, true),
    SLIME_CACHE("slime_cache", "黏液块缓存", 0xFFAB47BC, true, false, true),
    COBBLESTONE_CACHE("cobblestone_cache", "圆石缓存", 0xFF9E9E9E, true, false, true),
    UNLOADING_PLACEMENT("unloading_shulker_box_placement", "卸货潜影盒摆放处",
        0xFFFF7043, true, false, false);

    private final String id;
    private final String display;
    private final int color;
    private final boolean list;
    private final boolean optional;
    private final boolean container;

    MineStationPart(String id, String display, int color, boolean list, boolean optional,
                    boolean container) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.list = list;
        this.optional = optional;
        this.container = container;
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

    /** 这一部分可以不配（目前只有产物收集）。 */
    public boolean optional() {
        return optional;
    }

    /** 这一部分要打开数内容（{@code check} 会走过去开箱）。 */
    public boolean isContainer() {
        return container;
    }
}
