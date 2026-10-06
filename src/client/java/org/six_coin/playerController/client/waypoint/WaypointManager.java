package org.six_coin.playerController.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PortalUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径点管理器：负责当前 world 编号的读写、显示开关、编辑模式、各种边的记录。
 *
 * <p>数据文件：{@code config/player-controller/world_<num>/waypoints.json}
 */
public final class WaypointManager {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final WaypointManager INSTANCE = new WaypointManager();

    private WaypointGraph graph = new WaypointGraph();
    private int loadedWorld = -1;

    /** 读档是否成功；失败时不允许写回，免得把原文件覆盖成空图。 */
    private boolean loadedOk = false;

    /** 是否高亮显示路径点与边。 */
    private boolean show = false;

    /** 路径点编辑模式：此时 /pc move 会自动记录路径点和边。 */
    private boolean editMode = false;

    private WaypointManager() {
    }

    public static WaypointManager get() {
        return INSTANCE;
    }

    public WaypointGraph graph() {
        return graph;
    }

    public int loadedWorld() {
        return loadedWorld;
    }

    public boolean isShowing() {
        return show;
    }

    public boolean toggleShow() {
        show = !show;
        return show;
    }

    public void setShow(boolean value) {
        show = value;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public boolean toggleEditMode() {
        editMode = !editMode;
        if (editMode) show = true;
        return editMode;
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    public static Path worldDir(int world) {
        return PlayerControllerConfig.configDir().resolve("world_" + world);
    }

    public static Path worldFile(int world) {
        return worldDir(world).resolve("waypoints.json");
    }

    public Path currentFile() {
        return worldFile(PlayerControllerConfig.getWorld());
    }

    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int world) {
        world = Math.max(1, world);
        loadedWorld = world;
        loadedOk = false;
        Path path = worldFile(world);

        if (!Files.exists(path)) {
            graph = new WaypointGraph();
            loadedOk = true;
            ChatUtils.debug("世界配置 world_" + world + " 还没有路径点文件，使用空图: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            // 旧格式的 edges 是一个数组；新格式是「编号 -> 边」的对象
            boolean oldFormat = root.has("edges") && root.get("edges").isJsonArray();

            if (oldFormat) {
                graph = migrateOldFormat(root);
                loadedOk = true;
                graph.rebuildIndex();
                save();
                ChatUtils.info("已把旧格式的 waypoints.json 迁移成新格式"
                    + "（路径点重新编号为 1,2,3...，边合并成带编号的对象）");
            } else {
                WaypointGraph loaded = GSON.fromJson(root, WaypointGraph.class);
                graph = loaded == null ? new WaypointGraph() : loaded;
                loadedOk = true;
                graph.rebuildIndex();
            }

            ChatUtils.debug("已加载 world_" + world + " 的路径点: "
                + graph.normalCount() + " 个点，" + graph.edgeCount() + " 条边");
        } catch (Exception e) {
            graph = new WaypointGraph();
            loadedOk = false;
            ChatUtils.error("读取路径点失败：" + e.getMessage()
                + " —— 这一轮不会覆盖原文件，请把文件发给我看看");
        }
    }

    /**
     * 把上一版的格式迁移过来。
     *
     * <p>旧格式：{@code edges} 是数组（{@code {a,b}}），{@code oneWayEdges} 是数组（{@code {from,to}}），
     * 出生点节点编号是 -1，另有 {@code spawns} / {@code currentSpawn}。
     *
     * <p>新格式：路径点 1,2,3...（出生点节点 0），边是「编号 -1,-2,-3... -&gt; {from,to,bi}」的对象，
     * 出生点用顶层的 {@code spawn} 记录对应的普通路径点。
     */
    private static WaypointGraph migrateOldFormat(JsonObject root) {
        WaypointGraph graph = new WaypointGraph();
        Map<Integer, Waypoint> byOldId = new HashMap<>();

        JsonObject oldWaypoints = root.has("waypoints") && root.get("waypoints").isJsonObject()
            ? root.getAsJsonObject("waypoints")
            : new JsonObject();

        List<Integer> oldIds = new ArrayList<>();
        for (String key : oldWaypoints.keySet()) {
            try {
                oldIds.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // 忽略不是数字的键
            }
        }
        oldIds.sort(Integer::compareTo);

        // 普通路径点：按旧编号升序建，新编号自然就是 1,2,3...
        for (int oldId : oldIds) {
            if (oldId < 0) continue;
            JsonObject o = oldWaypoints.getAsJsonObject(String.valueOf(oldId));
            if (o == null) continue;

            Waypoint w = graph.ensureWaypoint(
                optString(o, "dimension", DimensionUtils.OVERWORLD),
                new BlockPos(optInt(o, "x"), optInt(o, "y"), optInt(o, "z")));
            String name = optString(o, "name", null);
            if (name != null && WaypointGraph.isValidName(name)) {
                graph.setName(w.id(), name);
            }
            byOldId.put(oldId, w);
        }

        // 旧的 -1 号：出生点，位置对应的普通路径点就是新的出生点目标
        Waypoint spawnTarget = null;
        String oldSpawnName = null;
        for (int oldId : oldIds) {
            if (oldId >= 0) continue;
            JsonObject o = oldWaypoints.getAsJsonObject(String.valueOf(oldId));
            if (o == null) continue;

            spawnTarget = graph.ensureWaypoint(
                optString(o, "dimension", DimensionUtils.OVERWORLD),
                new BlockPos(optInt(o, "x"), optInt(o, "y"), optInt(o, "z")));
            oldSpawnName = optString(o, "name", null);
            byOldId.put(oldId, spawnTarget);
        }
        if (spawnTarget != null) {
            if (oldSpawnName != null && !spawnTarget.hasName()
                && WaypointGraph.isValidName(oldSpawnName)) {
                graph.setName(spawnTarget.id(), oldSpawnName);
            }
            graph.setSpawn(spawnTarget.id());
        }

        // 无向边
        if (root.has("edges") && root.get("edges").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("edges")) {
                if (!element.isJsonObject()) continue;
                JsonObject o = element.getAsJsonObject();
                Waypoint a = byOldId.get(optInt(o, "a"));
                Waypoint b = byOldId.get(optInt(o, "b"));
                if (a == null || b == null || a.id() == b.id()) continue;
                graph.addEdge(a.id(), b.id(), true);
            }
        }

        // 单向边
        if (root.has("oneWayEdges") && root.get("oneWayEdges").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("oneWayEdges")) {
                if (!element.isJsonObject()) continue;
                JsonObject o = element.getAsJsonObject();
                Waypoint a = byOldId.get(optInt(o, "from"));
                Waypoint b = byOldId.get(optInt(o, "to"));
                if (a == null || b == null || a.id() == b.id()) continue;
                graph.addEdge(a.id(), b.id(), false);
            }
        }

        return graph;
    }

