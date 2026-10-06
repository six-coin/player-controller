package org.six_coin.playerController.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径点管理器：负责当前 world 编号的读写、显示开关、编辑模式。
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
                + graph.size() + " 个点，" + graph.edgeCount() + " 条边");
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
    public boolean recordMove(BlockPos from, BlockPos to) {
        if (!editMode) return false;
        if (from.equals(to)) return false;
        if (WaypointGraph.sharedAxis(from, to) == null) return false;

        graph.addSegment(from, to);
        save();
        ChatUtils.debug("编辑模式：已记录 " + from.toShortString() + " <-> " + to.toShortString());
        return true;
    }
}
