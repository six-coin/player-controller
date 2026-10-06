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
 * <p>每个路径点带一个维度，所以「同一个方块坐标」在不同维度是两个不同的点；
 * 所有跟坐标有关的查找 / 切分 / 合并都只在同一维度内进行。
 *
 * <p>边的种类：
 * <ul>
 *   <li>普通边：同一维度、沿单一轴向，长度 = 方块距离；</li>
 *   <li>传送门边（无向）：两端在不同维度，长度算 0，就是下界传送门；</li>
 *   <li>单向边：只有 from -&gt; to，目前是末地传送门，也单独存在 {@code oneWayEdges} 里。</li>
 * </ul>
 */
public final class WaypointGraph {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    /** 出生点路径点的固定 id（不是数字编号里的那种，是个特殊值）。 */
    public static final int SPAWN_ID = -1;

    // ---- 存档内容 ----
    private int nextId = 0;
    private final Map<Integer, Waypoint> waypoints = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<DirectedEdge> oneWayEdges = new ArrayList<>();

    /** 候选出生点。 */
    private final List<SpawnPoint> spawns = new ArrayList<>();

    /** 当前出生点的名字。 */
    @Nullable
    private String currentSpawn;

    // ---- 运行时索引（不存档）----
    private transient final Map<DimPos, Integer> byPos = new HashMap<>();
    private transient final Map<String, Integer> byName = new HashMap<>();
    private transient final Map<Integer, Set<Integer>> adjacency = new HashMap<>();
    private transient final Map<Integer, Set<Integer>> outgoing = new HashMap<>();

    /** 维度 + 方块坐标，作为查找的键。 */
    private record DimPos(String dimension, long pos) {
    }

    public WaypointGraph() {
    }

    /** 读档之后调用，重建所有索引。 */
    public void rebuildIndex() {
        syncSpawnWaypoint();

        byPos.clear();
        byName.clear();
        adjacency.clear();
        outgoing.clear();

        int maxId = SPAWN_ID;
        for (Waypoint w : waypoints.values()) {
            byPos.put(new DimPos(w.dimension(), w.pos().asLong()), w.id());
            if (w.hasName()) byName.put(w.name(), w.id());
            adjacency.computeIfAbsent(w.id(), k -> new LinkedHashSet<>());
            outgoing.computeIfAbsent(w.id(), k -> new LinkedHashSet<>());
            if (w.id() > maxId) maxId = w.id();
        }
        if (nextId <= maxId) nextId = maxId + 1;

        edges.removeIf(e -> !waypoints.containsKey(e.a()) || !waypoints.containsKey(e.b()));
        for (Edge e : edges) {
            adjacency.get(e.a()).add(e.b());
            adjacency.get(e.b()).add(e.a());
            outgoing.get(e.a()).add(e.b());
            outgoing.get(e.b()).add(e.a());
        }

        oneWayEdges.removeIf(e -> !waypoints.containsKey(e.from()) || !waypoints.containsKey(e.to()));
        for (DirectedEdge e : oneWayEdges) {
            outgoing.get(e.from()).add(e.to());
        }
    }

