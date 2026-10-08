package org.six_coin.playerController.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
            WaypointGraph loaded = GSON.fromJson(reader, WaypointGraph.class);
            graph = loaded == null ? new WaypointGraph() : loaded;
            loadedOk = true;
            graph.rebuildIndex();

            ChatUtils.debug("已加载 world_" + world + " 的路径点: "
                + graph.normalCount() + " 个点，" + graph.edgeCount() + " 条边");
        } catch (Exception e) {
            graph = new WaypointGraph();
            loadedOk = false;
            ChatUtils.error("读取路径点失败：" + e.getMessage()
                + " —— 这一轮不会覆盖原文件，请把文件发给我看看");
        }
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
     * 在这里新建一个路径点（已经有了就复用），并把穿过这一格的边从中间切开，然后存盘。
     *
     * <p>就是「newwaypoint」：{@link WaypointGraph#addWaypoint(String, BlockPos)} + 保存。
     * 命令里（起点/终点落在边上时）都是走这里；新建的点不会回退，就留在那儿。
     */
    public Waypoint createWaypoint(String dimension, BlockPos pos) {
        Waypoint waypoint = graph.addWaypoint(dimension, pos);
        save();
        ChatUtils.debug("已新建路径点 #" + waypoint.id() + " "
            + DimensionUtils.display(dimension) + " " + waypoint.coordString());
        return waypoint;
    }

    /**
     * 编辑模式下把一段移动记成双向走路边。
     *
     * <p>具体做法见 {@link WaypointGraph#addSegment(String, BlockPos, BlockPos)}：
     * 起点、终点各建一个路径点（会把穿过它们的边切开），再把这条边和其它边重合的位置也建成路径点，
     * 所以走出来的图一定是连通的。
     *
     * <p>只有 {@code /pc move <轴>} / {@code /pc move face} 会走到这里（也就是
     * {@code MoveAction}）；{@code /pc move to} 走的都是已知边，不记录。
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
     * <p>调用方必须已经校验过两端都是「下面垫着黑曜石的下界传送门方块」
     * （见 {@link PortalUtils#isNetherPortalOnObsidian(BlockPos)}），而且确认这次传送是
     * {@code /pc move <轴>} / {@code /pc move face} 的终点 —— 这些判断都在
     * {@link PortalTracker} 里做，玩家平时自己走进传送门是不会记的。
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

    // ------------------------------------------------------------------
    // 可达性 / 清理
    // ------------------------------------------------------------------

    /**
     * 从这些起点出发不可达的路径点和边。
     *
     * @param waypoints 可以删的不可达路径点
     * @param edges     可以删的不可达边
     * @param skipped   不可达但受保护、不会删的路径点
     */
    public record Unreachable(List<Waypoint> waypoints,
                              List<WaypointGraph.EdgeEntry> edges,
                              List<Waypoint> skipped) {
    }

    /** 这个路径点是不是「不能删」的：出生点节点、当前出生点、末地初始平台。 */
    public boolean isProtected(Waypoint waypoint) {
        if (waypoint == null) return true;
        if (waypoint.isSpawn()) return true;   // 0 号出生点节点

        Waypoint spawn = graph.spawnWaypointTarget();
        if (spawn != null && spawn.id() == waypoint.id()) return true;

        return waypoint.dimension().equals(DimensionUtils.END)
            && waypoint.pos().equals(DimensionUtils.endSpawnPos());
    }

    /**
     * 从这些起点出发，找出不可达的路径点和边。
     *
     * <p>路径点不可达 = 从起点沿着走得过去的方向到不了它（出生点节点、当前出生点、末地平台会跳过，
     * 放进 {@code skipped}）。
     *
     * <p>边不可达 = 它的起点不可达（也就是这条边走不了）；从出生点节点（0 号）出发的那条边是自动生成的，
     * 不算，受保护的点身上的边也不动。
     */
    public Unreachable findUnreachable(Collection<Integer> starts) {
        Set<Integer> reachable = graph.reachableFrom(starts);

        List<Waypoint> waypoints = new ArrayList<>();
        List<Waypoint> skipped = new ArrayList<>();
        for (Waypoint waypoint : graph.allWaypoints()) {
            if (waypoint.isSpawn()) continue;                  // 出生点节点不参与
            if (reachable.contains(waypoint.id())) continue;    // 走得到
            if (isProtected(waypoint)) {
                skipped.add(waypoint);
                continue;
            }
            waypoints.add(waypoint);
        }

        List<WaypointGraph.EdgeEntry> edges = new ArrayList<>();
        for (WaypointGraph.EdgeEntry entry : graph.allEdges()) {
            Edge edge = entry.edge();
            if (edge.touches(WaypointGraph.SPAWN_ID)) continue;   // 出生点自动生成的那条边
            if (reachable.contains(edge.from())) continue;        // 从可达的点出发，这条边走得了

            Waypoint from = graph.get(edge.from());
            Waypoint to = graph.get(edge.to());
            if (isProtected(from) || isProtected(to)) continue;   // 受保护的点身上的边不动

            edges.add(entry);
        }
        return new Unreachable(waypoints, edges, skipped);
    }

    /**
     * 把当前 world 的 waypoints.json 备份到同目录下的 {@code waypoint_bak_<n>.json}（n 从 1 开始，
     * 挑第一个还没被占用的编号）。
     *
     * @return 备份文件路径；源文件不存在或者备份失败返回 null
     */
    @Nullable
    public Path backupFile() {
        int world = loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld();
        Path dir = worldDir(world);
        Path source = worldFile(world);
        if (!Files.exists(source)) {
            ChatUtils.debug("没有可备份的路径点文件：" + source);
            return null;
        }

        try {
            Files.createDirectories(dir);
            int n = 1;
            Path target;
            do {
                target = dir.resolve("waypoint_bak_" + n + ".json");
                n++;
            } while (Files.exists(target));

            Files.copy(source, target);
            ChatUtils.debug("已备份路径点文件：" + source + " → " + target);
            return target;
        } catch (IOException e) {
            ChatUtils.error("备份路径点文件失败: " + e.getMessage());
            return null;
        }
    }
}
