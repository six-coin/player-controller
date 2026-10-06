package org.six_coin.playerController.client.waypoint;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 路径点图：一堆路径点 + 一堆边。
 *
 * <p>边的两端存的是路径点 id，每条边只能沿单一轴向（两个路径点必须有两个坐标相同）。
 */
public final class WaypointGraph {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    // ---- 存档内容 ----
    private int nextId = 0;
    private final Map<Integer, Waypoint> waypoints = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    // ---- 运行时索引（不存档）----
    private transient final Map<Long, Integer> byPos = new HashMap<>();
    private transient final Map<String, Integer> byName = new HashMap<>();
    private transient final Map<Integer, Set<Integer>> adjacency = new HashMap<>();

    public WaypointGraph() {
    }

    /** 读档之后调用，重建所有索引。 */
    public void rebuildIndex() {
        byPos.clear();
        byName.clear();
        adjacency.clear();

        int maxId = -1;
        for (Waypoint w : waypoints.values()) {
            byPos.put(w.pos().asLong(), w.id());
            if (w.hasName()) byName.put(w.name(), w.id());
            adjacency.computeIfAbsent(w.id(), k -> new LinkedHashSet<>());
            if (w.id() > maxId) maxId = w.id();
        }
        // 防止手工改过文件之后新加的点和已有的撞 id
        if (nextId <= maxId) nextId = maxId + 1;

        edges.removeIf(e -> !waypoints.containsKey(e.a()) || !waypoints.containsKey(e.b()));
        for (Edge e : edges) {
            adjacency.computeIfAbsent(e.a(), k -> new LinkedHashSet<>()).add(e.b());
            adjacency.computeIfAbsent(e.b(), k -> new LinkedHashSet<>()).add(e.a());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int size() {
        return waypoints.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    public List<Waypoint> allWaypoints() {
        List<Waypoint> list = new ArrayList<>(waypoints.values());
        list.sort(Comparator.comparingInt(Waypoint::id));
        return list;
    }

    public List<Edge> allEdges() {
        return new ArrayList<>(edges);
    }

    @Nullable
    public Waypoint get(int id) {
        return waypoints.get(id);
    }

    @Nullable
    public Waypoint at(BlockPos pos) {
        Integer id = byPos.get(pos.asLong());
        return id == null ? null : waypoints.get(id);
    }

    @Nullable
    public Waypoint byName(String name) {
        Integer id = byName.get(name);
        return id == null ? null : waypoints.get(id);
    }

    public Set<Integer> neighbors(int id) {
        return adjacency.getOrDefault(id, Set.of());
    }

    public int degree(int id) {
        return neighbors(id).size();
    }

    public int edgeLength(Edge e) {
        Waypoint a = get(e.a());
        Waypoint b = get(e.b());
        if (a == null || b == null) return 0;
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    public boolean hasEdge(int a, int b) {
        for (Edge e : edges) {
            if (e.connects(a, b)) return true;
        }
        return false;
    }

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    // ------------------------------------------------------------------
    // 增删
    // ------------------------------------------------------------------

    /** 取这个位置的路径点，没有就新建（不带名字）。 */
    public Waypoint ensureWaypoint(BlockPos pos) {
        Waypoint existing = at(pos);
        if (existing != null) return existing;

        Waypoint created = new Waypoint(nextId++, pos, null);
        waypoints.put(created.id(), created);
        rebuildIndex();
        return created;
    }

    /** 给路径点改名字。名字被别的点占用时返回 false。 */
    public boolean setName(int id, @Nullable String name) {
        Waypoint waypoint = waypoints.get(id);
        if (waypoint == null) return false;
        if (name != null) {
            Integer owner = byName.get(name);
            if (owner != null && owner != id) return false;
        }
        waypoint.name(name);
        rebuildIndex();
        return true;
    }

    /** 删除路径点，同时删掉所有和它相连的边。 */
    public boolean removeWaypoint(int id) {
        if (waypoints.remove(id) == null) return false;
        edges.removeIf(e -> e.a() == id || e.b() == id);
        rebuildIndex();
        return true;
    }

    public boolean removeEdge(int a, int b) {
        boolean removed = edges.removeIf(e -> e.connects(a, b));
        if (removed) rebuildIndex();
        return removed;
    }

    public boolean removeEdgeAt(BlockPos pa, BlockPos pb) {
        Waypoint a = at(pa);
        Waypoint b = at(pb);
        if (a == null || b == null) return false;
        return removeEdge(a.id(), b.id());
    }

    /** 直接连一条边（两个端点都要在同一轴向上），没有路径点就建。 */
    public boolean addEdgeRaw(BlockPos p, BlockPos q) {
        if (p.equals(q)) return false;
        if (sharedAxis(p, q) == null) return false;

        Waypoint a = ensureWaypoint(p);
        Waypoint b = ensureWaypoint(q);
        if (a.id() == b.id()) return false;
        if (hasEdge(a.id(), b.id())) return false;

        edges.add(new Edge(a.id(), b.id()));
        rebuildIndex();
        return true;
    }

    // ------------------------------------------------------------------
    // 加一段路（带重叠切分）
    // ------------------------------------------------------------------

    /**
     * 加入一段轴向移动，并按重叠情况切开已有边。
     *
     * <p>例：已有 1 1 1 &lt;-&gt; 5 1 1，现在加 3 1 1 &lt;-&gt; 8 1 1，
     * 结果是 1 1 1 &lt;-&gt; 3 1 1、3 1 1 &lt;-&gt; 5 1 1、5 1 1 &lt;-&gt; 8 1 1。
     *
     * <p>另外，如果起点或终点落在别的边的中间（不管那条边朝哪个轴），
     * 会先把那条边从中间拆开，例如已有 1 1 1 &lt;-&gt; 5 1 1，起点是 3 1 1，
     * 就变成 1 1 1 &lt;-&gt; 3 1 1 和 3 1 1 &lt;-&gt; 5 1 1。
     *
     * @return 这次操作涉及到的切点（用于日志）
     */
    public List<BlockPos> addSegment(BlockPos pa, BlockPos pb) {
        Direction.Axis axis = sharedAxis(pa, pb);
        if (axis == null) {
            throw new IllegalArgumentException("两个路径点必须有两个坐标相等（只能沿一个轴移动）");
        }

        // 0. 起点 / 终点如果正好落在别的边的内部，先把那些边拆开
        splitEdgeAt(pa);
        splitEdgeAt(pb);

        int lo = Math.min(coord(pa, axis), coord(pb, axis));
        int hi = Math.max(coord(pa, axis), coord(pb, axis));
        if (lo == hi) {
            throw new IllegalArgumentException("两个路径点不能是同一个位置");
        }

        // 1. 找出同一条线上、和新线段有正长度重叠的边
        List<Edge> overlapping = new ArrayList<>();
        for (Edge e : new ArrayList<>(edges)) {
            Waypoint wa = get(e.a());
            Waypoint wb = get(e.b());
            if (wa == null || wb == null) continue;
            if (!onLine(wa.pos(), pa, axis) || !onLine(wb.pos(), pa, axis)) continue;
            int elo = Math.min(coord(wa, axis), coord(wb, axis));
            int ehi = Math.max(coord(wa, axis), coord(wb, axis));
            if (Math.min(ehi, hi) > Math.max(elo, lo)) overlapping.add(e);
        }

        // 2. 收集切点：新线段两端，加上落在它内部的、重叠边的端点
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(lo);
        cuts.add(hi);
        for (Edge e : overlapping) {
            Waypoint wa = get(e.a());
            Waypoint wb = get(e.b());
            for (int c : new int[]{coord(wa, axis), coord(wb, axis)}) {
                if (c > lo && c < hi) cuts.add(c);
            }
        }

        // 3. 拆掉重叠边，把落在新线段外面的部分补回去
        for (Edge e : overlapping) {
            Waypoint wa = get(e.a());
            Waypoint wb = get(e.b());
            int elo = Math.min(coord(wa, axis), coord(wb, axis));
            int ehi = Math.max(coord(wa, axis), coord(wb, axis));
            removeEdge(e.a(), e.b());
            if (elo < lo) addEdgeRaw(pointAt(pa, axis, elo), pointAt(pa, axis, lo));
            if (ehi > hi) addEdgeRaw(pointAt(pa, axis, hi), pointAt(pa, axis, ehi));
        }

        // 4. 相邻切点两两连边
        Integer prev = null;
        for (int c : cuts) {
            if (prev != null) addEdgeRaw(pointAt(pa, axis, prev), pointAt(pa, axis, c));
            prev = c;
        }
        rebuildIndex();

        List<BlockPos> result = new ArrayList<>();
        for (int c : cuts) result.add(pointAt(pa, axis, c));
        return result;
    }

    // ------------------------------------------------------------------
    // 拆边
    // ------------------------------------------------------------------

    /**
     * 如果这个位置落在某条边的内部（不是端点），就把那条边从中间断开。
     *
     * <p>「起点 / 终点在某条边上时把那条边拆成两份」靠的就是这个，
     * 和那条边本身朝哪个轴无关。
     *
     * @return 拆掉了几条边
     */
    public int splitEdgeAt(BlockPos pos) {
        int split = 0;

        for (Edge e : new ArrayList<>(edges)) {
            Waypoint a = get(e.a());
            Waypoint b = get(e.b());
            if (a == null || b == null) continue;

            Direction.Axis axis = sharedAxis(a.pos(), b.pos());
            if (axis == null) continue;
            if (!onLine(pos, a.pos(), axis)) continue;

            int ca = coord(a, axis);
            int cb = coord(b, axis);
            int cp = coord(pos, axis);
            int elo = Math.min(ca, cb);
            int ehi = Math.max(ca, cb);
            if (cp <= elo || cp >= ehi) continue; // 是端点，或者在边外面

            removeEdge(a.id(), b.id());
            addEdgeRaw(a.pos(), pos);
            addEdgeRaw(pos, b.pos());
            split++;
        }
        return split;
    }

    // ------------------------------------------------------------------
    // 优化
    // ------------------------------------------------------------------

    /**
     * 合并“中间点”：如果一个路径点没有名字、只有两条边、而且两条边在同一条直线上，
     * 就把这个点和两条边删掉，换成一条直接相连的边。
     *
     * @return 合并掉的数量
     */
    public int optimize() {
        int merged = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            for (Waypoint w : allWaypoints()) {
                if (w.hasName()) continue; // 有名字的点是用户指定的，保留
                List<Integer> ns = new ArrayList<>(neighbors(w.id()));
                if (ns.size() != 2) continue;

                Waypoint a = get(ns.get(0));
                Waypoint b = get(ns.get(1));
                if (a == null || b == null || a.id() == b.id()) continue;

                Direction.Axis axis = sharedAxis(a.pos(), b.pos());
                if (axis == null) continue;
                if (sharedAxis(a.pos(), w.pos()) != axis) continue;

                int ca = coord(a, axis);
                int cw = coord(w, axis);
                int cb = coord(b, axis);
                boolean between = (ca < cw && cw < cb) || (cb < cw && cw < ca);
                if (!between) continue;

                removeEdge(a.id(), w.id());
                removeEdge(w.id(), b.id());
                removeWaypoint(w.id());
                addEdgeRaw(a.pos(), b.pos());
                merged++;
                changed = true;
                break;
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // 最短路
    // ------------------------------------------------------------------

    /** 用 Dijkstra 找最短路，返回沿途每个路径点的坐标（包含起点和终点）。找不到返回 null。 */
    @Nullable
    public List<BlockPos> shortestPath(int fromId, int toId) {
        Waypoint from = get(fromId);
        Waypoint to = get(toId);
        if (from == null || to == null) return null;
        if (fromId == toId) return List.of(from.pos());

        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>();

        dist.put(fromId, 0.0);
        queue.add(new Node(fromId, 0.0));

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.dist() > dist.getOrDefault(node.id(), Double.MAX_VALUE)) continue;
            if (node.id() == toId) break;

            for (int next : neighbors(node.id())) {
                Waypoint a = get(node.id());
                Waypoint b = get(next);
                if (a == null || b == null) continue;

                double weight = Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
                double candidate = node.dist() + weight;
                if (candidate < dist.getOrDefault(next, Double.MAX_VALUE)) {
                    dist.put(next, candidate);
                    prev.put(next, node.id());
                    queue.add(new Node(next, candidate));
                }
            }
        }

        if (!dist.containsKey(toId)) return null;

        List<BlockPos> path = new ArrayList<>();
        Integer cursor = toId;
        while (cursor != null) {
            Waypoint w = get(cursor);
            if (w == null) return null;
            path.add(w.pos());
            cursor = prev.get(cursor);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private record Node(int id, double dist) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Double.compare(dist, other.dist);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 两个坐标是否只在一个轴向上不同；不是就返回 null。 */
    @Nullable
    public static Direction.Axis sharedAxis(BlockPos a, BlockPos b) {
        if (a.equals(b)) return null;
        boolean sameX = a.getX() == b.getX();
        boolean sameY = a.getY() == b.getY();
        boolean sameZ = a.getZ() == b.getZ();
        int same = (sameX ? 1 : 0) + (sameY ? 1 : 0) + (sameZ ? 1 : 0);
        if (same != 2) return null;
        if (!sameX) return Direction.Axis.X;
        if (!sameY) return Direction.Axis.Y;
        return Direction.Axis.Z;
    }

    public static int coord(Waypoint w, Direction.Axis axis) {
        return switch (axis) {
            case X -> w.x();
            case Y -> w.y();
            case Z -> w.z();
        };
    }

    public static int coord(BlockPos p, Direction.Axis axis) {
        return switch (axis) {
            case X -> p.getX();
            case Y -> p.getY();
            case Z -> p.getZ();
        };
    }

    private static boolean onLine(BlockPos p, BlockPos ref, Direction.Axis axis) {
        return switch (axis) {
            case X -> p.getY() == ref.getY() && p.getZ() == ref.getZ();
            case Y -> p.getX() == ref.getX() && p.getZ() == ref.getZ();
            case Z -> p.getX() == ref.getX() && p.getY() == ref.getY();
        };
    }

    private static BlockPos pointAt(BlockPos ref, Direction.Axis axis, int coord) {
        return switch (axis) {
            case X -> new BlockPos(coord, ref.getY(), ref.getZ());
            case Y -> new BlockPos(ref.getX(), coord, ref.getZ());
            case Z -> new BlockPos(ref.getX(), ref.getY(), coord);
        };
    }

    /** 一条边经过的所有方块（含两端）。 */
    public static List<BlockPos> blocksAlong(BlockPos a, BlockPos b) {
        List<BlockPos> list = new ArrayList<>();
        Direction.Axis axis = sharedAxis(a, b);
        if (axis == null) {
            list.add(a);
            list.add(b);
            return list;
        }
        int lo = Math.min(coord(a, axis), coord(b, axis));
        int hi = Math.max(coord(a, axis), coord(b, axis));
        for (int c = lo; c <= hi; c++) {
            list.add(pointAt(a, axis, c));
        }
        return list;
    }
}
