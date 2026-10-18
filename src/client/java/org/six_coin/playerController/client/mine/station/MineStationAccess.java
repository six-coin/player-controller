package org.six_coin.playerController.client.mine.station;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.waypoint.Edge;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 给容器找 {@code access_position}，以及把落点变成路径点。
 *
 * <p>和 {@code /pc container cache get_all_items} 是同一套算法（见 ContainerItemsExporter）：
 * <ol>
 *   <li>先把每个维度里「是路径点或者在某条边上」的坐标都列出来；</li>
 *   <li>在容器周围、触及距离内按由近到远扫，第一个「有点或者边」的格子就是落点；</li>
 *   <li>落点如果只是压在某条边上，就在那里建一个路径点（边会被切开），这样才算得出路径。</li>
 * </ol>
 *
 * <p>挖掘站没有「所有容器都在站立点触及范围内」这种要求，所以每个容器各算各的落点。
 */
public final class MineStationAccess {

    private MineStationAccess() {
    }

    /** 每个维度里「是路径点或者在某条边上」的所有方块坐标。 */
    public static Map<String, Set<Long>> buildOccupied(WaypointGraph graph) {
        Map<String, Set<Long>> occupied = new HashMap<>();

        for (Waypoint waypoint : graph.allWaypoints()) {
            if (waypoint.isSpawn()) continue;
            occupied.computeIfAbsent(waypoint.dimension(), key -> new HashSet<>())
                .add(waypoint.pos().asLong());
        }

        for (WaypointGraph.EdgeEntry entry : graph.allEdges()) {
            Edge edge = entry.edge();
            Waypoint a = graph.get(edge.from());
            Waypoint b = graph.get(edge.to());
            if (a == null || b == null) continue;
            if (!a.dimension().equals(b.dimension())) continue;   // 传送门边不占方块

            Set<Long> positions = occupied.computeIfAbsent(a.dimension(), key -> new HashSet<>());
            for (BlockPos pos : WaypointGraph.blocksAlong(a.pos(), b.pos())) {
                positions.add(pos.asLong());
            }
        }
        return occupied;
    }

    /** 触及距离内、以自己为中心的偏移，按距离由近到远排好（同距离按 y、z、x 排，结果稳定）。 */
    public static List<BlockPos> sphereOffsets(double radius) {
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

    /** 容器触及范围内第一个「有点或者边」的坐标；附近没有就是 null。 */
    @Nullable
    public static BlockPos findAccess(Map<String, Set<Long>> occupied, String dimension,
                                      BlockPos container, List<BlockPos> offsets) {
        Set<Long> positions = occupied.get(dimension);
        if (positions == null || positions.isEmpty()) return null;

        for (BlockPos offset : offsets) {
            BlockPos candidate = container.add(offset);
            if (positions.contains(candidate.asLong())) return candidate;
        }
        return null;
    }

    /**
     * 把这个坐标变成路径点：已经是就复用；只是压在某条边上就新建（边会被切开）；
     * 既不是点也不在边上返回 null。
     *
     * <p>新建的点不会自动存盘，调用方最后统一 {@code WaypointManager.get().save()} 一次。
     */
    @Nullable
    public static Waypoint waypointAt(String dimension, BlockPos pos) {
        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint at = graph.at(dimension, pos);
        if (at != null) return at;
        if (graph.edgeAt(dimension, pos) == null) return null;
        return graph.addWaypoint(dimension, pos);
    }
}