    private static int optInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return 0;
        try {
            return element.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    @Nullable
    private static String optString(JsonObject object, String key, @Nullable String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        return element.getAsString();
    }

    /** 切换世界配置：先把旧的存起来，再读新的。 */
    public void switchWorld(int world) {
        save();
        PlayerControllerConfig.setWorld(world);
        load(world);
        ChatUtils.info("已切换到世界配置 world_" + world
            + "（" + graph.normalCount() + " 个路径点，" + graph.edgeCount() + " 条边）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        Path path = worldFile(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(graph, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存路径点失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 编辑模式记录
    // ------------------------------------------------------------------

    /**
     * 编辑模式下把一段移动记录成双向边（会把重叠的边切开）。
     *
     * @return 是否真的记录了
     */
    public boolean recordMove(String dimension, BlockPos from, BlockPos to) {
        if (!editMode) return false;
        if (from.equals(to)) return false;
        if (WaypointGraph.sharedAxis(from, to) == null) return false;

        graph.addSegment(dimension, from, to);
        save();
        ChatUtils.debug("编辑模式：已记录 " + from.toShortString() + " <-> " + to.toShortString());
        return true;
    }

    /**
     * 编辑模式下：终点在末地传送门处时，连一条单边过去。
     *
     * <p>末地传送门方块两个方向用的是同一个方块，所以必须按维度区分：
     * <ul>
     *   <li>在主世界（或非末地维度）：去末地出生平台 (100, 49, 0) @ the_end；</li>
     *   <li>在末地：那是回主世界的祭坛，落点是玩家的重生点 —— 连到出生点节点（0 号）。</li>
     * </ul>
     *
     * @return 是否新建了单向边
     */
    public boolean recordEndPortalLink(String dimension, BlockPos endPos) {
        if (!editMode) return false;
        if (MinecraftClient.getInstance().world == null) return false;

        // 只看自己那一格和下面一格
        if (!PortalUtils.endPortalNear(endPos)) return false;

        Waypoint from = graph.ensureWaypoint(dimension, endPos);
        Waypoint to;

        if (DimensionUtils.END.equals(dimension)) {
            to = graph.spawnNode();
            if (to == null) {
                ChatUtils.error("末地祭坛 + " + endPos.toShortString()
                    + " 需要连到出生点，但你还没有设置出生点；"
                    + "请先用 /pc w spawn_set_by_id 或在出生点的路径点上用 /pc w spawn_set_by_name");
                return false;
            }
        } else {
            to = graph.ensureWaypoint(DimensionUtils.END, DimensionUtils.endSpawnPos());
        }

        if (graph.addEdge(from.id(), to.id(), false) == null) {
            return false;
        }

        save();
        ChatUtils.debug("编辑模式：末地传送门 " + DimensionUtils.display(dimension) + " "
            + endPos.toShortString() + " → " + DimensionUtils.display(to.dimension()) + " "
            + to.coordString() + " 单向边");
        return true;
    }

    /**
     * 编辑模式下：玩家穿过下界传送门后，把传送前所在的路径点和落地方块连一条 0 长度双向边。
     *
     * @return 是否新建了传送门边
     */
    public boolean recordPortalLink(Waypoint source, String arrivalDimension, BlockPos arrivalPos) {
        if (!editMode) return false;
        if (source == null) return false;
        if (source.dimension().equals(arrivalDimension)) return false;

        Waypoint arrival = graph.ensureWaypoint(arrivalDimension, arrivalPos);
        if (graph.addEdge(source.id(), arrival.id(), true) == null) {
            return false;
        }

        save();
        ChatUtils.info("编辑模式：记录下界传送门 " + DimensionUtils.display(source.dimension())
            + " " + source.coordString() + " <-> " + DimensionUtils.display(arrivalDimension)
            + " " + arrival.coordString() + "（长度 0，紫色）");
        return true;
    }

    // ------------------------------------------------------------------
    // 出生点
    // ------------------------------------------------------------------

    /** 把一个普通路径点设为当前出生点。 */
    public boolean setSpawn(int id) {
        if (!graph.setSpawn(id)) return false;
        save();
        Waypoint w = graph.get(id);
        if (w != null) {
            ChatUtils.debug("当前出生点已设为 #" + id + " "
                + DimensionUtils.display(w.dimension()) + " " + w.coordString());
        }
        return true;
    }

    /** 取消出生点设置。 */
    public boolean clearSpawn() {
        if (!graph.clearSpawn()) return false;
        save();
        ChatUtils.debug("已取消出生点设置");
        return true;
    }
}
