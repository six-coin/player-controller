package org.six_coin.playerController.client.mine.station;

/**
 * 挖掘站的各个部分：json 里的字段名、中文名、高亮颜色、是单点还是一个列表、是不是可选的。
 *
 * <p>颜色是 {@code /pc mine station show} 用的 ARGB（随手挑的，要改就改这里的数字）。
 *
 * <p>{@code optional} 只影响 {@code check} 的「有没有配置」那一关：可选的（产物收集）
 * 不配就不检查，别的部分不配一律算问题。
 *
 * <p>{@code container} 说明这个部分是不是「要打开数内容」的容器：
 * 三个潜影盒摆放处不是容器（只要求那几个位置空着），所以是 {@code false}。
 *
 * <p>{@code placement} 说明这个部分是不是「潜影盒摆放处」：这一类不查 {@code access_position}，
 * 而是要求<b>摆放处上面那一格是路径点</b>，并且那个路径点要和安全点双向可达
 * （见 {@code MineStationCheckAction}）。产物收集不是摆放处，它照旧按落点算。
 */
public enum MineStationPart {

    SAFE_POINT("safe_point", "安全点", 0xFF00E5FF, false, false, false, false),
    FOOD_PROVIDER("food_provider", "食物补给", 0xFFFF9800, false, false, true, false),
    TOTEM_PROVIDER("totem_provider", "图腾补给", 0xFFFFFF00, false, false, true, false),
    EMPTY_SHULKER_BOX_RECYCLER("empty_shulker_box_recycler", "空潜影盒回收处",
        0xFF80DEEA, false, false, true, false),
    INVENTORY_TEMP("inventory_temp", "物品栏暂存处", 0xFFA5D6A7, false, false, true, false),
    ITEM_COLLECTOR("item_collector", "产物收集", 0xFF00FF00, true, true, true, false),
    TOOL_CACHE("tool_cache", "工具缓存", 0xFF3399FF, true, false, true, false),
    SLIME_CACHE("slime_cache", "黏液块缓存", 0xFFAB47BC, true, false, true, false),
    COBBLESTONE_CACHE("cobblestone_cache", "圆石缓存", 0xFF9E9E9E, true, false, true, false),
    ITEM_TEMP("item_temp", "任务前物品暂存处", 0xFFFFD54F, true, false, true, false),
    UNLOADING_PLACEMENT("unloading_shulker_box_placement", "卸货潜影盒摆放处",
        0xFFFF7043, true, false, false, true),
    SUPPLEMENT_PLACEMENT("supplement_shulker_box_placement", "补充潜影盒摆放处",
        0xFF8D6E63, true, false, false, true),
    TEMP_PLACEMENT("temp_shulker_box_placement", "临时潜影盒摆放处",
        0xFF7E57C2, true, false, false, true);

    private final String id;
    private final String display;
    private final int color;
    private final boolean list;
    private final boolean optional;
    private final boolean container;
    private final boolean placement;

    MineStationPart(String id, String display, int color, boolean list, boolean optional,
                    boolean container, boolean placement) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.list = list;
        this.optional = optional;
        this.container = container;
        this.placement = placement;
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

    /** 这一部分是潜影盒摆放处（不查落点，要求上面那一格是路径点）。 */
    public boolean isPlacement() {
        return placement;
    }
}
