package org.six_coin.playerController.client.mine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /pc mine simple} 用的方块过滤器，存在这三份文件里（全局，不分世界）：
 *
 * <pre>
 * config/player-controller/mine/mode.json       {"mode": "blacklist"}          默认黑名单
 * config/player-controller/mine/whitelist.json  ["minecraft:oak_log", …]       默认空
 * config/player-controller/mine/blacklist.json  ["minecraft:bedrock"]          默认只有基岩
 * </pre>
 *
 * <p>文件不存在就按默认值建一份；文件写坏了就报错并用默认值，**不会覆盖**原文件。
 * 用 {@code /pc mine list ...} 改，也可以直接改文件（重进世界 / 重连时重新读）。
 */
public final class MineFilterConfig {

    private static final String FOLDER = "mine";
    private static final String MODE_FILE = "mode.json";
    private static final String WHITELIST_FILE = "whitelist.json";
    private static final String BLACKLIST_FILE = "blacklist.json";

    private static final List<String> DEFAULT_BLACKLIST = List.of("minecraft:bedrock");

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final MineFilterConfig INSTANCE = new MineFilterConfig();

    private MineFilter.Mode mode = MineFilter.Mode.BLACKLIST;
    private final Set<Block> whitelist = new LinkedHashSet<>();
    private final Set<Block> blacklist = new LinkedHashSet<>();

    private MineFilterConfig() {
    }

    public static MineFilterConfig get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    public static Path dir() {
        return PlayerControllerConfig.configDir().resolve(FOLDER);
    }

    public static Path modeFile() {
        return dir().resolve(MODE_FILE);
    }

    public static Path whitelistFile() {
        return dir().resolve(WHITELIST_FILE);
    }

    public static Path blacklistFile() {
        return dir().resolve(BLACKLIST_FILE);
    }

    // ------------------------------------------------------------------
    // 读 / 写
    // ------------------------------------------------------------------

    /** 读三份文件（缺的按默认值建）；启动时和进世界时各读一次。 */
    public void load() {
        mode = MineFilter.Mode.BLACKLIST;
        whitelist.clear();
        blacklist.clear();

        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            ChatUtils.error("创建挖掘过滤器文件夹失败：" + e.getMessage());
            return;
        }

        loadMode();
        loadBlocks(whitelistFile(), whitelist, List.of());
        loadBlocks(blacklistFile(), blacklist, DEFAULT_BLACKLIST);

        ChatUtils.debug("挖掘过滤器：mode=" + mode.id() + "，白名单 " + whitelist.size()
            + " 种，黑名单 " + blacklist.size() + " 种");
    }

    private void loadMode() {
        Path path = modeFile();
        if (!Files.exists(path)) {
            mode = MineFilter.Mode.BLACKLIST;
            saveMode();
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                throw new IllegalStateException("要写成一个对象：{\"mode\": \"blacklist\"}");
            }
            JsonElement value = root.getAsJsonObject().get("mode");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException("要写 mode：\"whitelist\" 或 \"blacklist\"");
            }
            mode = MineFilter.Mode.of(value.getAsString());
        } catch (Exception e) {
            mode = MineFilter.Mode.BLACKLIST;
            ChatUtils.error("读取 " + path + " 失败：" + e.getMessage()
                + " —— 这一轮按黑名单处理，原文件没有改动");
        }
    }

    private void loadBlocks(Path path, Set<Block> target, List<String> defaults) {
        if (!Files.exists(path)) {
            target.addAll(resolve(defaults));
            saveBlocks(path, target);
            return;
        }

        List<String> unknown = new ArrayList<>();
        Set<Block> loaded = new LinkedHashSet<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonArray()) {
                throw new IllegalStateException("要写成一个方块 id 数组：[\"minecraft:stone\", …]");
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IllegalStateException("每一项都要是方块 id 字符串，例如 \"minecraft:stone\"");
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
                loaded.add(block);
            }
        } catch (Exception e) {
            target.addAll(resolve(defaults));
            ChatUtils.error("读取 " + path + " 失败：" + e.getMessage()
                + " —— 这一轮先用默认值，原文件没有改动");
            return;
        }

        target.addAll(loaded);
        if (!unknown.isEmpty()) {
            ChatUtils.error(path.getFileName() + " 里有不认识的方块（已跳过）："
                + String.join(", ", unknown));
        }
    }

    private static List<Block> resolve(List<String> ids) {
        List<Block> blocks = new ArrayList<>();
        for (String id : ids) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier == null) continue;
            Registries.BLOCK.getOptionalValue(identifier).ifPresent(blocks::add);
        }
        return blocks;
    }

    /** 把三份文件都写一遍（缺的会建出来）。 */
    public void save() {
        saveMode();
        saveBlocks(whitelistFile(), whitelist);
        saveBlocks(blacklistFile(), blacklist);
    }

    private void saveMode() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", mode.id());
        write(modeFile(), object);
    }

    private static void saveBlocks(Path path, Set<Block> blocks) {
        List<String> ids = new ArrayList<>();
        for (Block block : blocks) ids.add(Registries.BLOCK.getId(block).toString());
        write(path, GSON.toJsonTree(ids));
    }

    private static void write(Path path, JsonElement element) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(element, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存 " + path + " 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查询 / 修改
    // ------------------------------------------------------------------

    public MineFilter.Mode mode() {
        return mode;
    }

    public void setMode(MineFilter.Mode value) {
        mode = value;
        saveMode();
    }

    /** 这张表里的方块（按加进来的顺序）。 */
    public Set<Block> blocks(MineFilter.Mode which) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
            which == MineFilter.Mode.WHITELIST ? whitelist : blacklist));
    }

    /** 某张表里有没有这个方块。 */
    public boolean contains(MineFilter.Mode which, Block block) {
        return (which == MineFilter.Mode.WHITELIST ? whitelist : blacklist).contains(block);
    }

    /** 往表里加一个方块；本来就有返回 false。 */
    public boolean add(MineFilter.Mode which, Block block) {
        Set<Block> target = which == MineFilter.Mode.WHITELIST ? whitelist : blacklist;
        if (!target.add(block)) return false;
        saveBlocks(which == MineFilter.Mode.WHITELIST ? whitelistFile() : blacklistFile(), target);
        return true;
    }

    /** 从表里删一个方块；本来没有返回 false。 */
    public boolean remove(MineFilter.Mode which, Block block) {
        Set<Block> target = which == MineFilter.Mode.WHITELIST ? whitelist : blacklist;
        if (!target.remove(block)) return false;
        saveBlocks(which == MineFilter.Mode.WHITELIST ? whitelistFile() : blacklistFile(), target);
        return true;
    }

    /** 当前生效的过滤器（快照）。 */
    public MineFilter filter() {
        return MineFilter.of(mode, mode == MineFilter.Mode.WHITELIST ? whitelist : blacklist);
    }

    /** 「黑名单（3 种：…）」这样一行，给命令用。 */
    public String describe(MineFilter.Mode which) {
        Set<Block> blocks = which == MineFilter.Mode.WHITELIST ? whitelist : blacklist;
        if (blocks.isEmpty()) return which.label() + "：0 种（空）";

        MineFilter snapshot = MineFilter.of(which, blocks);
        return which.label() + "：" + blocks.size() + " 种" + snapshot.listText(8);
    }
}
