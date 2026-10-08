package org.six_coin.playerController.client.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Edge;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code /pc container cache get_all_items} 的执行体（一次算完，不是多 tick 的动作）。
 *
 * <p>对每个缓存容器：
 * <ol>
 *   <li>在它的坐标周围、触及距离内（配置里的 {@code actions.interaction_range}，按方块中心距离算球形）
 *       按「由近到远」找一个「有点或者边」的格子，第一个找到的就算它的 {@code access_position}；</li>
 *   <li>如果那个格子落在边上（不是路径点），就在那里新建一个路径点（边会被切开，点留着不回退）；</li>
 *   <li>再看从玩家当前起点（{@link WaypointManager#playerStartWaypoint()}）走不走得到那个点，
 *       走得到才写进文件。</li>
 * </ol>
 *
 * <p>生成 {@code world_<n>/container/all_items.json}：
 * <pre>
 * {
 *   "detail": { "1": { "id": 1, "container_position": {...}, "access_position": {...}, "items": {...} } },
 *   "overall": { "minecraft:stone": 64 }
 * }
 * </pre>
 * {@code items} 直接抄缓存里的；{@code overall} 是所有 detail 的 items 加和。
 */
public final class ContainerItemsExporter {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** 算完的结果，给命令用来说话。 */
    public record Result(int containers,
                         int withAccess,
                         int written,
                         int overallKinds,
                         int overallCount,
                         @Nullable Path file) {
    }

    private ContainerItemsExporter() {
    }

    public static Result export(Waypoint start) {
        ContainerCacheManager cache = ContainerCacheManager.get();
        WaypointGraph graph = WaypointManager.get().graph();

        // 每个维度里「是路径点或者在某条边上」的坐标，先算好，后面按坐标查
        Map<String, Set<Long>> occupied = buildOccupied(graph);
        // 触及范围内的偏移，按由近到远排好（第一个命中的就是最近的那个）
        List<BlockPos> offsets = sphereOffsets(PlayerUtils.reach());

        JsonObject detail = new JsonObject();
        Map<String, Integer> overall = new TreeMap<>();
        int withAccess = 0;
        int written = 0;
        int createdPoints = 0;

        ChatUtils.debug("开始汇总容器物品：缓存 " + cache.size() + " 个容器，起点 #" + start.id()
            + "，触及距离 " + PlayerUtils.reach() + " 格（球形，共 " + offsets.size() + " 个偏移）");

        for (CachedContainer container : cache.all()) {
            BlockPos accessPos = findAccess(occupied, container, offsets);
            if (accessPos == null) {
                ChatUtils.debug("容器 #" + container.id() + " " + container.coordString()
                    + " 触及范围内没有路径点也没有边，跳过");
                continue;
            }
            withAccess++;

            // 落在边上就先在那里建个点（已经有了就复用），这样才有得算路径
            Waypoint accessNode = graph.at(container.dimension(), accessPos);
            if (accessNode == null) {
                accessNode = graph.addWaypoint(container.dimension(), accessPos);
                createdPoints++;
                ChatUtils.debug("容器 #" + container.id() + " 的落点 " + accessPos.toShortString()
                    + " 在边上，已新建路径点 #" + accessNode.id());
            }

            if (graph.shortestPath(start.id(), accessNode.id()) == null) {
                ChatUtils.debug("容器 #" + container.id() + " 的落点 #" + accessNode.id() + " "
                    + accessPos.toShortString() + "（" + DimensionUtils.display(container.dimension())
                    + "）从这里走不到，跳过");
                continue;
            }

            written++;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", container.id());
            entry.add("container_position", positionJson(container.dimension(), container.pos()));
            entry.add("access_position", positionJson(container.dimension(), accessPos));

            JsonObject items = new JsonObject();
            for (Map.Entry<String, Integer> item : container.items().entrySet()) {
                items.addProperty(item.getKey(), item.getValue());
                overall.merge(item.getKey(), item.getValue(), Integer::sum);
            }
            entry.add("items", items);
            detail.add(String.valueOf(container.id()), entry);

            ChatUtils.debug("容器 #" + container.id() + " 落点 #" + accessNode.id() + " "
                + accessPos.toShortString() + "，物品 " + container.itemsSummary());
        }

        // 建过点就存一次盘
        if (createdPoints > 0) {
            ChatUtils.debug("这次一共在边的落点上新建了 " + createdPoints + " 个路径点，存盘");
            WaypointManager.get().save();
        }

        JsonObject overallObject = new JsonObject();
        int overallCount = 0;
        for (Map.Entry<String, Integer> item : overall.entrySet()) {
            overallObject.addProperty(item.getKey(), item.getValue());
            overallCount += item.getValue();
        }

        JsonObject root = new JsonObject();
        root.add("detail", detail);
        root.add("overall", overallObject);

        Path file = cache.currentItemsFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            ChatUtils.debug("已写出 " + file);
        } catch (IOException e) {
            ChatUtils.error("写 all_items.json 失败: " + e.getMessage());
            return new Result(cache.size(), withAccess, written, overall.size(), overallCount, null);
        }

        return new Result(cache.size(), withAccess, written, overall.size(), overallCount, file);
    }

    // ------------------------------------------------------------------

    /** 每个维度里「是路径点或者在某条边上」的所有方块坐标。 */
    private static Map<String, Set<Long>> buildOccupied(WaypointGraph graph) {
        Map<String, Set<Long>> occupied = new HashMap<>();

        for (Waypoint waypoint : graph.allWaypoints()) {
            if (waypoint.isSpawn()) continue;
            occupied.computeIfAbsent(waypoint.dimension(), k -> new HashSet<>())
                .add(waypoint.pos().asLong());
        }

        for (WaypointGraph.EdgeEntry entry : graph.allEdges()) {
            Edge edge = entry.edge();
            Waypoint a = graph.get(edge.from());
            Waypoint b = graph.get(edge.to());
            if (a == null || b == null) continue;
            if (!a.dimension().equals(b.dimension())) continue;   // 传送门边不占方块

            Set<Long> positions = occupied.computeIfAbsent(a.dimension(), k -> new HashSet<>());
            for (BlockPos pos : WaypointGraph.blocksAlong(a.pos(), b.pos())) {
                positions.add(pos.asLong());
            }
        }
        return occupied;
    }

    /** 触及距离内、以自己为中心的偏移，按距离由近到远排好（同距离按 y、z、x 排，结果稳定）。 */
    private static List<BlockPos> sphereOffsets(double radius) {
        List<BlockPos> offsets = new ArrayList<>();
        int r = (int) Math.ceil(radius);

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.sqrt(x * x + y * y + z * z) > radius) continue;
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }

        offsets.sort(Comparator
            .comparingDouble((BlockPos p) -> Math.sqrt(p.getX() * p.getX()
                + p.getY() * p.getY() + p.getZ() * p.getZ()))
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ)
            .thenComparingInt(BlockPos::getX));
        return offsets;
    }

    /** 容器触及范围内第一个「有点或者边」的坐标。 */
    @Nullable
    private static BlockPos findAccess(Map<String, Set<Long>> occupied, CachedContainer container,
                                       List<BlockPos> offsets) {
        Set<Long> positions = occupied.get(container.dimension());
        if (positions == null || positions.isEmpty()) return null;

        BlockPos base = container.pos();
        for (BlockPos offset : offsets) {
            BlockPos candidate = base.add(offset);
            if (positions.contains(candidate.asLong())) return candidate;
        }
        return null;
    }

    private static JsonObject positionJson(String dimension, BlockPos pos) {
        JsonObject object = new JsonObject();
        object.addProperty("dimension", dimension);
        object.addProperty("x", pos.getX());
        object.addProperty("y", pos.getY());
        object.addProperty("z", pos.getZ());
        return object;
    }
}
