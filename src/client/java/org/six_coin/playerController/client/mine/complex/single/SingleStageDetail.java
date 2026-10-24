package org.six_coin.playerController.client.mine.complex.single;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /pc mine debug_chunk_miner <detail>} 的那份 {@code detail}。
 *
 * <p>格式（{@code detail} 必填）：
 * <pre>
 * {
 *   "item_collector_list": ["minecraft:coal", "minecraft:diamond"],
 *   "north": "cobblestone", "south": "cobblestone",
 *   "west": "auto", "east": "auto", "bottom": "auto",
 *   "levels": 8
 * }
 * </pre>
 *
 * <p>含义：
 * <ul>
 *   <li>{@code item_collector_list}：要收集的物品 id（和以前的参数一样）；</li>
 *   <li>{@code north / south / west / east / bottom}：五个方向边缘区域（那一圈「壳」）怎么填，
 *       只有两种取值 ——
 *       <ul>
 *         <li>{@code cobblestone}：这个方向的边缘用<b>圆石</b>填（不管外面那个子区块空不空）；</li>
 *         <li>{@code auto}：和以前一样 —— 挨着的子区块整块是空的就不填，否则填黏液块 / 树脂块。
 *             底部的 {@code auto} 是「无论底下是不是空的都填黏液块」；</li>
 *       </ul>
 *       底部还有一条固定规则：<b>不是最底层</b>的时候底部一律填黏液块（下一轮要挖掉它）；
 *       只有**最底层**才看 {@code bottom} 的配置。</li>
 *   <li>{@code levels}：挖几层（一个 level = 2 格），实际挖 {@code 2 * levels} 格；
 *       目标子区块一共 16 格高，所以合法范围 1~8（不写就是 8）。</li>
 * </ul>
 *
 * <p>方向按原版来：{@code north = -z}、{@code south = +z}、{@code west = -x}、{@code east = +x}、
 * {@code bottom = -y}。
 */
public final class SingleStageDetail {

    /** 目标子区块一共 16 格高，一个 level 挖 2 格。 */
    public static final int MAX_LEVELS = 8;

    public static final int DEFAULT_LEVELS = 8;

    /** 边缘区域怎么填。 */
    public enum WallMode {

        /** 和以前一样：挨着的子区块整块是空的就不填，否则填黏液块 / 树脂块。 */
        AUTO("auto"),
        /** 这个方向的边缘用圆石填（不管外面空不空）。 */
        COBBLESTONE("cobblestone");

        private final String id;

        WallMode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String label() {
            return this == AUTO ? "自动（黏液块）" : "圆石";
        }

        static WallMode parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            for (WallMode mode : values()) {
                if (mode.id.equals(value)) return mode;
            }
            throw new IllegalArgumentException("方向只能写 cobblestone 或 auto（现在是「" + raw + "」）");
        }
    }

    private final List<String> itemCollectorList;
    private final Map<Direction, WallMode> walls;
    private final int levels;

    private SingleStageDetail(List<String> itemCollectorList, Map<Direction, WallMode> walls, int levels) {
        this.itemCollectorList = List.copyOf(itemCollectorList);
        this.walls = walls;
        this.levels = levels;
    }

    /** 要收集的物品 id（保持文件里的顺序）。 */
    public List<String> itemCollectorList() {
        return itemCollectorList;
    }

    /**
     * 直接构造一份 detail（不走 JSON）。
     *
     * <p>{@code d_start_mining} 用它：收集清单就是当前 item_collector 里那些物品，
     * <b>可以是空的</b>（没配产物收集的时候就是什么都不收）。命令那一侧仍然要求非空
     * （见 {@link #parse}）。
     */
    public static SingleStageDetail of(java.util.Collection<String> collectorItems,
                                       Map<Direction, WallMode> walls, int levels) {
        Set<String> ids = new LinkedHashSet<>();
        if (collectorItems != null) {
            for (String id : collectorItems) {
                if (id == null || id.isBlank()) continue;
                ids.add(id.trim());
            }
        }
        Map<Direction, WallMode> copy = new EnumMap<>(Direction.class);
        if (walls != null) copy.putAll(walls);
        int value = Math.max(1, Math.min(MAX_LEVELS, levels));
        return new SingleStageDetail(new ArrayList<>(ids), copy, value);
    }

    /** 挖几层（1~{@value #MAX_LEVELS}）。 */
    public int levels() {
        return levels;
    }

    /** 某个方向的边缘怎么填。 */
    public WallMode wall(Direction direction) {
        return walls.getOrDefault(direction, WallMode.AUTO);
    }

    /** 底部（最底层）怎么填。 */
    public WallMode bottom() {
        return wall(Direction.DOWN);
    }

    public String describe() {
        return "收集 " + itemCollectorList.size() + " 种物品；边缘 北=" + wall(Direction.NORTH).id()
            + " 南=" + wall(Direction.SOUTH).id() + " 西=" + wall(Direction.WEST).id()
            + " 东=" + wall(Direction.EAST).id() + " 底=" + bottom().id()
            + "；levels=" + levels + "（挖 " + (levels * 2) + " 格高）";
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析 {@code detail}。
     *
     * @throws IllegalArgumentException 格式不对（消息直接给玩家看）
     */
    public static SingleStageDetail parse(String raw) {
        JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("不是合法的 JSON：" + e.getMessage());
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("要写成 { ... } 这样的 JSON 对象");
        }
        JsonObject object = root.getAsJsonObject();

        // 收集清单
        Set<String> ids = new LinkedHashSet<>();
        JsonElement list = object.get("item_collector_list");
        if (list != null && list.isJsonArray()) {
            JsonArray array = list.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonPrimitive()) continue;
                String id = element.getAsString().trim();
                if (!id.isEmpty()) ids.add(id);
            }
        } else if (list != null && list.isJsonPrimitive()) {
            String id = list.getAsString().trim();
            if (!id.isEmpty()) ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("item_collector_list 是空的（写成 [\"minecraft:coal\", ...]）");
        }

        // 五个方向
        Map<Direction, WallMode> walls = new EnumMap<>(Direction.class);
        readWall(object, walls, "north", Direction.NORTH);
        readWall(object, walls, "south", Direction.SOUTH);
        readWall(object, walls, "west", Direction.WEST);
        readWall(object, walls, "east", Direction.EAST);
        readWall(object, walls, "bottom", Direction.DOWN);

        // levels
        int levels = DEFAULT_LEVELS;
        JsonElement levelsElement = object.get("levels");
        if (levelsElement != null && levelsElement.isJsonPrimitive()) {
            try {
                levels = levelsElement.getAsInt();
            } catch (Exception e) {
                throw new IllegalArgumentException("levels 要写整数");
            }
        }
        if (levels < 1) throw new IllegalArgumentException("levels 至少是 1");
        levels = Math.min(MAX_LEVELS, levels);

        return new SingleStageDetail(new ArrayList<>(ids), walls, levels);
    }

    private static void readWall(JsonObject object, Map<Direction, WallMode> walls,
                                 String key, Direction direction) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            walls.put(direction, WallMode.AUTO);
            return;
        }
        try {
            walls.put(direction, WallMode.parse(element.getAsString()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + "：" + e.getMessage());
        }
    }
}
