package org.six_coin.playerController.client.mine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 挖掘区域里的方块过滤器：白名单 / 黑名单 + 一串方块 id。
 *
 * <p>命令里写成一段 JSON：{@code {"mode": "whitelist", "list": ["minecraft:dirt"]}}。
 * 不写这段参数就是 {@link #ALL} —— 黑名单、什么都不排除，也就是所有方块都挖。
 */
public final class MineFilter {

    /** 一个方块都不排除：黑名单 + 空列表。 */
    public static final MineFilter ALL = new MineFilter(Mode.BLACKLIST, Set.of());

    /** 列表怎么写。 */
    public enum Mode {

        WHITELIST("whitelist", "白名单"),
        BLACKLIST("blacklist", "黑名单");

        private final String id;
        private final String label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** 名字 -> 模式，写错就抛 {@link IllegalArgumentException}（消息直接给玩家看）。 */
        public static Mode of(String text) {
            for (Mode mode : values()) {
                if (mode.id.equalsIgnoreCase(text.trim())) return mode;
            }
            throw new IllegalArgumentException("mode 只能是 \"whitelist\" 或 \"blacklist\"，现在是 \""
                + text + "\"");
        }
    }

    private final Mode mode;
    private final Set<Block> blocks;

    private MineFilter(Mode mode, Set<Block> blocks) {
        this.mode = mode;
        this.blocks = blocks;
    }

    public Mode mode() {
        return mode;
    }

    public Set<Block> blocks() {
        return blocks;
    }

    /** 这个方块在这一片区域里该不该挖。 */
    public boolean allows(Block block) {
        boolean listed = blocks.contains(block);
        return mode == Mode.WHITELIST ? listed : !listed;
    }

    /** 「白名单：3 种（minecraft:dirt, …）」这样一行，给命令和调试日志用。 */
    public String describe() {
        String list = blocks.stream()
            .limit(8)
            .map(block -> Registries.BLOCK.getId(block).toString())
            .collect(Collectors.joining(", "));
        if (blocks.size() > 8) list = list + ", …（还有 " + (blocks.size() - 8) + " 种）";

        if (blocks.isEmpty()) {
            return mode.label() + "：0 种"
                + (mode == Mode.BLACKLIST ? "（所有方块都挖）" : "（什么都不挖）");
        }
        return mode.label() + "：" + blocks.size() + " 种（" + list + "）";
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 把命令里那段 JSON 解析成过滤器。
     *
     * @throws IllegalArgumentException 写错了（消息直接给玩家看）
     */
    public static MineFilter parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("list 不能是空的；不写这一段就是所有方块都挖");
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(text.trim());
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("不是合法的 JSON：" + e.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException(
                "要写成一个对象：{\"mode\": \"whitelist\", \"list\": [\"minecraft:dirt\"]}");
        }

        JsonObject object = root.getAsJsonObject();
        Mode mode = Mode.of(readMode(object));

        Set<Block> blocks = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();

        JsonElement list = object.get("list");
        if (list != null && !list.isJsonNull()) {
            if (!list.isJsonArray()) {
                throw new IllegalArgumentException("list 里的 list 要是一个数组：[\"minecraft:dirt\", …]");
            }
            for (JsonElement element : list.getAsJsonArray()) {
                if (element == null || element.isJsonNull()) continue;
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("list 里的每一项都要是方块 id 字符串，例如 \"minecraft:dirt\"");
                }

                String id = element.getAsString().trim();
                Identifier identifier = Identifier.tryParse(id);
                Block block = identifier == null
                    ? null
                    : Registries.BLOCK.getOptionalValue(identifier).orElse(null);
                if (block == null) {
                    unknown.add(id);
                    continue;
                }
                blocks.add(block);
            }
        }

        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("没有这些方块：" + String.join(", ", unknown));
        }
        return new MineFilter(mode, Collections.unmodifiableSet(blocks));
    }

    private static String readMode(JsonObject object) {
        JsonElement mode = object.get("mode");
        if (mode == null || mode.isJsonNull() || !mode.isJsonPrimitive()
            || !mode.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("要写 mode：\"whitelist\" 或 \"blacklist\"");
        }
        return mode.getAsString();
    }
}
