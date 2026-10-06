package org.six_coin.playerController.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径点管理器：负责当前 world 编号的读写、显示开关、编辑模式、传送门边的记录。
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

    /** 按当前配置里的 world 编号加载。 */
    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int world) {
        world = Math.max(1, world);
        loadedWorld = world;
        Path path = worldFile(world);

        if (!Files.exists(path)) {
            graph = new WaypointGraph();
            ChatUtils.debug("世界配置 world_" + world + " 还没有路径点文件，使用空图: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            WaypointGraph loaded = GSON.fromJson(reader, WaypointGraph.class);
            graph = loaded == null ? new WaypointGraph() : loaded;
            graph.rebuildIndex();
            ChatUtils.debug("已加载 world_" + world + " 的路径点: "
                + graph.size() + " 个点，" + graph.normalEdgeCount() + " 条普通边，"
                + graph.oneWayEdgeCount() + " 条单向边");
        } catch (Exception e) {
            graph = new WaypointGraph();
            ChatUtils.error("读取路径点失败，已使用空图: " + e.getMessage());
        }
    }

    /** 切换世界配置：先把旧的存起来，再读新的。 */
    public void switchWorld(int world) {
        save();
        PlayerControllerConfig.setWorld(world);
        load(world);
        ChatUtils.info("已切换到世界配置 world_" + world
            + "（" + graph.size() + " 个路径点，" + graph.edgeCount() + " 条边）");
    }

    public void save() {
        if (loadedWorld < 0) return;

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
     * 编辑模式下把一段移动记录成边（会把重叠的边切开）。
     *
     * @return 是否真的记录了（不在编辑模式 / 两端相同 / 不合法时返回 false）
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
     * 编辑模式下：把终端在末地传送门的那一格，和它真正会去的地方连一条单向边。
     *
     * <p>末地传送门方块两个方向用的是同一个方块，所以必须按维度区分：
     * <ul>
     *   <li>在主世界（或非末地维度）：去末地出生平台 (100, 50, 0) @ the_end；</li>
     *   <li>在末地：那是回主世界的祭坛，落点是玩家的重生点 —— 连到当前的出生点路径点（id = -1）。</li>
     * </ul>
     *
     * <p>「终点下方 1 格是末地传送门方块」或者「终点本身就是末地传送门方块」都算。
     * 这条边只加不减，不会动别的边。
     *
     * @return 是否新建了单向边
     */
    public boolean recordEndPortalLink(String dimension, BlockPos endPos) {
        if (!editMode) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;

        boolean portalHere = mc.world.getBlockState(endPos).isOf(Blocks.END_PORTAL);
        boolean portalBelow = mc.world.getBlockState(endPos.down()).isOf(Blocks.END_PORTAL);
        if (!portalHere && !portalBelow) return false;

        Waypoint from = graph.ensureWaypoint(dimension, endPos);
        Waypoint to;

        if (DimensionUtils.END.equals(dimension)) {
            // 末地里的祭坛：回主世界 / 下界的重生点
            to = graph.spawnWaypoint();
            if (to == null) {
                ChatUtils.error("末地祭坛 + " + endPos.toShortString()
                    + " 需要连到出生点，但你还没有设置出生点；"
                    + "请先用 /pc waypoints spawn add_here <name> 登记一个");
                return false;
            }
        } else {
            to = graph.ensureWaypoint(DimensionUtils.END, DimensionUtils.endSpawnPos());
        }

        if (!graph.addOneWayEdge(from.id(), to.id())) {
            return false;
        }

        save();
        ChatUtils.debug("编辑模式：末地传送门 " + DimensionUtils.display(dimension) + " "
            + endPos.toShortString() + " → " + DimensionUtils.display(to.dimension()) + " "
            + to.coordString() + " 单向边");
        return true;
    }

    /**
     * 编辑模式下：玩家穿过下界传送门后，把传送前所在的路径点和落地方块连一条 0 长度传送门边。
     *
     * @return 是否新建了传送门边
     */
    public boolean recordPortalLink(Waypoint source, String arrivalDimension, BlockPos arrivalPos) {
        if (!editMode) return false;
        if (source == null) return false;
        if (source.dimension().equals(arrivalDimension)) return false;

        Waypoint arrival = graph.ensureWaypoint(arrivalDimension, arrivalPos);
        if (!graph.addPortalEdge(source.id(), arrival.id())) {
            return false;
        }

        save();
        ChatUtils.info("编辑模式：记录传送门 " + DimensionUtils.display(source.dimension())
            + " " + source.coordString() + " <-> " + DimensionUtils.display(arrivalDimension)
            + " " + arrival.coordString() + "（长度 0，紫色）");
        return true;
    }

    // ------------------------------------------------------------------
    // 出生点
    // ------------------------------------------------------------------

    /** 登记一个候选出生点并设为当前出生点。 */
    public boolean addSpawn(String name, String dimension, BlockPos pos) {
        if (!graph.addSpawn(name, dimension, pos)) return false;
        save();
        ChatUtils.debug("已登记出生点 " + name + " -> " + DimensionUtils.display(dimension)
            + " " + pos.toShortString() + "，并设为当前出生点");
        return true;
    }

    /** 把当前出生点切到已经登记过的某个名字上。 */
    public boolean setCurrentSpawn(String name) {
        if (!graph.setCurrentSpawn(name)) return false;
        save();
        SpawnPoint spawn = graph.currentSpawnPoint();
        if (spawn != null) {
            ChatUtils.debug("当前出生点已设为 " + name + " -> "
                + DimensionUtils.display(spawn.dimension()) + " " + spawn.coordString());
        }
        return true;
    }
}