    /**
     * 让 -1 号路径点跟着「当前出生点」走。
     *
     * <p>只改 {@code waypoints} 这张表，索引由 {@link #rebuildIndex()} 之后统一重建。
     */
    private void syncSpawnWaypoint() {
        SpawnPoint spawn = currentSpawnPoint();
        Waypoint node = waypoints.get(SPAWN_ID);

        if (spawn == null) {
            if (node != null) {
                waypoints.remove(SPAWN_ID);
                edges.removeIf(e -> e.a() == SPAWN_ID || e.b() == SPAWN_ID);
                oneWayEdges.removeIf(e -> e.from() == SPAWN_ID || e.to() == SPAWN_ID);
            }
            return;
        }

        if (node == null) {
            waypoints.put(SPAWN_ID, new Waypoint(SPAWN_ID, spawn.dimension(), spawn.pos(), spawn.name()));
        } else {
            node.moveTo(spawn.dimension(), spawn.pos());
            node.name(spawn.name());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int size() {
        return waypoints.size();
    }

    public int edgeCount() {
        return edges.size() + oneWayEdges.size();
    }

    public int normalEdgeCount() {
        return edges.size();
    }

    public int oneWayEdgeCount() {
        return oneWayEdges.size();
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

    public List<DirectedEdge> allOneWayEdges() {
        return new ArrayList<>(oneWayEdges);
    }

    @Nullable
    public Waypoint get(int id) {
        return waypoints.get(id);
    }

    @Nullable
    public Waypoint at(String dimension, BlockPos pos) {
        Integer id = byPos.get(new DimPos(dimension, pos.asLong()));
        return id == null ? null : waypoints.get(id);
    }

    @Nullable
    public Waypoint byName(String name) {
        Integer id = byName.get(name);
        return id == null ? null : waypoints.get(id);
    }

    /** 无向邻居（普通边 + 传送门边）。 */
    public Set<Integer> neighbors(int id) {
        return adjacency.getOrDefault(id, Set.of());
    }

    /** 从这一点出发能直接到的点（无向边两端都算 + 单向边只算 from）。 */
    public Set<Integer> outgoing(int id) {
        return outgoing.getOrDefault(id, Set.of());
    }

    public int degree(int id) {
        return neighbors(id).size();
    }

    /** 这条边两端是不是在不同维度（是的话就是传送门边，长度算 0）。 */
    public boolean isPortalEdge(Edge e) {
        Waypoint a = get(e.a());
        Waypoint b = get(e.b());
        if (a == null || b == null) return false;
        return !a.dimension().equals(b.dimension());
    }

    /** 这个路径点是不是和传送门有关（下界传送门边或末地单向边），用来画紫色。 */
    public boolean isPortalWaypoint(int id) {
        for (Edge e : edges) {
            if (e.a() != id && e.b() != id) continue;
            if (isPortalEdge(e)) return true;
        }
        for (DirectedEdge e : oneWayEdges) {
            if (e.from() == id || e.to() == id) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 出生点
    // ------------------------------------------------------------------

    public List<SpawnPoint> allSpawns() {
        return new ArrayList<>(spawns);
    }

    @Nullable
    public String currentSpawnName() {
        return currentSpawn;
    }

    @Nullable
    public SpawnPoint currentSpawnPoint() {
        if (currentSpawn == null) return null;
        for (SpawnPoint spawn : spawns) {
            if (currentSpawn.equals(spawn.name())) return spawn;
        }
        return null;
    }

    @Nullable
    public SpawnPoint spawnByName(String name) {
        for (SpawnPoint spawn : spawns) {
            if (spawn.name().equals(name)) return spawn;
        }
        return null;
    }

    /** 那个特殊的出生点路径点（id = -1），没有登记出生点时返回 null。 */
    @Nullable
    public Waypoint spawnWaypoint() {
        return waypoints.get(SPAWN_ID);
    }

    public boolean hasSpawn() {
        return spawnWaypoint() != null;
    }

    /**
     * 登记一个候选出生点并设为当前出生点。同名的话就地更新位置。
     *
     * @return 名字已被别的路径点占用时返回 false
     */
    public boolean addSpawn(String name, String dimension, BlockPos pos) {
        if (!isValidName(name)) return false;

        Integer owner = byName.get(name);
        if (owner != null && owner != SPAWN_ID) return false;

        SpawnPoint existing = spawnByName(name);
        if (existing != null) {
            existing.moveTo(dimension, pos);
        } else {
            spawns.add(new SpawnPoint(name, dimension, pos));
        }
        currentSpawn = name;
        rebuildIndex();
        return true;
    }

    /** 把当前出生点切到已经登记过的某个名字上。 */
    public boolean setCurrentSpawn(String name) {
        if (spawnByName(name) == null) return false;
        currentSpawn = name;
        rebuildIndex();
        return true;
    }

    public boolean hasEdge(int a, int b) {
        for (Edge e : edges) {
            if (e.connects(a, b)) return true;
        }
        return false;
    }

    public boolean hasOneWayEdge(int from, int to) {
        for (DirectedEdge e : oneWayEdges) {
            if (e.from() == from && e.to() == to) return true;
        }
        return false;
    }

    /** 边的通行代价：跨维度（传送门）算 0，同维度按方块距离。 */
    public double weight(int fromId, int toId) {
        Waypoint a = get(fromId);
        Waypoint b = get(toId);
        if (a == null || b == null) return Double.MAX_VALUE;
        if (!a.dimension().equals(b.dimension())) return 0.0;
        if (hasOneWayEdge(fromId, toId)) return 0.0;
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    /** 无向边的长度（只对同维度的普通边有意义）。 */
    public int edgeLength(Edge e) {
        Waypoint a = get(e.a());
        Waypoint b = get(e.b());
        if (a == null || b == null) return 0;
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    // ------------------------------------------------------------------
    // 增删
    // ------------------------------------------------------------------

    /** 取这个维度、这个位置的路径点，没有就新建（不带名字）。 */
    public Waypoint ensureWaypoint(String dimension, BlockPos pos) {
        Waypoint existing = at(dimension, pos);
        if (existing != null) return existing;

        Waypoint created = new Waypoint(nextId++, dimension, pos, null);
        waypoints.put(created.id(), created);
        rebuildIndex();
        return created;
    }

    /** 给路径点改名字。名字被别的点占用时返回 false。出生点不允许改名字。 */
    public boolean setName(int id, @Nullable String name) {
        Waypoint waypoint = waypoints.get(id);
        if (waypoint == null) return false;
        if (waypoint.isSpawn()) return false;
        if (name != null) {
            Integer owner = byName.get(name);
            if (owner != null && owner != id) return false;
        }
        waypoint.name(name);
        rebuildIndex();
        return true;
    }

    /** 删除路径点，同时删掉所有和它相连的边。出生点不能删。 */
    public boolean removeWaypoint(int id) {
        if (id == SPAWN_ID) return false;
        if (waypoints.remove(id) == null) return false;
        edges.removeIf(e -> e.a() == id || e.b() == id);
        oneWayEdges.removeIf(e -> e.from() == id || e.to() == id);
        rebuildIndex();
        return true;
    }

    public boolean removeEdge(int a, int b) {
        boolean removed = edges.removeIf(e -> e.connects(a, b));
        if (removed) rebuildIndex();
        return removed;
    }

    public boolean removeOneWayEdge(int from, int to) {
        boolean removed = oneWayEdges.removeIf(e -> e.from() == from && e.to() == to);
        if (removed) rebuildIndex();
        return removed;
    }

    public boolean removeEdgeAt(String dimension, BlockPos pa, BlockPos pb) {
        Waypoint a = at(dimension, pa);
        Waypoint b = at(dimension, pb);
        if (a == null || b == null) return false;
        boolean removed = removeEdge(a.id(), b.id());
        if (!removed) removed = removeOneWayEdge(a.id(), b.id());
        if (!removed) removed = removeOneWayEdge(b.id(), a.id());
        return removed;
    }

    /** 删掉挂在某个路径点上的所有跨维度边（下界传送门边 + 末地单向边）。 */
    public int removePortalEdgesAt(String dimension, BlockPos pos) {
        Waypoint waypoint = at(dimension, pos);
        if (waypoint == null) return 0;
        int id = waypoint.id();
        int removed = 0;

        for (Edge e : new ArrayList<>(edges)) {
            if (e.a() != id && e.b() != id) continue;
            if (!isPortalEdge(e)) continue;
            removeEdge(e.a(), e.b());
            removed++;
        }
        for (DirectedEdge e : new ArrayList<>(oneWayEdges)) {
            if (e.from() != id && e.to() != id) continue;
            removeOneWayEdge(e.from(), e.to());
            removed++;
        }
        return removed;
    }

    /** 直接连一条普通边（两个端点必须同维度且在同一轴向上），没有路径点就建。 */
    public boolean addEdgeRaw(String dimension, BlockPos p, BlockPos q) {
        if (p.equals(q)) return false;
        if (sharedAxis(p, q) == null) return false;

        Waypoint a = ensureWaypoint(dimension, p);
        Waypoint b = ensureWaypoint(dimension, q);
        if (a.id() == b.id()) return false;
        if (hasEdge(a.id(), b.id())) return false;

        edges.add(new Edge(a.id(), b.id()));
        rebuildIndex();
        return true;
    }

    /** 建一条传送门边（下界传送门），两端通常在不同维度，长度算 0。 */
    public boolean addPortalEdge(int a, int b) {
        if (a == b) return false;
        if (!waypoints.containsKey(a) || !waypoints.containsKey(b)) return false;
        if (hasEdge(a, b)) return false;
        edges.add(new Edge(a, b));
        rebuildIndex();
        return true;
    }

    /** 建一条单向边（末地传送门），只有 from -&gt; to。 */
    public boolean addOneWayEdge(int from, int to) {
        if (from == to) return false;
        if (!waypoints.containsKey(from) || !waypoints.containsKey(to)) return false;
        if (hasOneWayEdge(from, to)) return false;
        oneWayEdges.add(new DirectedEdge(from, to));
        rebuildIndex();
        return true;
    }

    // ------------------------------------------------------------------
    // 加一段路（带重叠切分）
    // ------------------------------------------------------------------

    /**
     * 加入一段轴向移动，并按重叠情况切开已有边（只看同一维度、同一条线上的边）。
     *
     * <p>例：已有 1 1 1 &lt;-&gt; 5 1 1，现在加 3 1 1 &lt;-&gt; 8 1 1，
     * 结果是 1 1 1 &lt;-&gt; 3 1 1、3 1 1 &lt;-&gt; 5 1 1、5 1 1 &lt;-&gt; 8 1 1。
     *
     * <p>另外，如果起点或终点落在别的边的中间（不管那条边朝哪个轴），
     * 会先把那条边从中间拆开。
     *
     * @return 这次操作涉及到的切点（用于日志）
     */
    public List<BlockPos> addSegment(String dimension, BlockPos pa, BlockPos pb) {
        Direction.Axis axis = sharedAxis(pa, pb);
        if (axis == null) {
            throw new IllegalArgumentException("两个路径点必须有两个坐标相等（只能沿一个轴移动）");
        }

        // 0. 起点 / 终点如果正好落在别的边的内部，先把那些边拆开
        splitEdgeAt(dimension, pa);
        splitEdgeAt(dimension, pb);

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
            if (!wa.dimension().equals(dimension) || !wb.dimension().equals(dimension)) continue;
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
            if (elo < lo) addEdgeRaw(dimension, pointAt(pa, axis, elo), pointAt(pa, axis, lo));
            if (ehi > hi) addEdgeRaw(dimension, pointAt(pa, axis, hi), pointAt(pa, axis, ehi));
        }

        // 4. 相邻切点两两连边
        Integer prev = null;
        for (int c : cuts) {
            if (prev != null) addEdgeRaw(dimension, pointAt(pa, axis, prev), pointAt(pa, axis, c));
            prev = c;
        }
        rebuildIndex();

        List<BlockPos> result = new ArrayList<>();
        for (int c : cuts) result.add(pointAt(pa, axis, c));
        return result;
    }

    /**
     * 如果这个位置落在某条边的内部（不是端点），就把那条边从中间断开。
     *
     * <p>「起点 / 终点在某条边上时把那条边拆成两份」靠的就是这个，
     * 和那条边本身朝哪个轴无关，但只在同一维度内找。
     *
     * @return 拆掉了几条边
     */
    public int splitEdgeAt(String dimension, BlockPos pos) {
        int split = 0;

        for (Edge e : new ArrayList<>(edges)) {
            Waypoint a = get(e.a());
            Waypoint b = get(e.b());
            if (a == null || b == null) continue;
            if (!a.dimension().equals(dimension) || !b.dimension().equals(dimension)) continue;

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
            addEdgeRaw(dimension, a.pos(), pos);
            addEdgeRaw(dimension, pos, b.pos());
            split++;
        }
        return split;
    }

    // ------------------------------------------------------------------
    // 优化
    // ------------------------------------------------------------------

    /**
     * 合并“中间点”：如果一个路径点没有名字、只有两条边、两个邻居和它同维度、
     * 而且两条边在同一条直线上，就把这个点和两条边删掉，换成一条直接相连的边。
     *
     * @return 合并掉的数量
     */
    public int optimize() {
        int merged = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            for (Waypoint w : allWaypoints()) {
                if (w.hasName()) continue; // 有名字的点是用户指定的，保留（出生点也有名字）
                if (w.isSpawn()) continue;
                List<Integer> ns = new ArrayList<>(neighbors(w.id()));
                if (ns.size() != 2) continue;

                Waypoint a = get(ns.get(0));
                Waypoint b = get(ns.get(1));
                if (a == null || b == null || a.id() == b.id()) continue;
                // 传送门边不能用几何关系合并
                if (!a.dimension().equals(w.dimension())) continue;
                if (!b.dimension().equals(w.dimension())) continue;

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
                addEdgeRaw(w.dimension(), a.pos(), b.pos());
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

    /** 用 Dijkstra 找最短路（传送门边长度为 0），返回沿途的路径点（含起点终点）。找不到返回 null。 */
    @Nullable
    public List<Waypoint> shortestPath(int fromId, int toId) {
        Waypoint from = get(fromId);
        Waypoint to = get(toId);
        if (from == null || to == null) return null;
        if (fromId == toId) return List.of(from);

        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>();

        dist.put(fromId, 0.0);
        queue.add(new Node(fromId, 0.0));

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.dist() > dist.getOrDefault(node.id(), Double.MAX_VALUE)) continue;
            if (node.id() == toId) break;

            for (int next : outgoing(node.id())) {
                double weight = weight(node.id(), next);
                if (weight == Double.MAX_VALUE) continue;

                double candidate = node.dist() + weight;
                if (candidate < dist.getOrDefault(next, Double.MAX_VALUE)) {
                    dist.put(next, candidate);
                    prev.put(next, node.id());
                    queue.add(new Node(next, candidate));
                }
            }
        }

        if (!dist.containsKey(toId)) return null;

        List<Waypoint> path = new ArrayList<>();
        Integer cursor = toId;
        while (cursor != null) {
            Waypoint w = get(cursor);
            if (w == null) return null;
            path.add(w);
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
