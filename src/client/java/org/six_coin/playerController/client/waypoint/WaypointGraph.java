package org.six_coin.playerController.client.waypoint;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 路径点图。
 *
 * <p>编号规则：
 * <ul>
 *   <li>普通路径点：1, 2, 3, ...（{@code optimize} 会按 x y z 递增重排）；</li>
 *   <li>出生点节点：固定 {@link #SPAWN_ID}（0），自动跟随「当前出生点」那个普通路径点；</li>
 *   <li>边：-1, -2, -3, ...（{@code optimize} 会按两端 id 重排）。</li>
 * </ul>
 *
 * <p>边的种类：{@code bi = true} 双向（普通走路边、下界传送门边），
 * {@code bi = false} 单向（末地传送门、出生点重合边）。
 *
 * <p>每个路径点带一个维度，所有跟坐标有关的查找 / 切分 / 合并都只在同一维度内进行。
 */
public final class WaypointGraph {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    /** 出生点节点的固定编号。 */
    public static final int SPAWN_ID = 0;

    // ---- 存档内容 ----
    private int nextId = 1;
    private final Map<Integer, Waypoint> waypoints = new LinkedHashMap<>();
    private final Map<Integer, Edge> edges = new LinkedHashMap<>();

    /** 当前出生点对应的**普通**路径点 id；没有出生点时是 null。 */
    @Nullable
    private Integer spawn;

    // ---- 运行时索引（不存档）----
    private transient final Map<DimPos, Integer> byPos = new HashMap<>();
    private transient final Map<String, Integer> byName = new HashMap<>();
    private transient final Map<Integer, Set<Integer>> neighbours = new HashMap<>();
    private transient final Map<Integer, Set<Integer>> outgoing = new HashMap<>();

    /** 维度 + 方块坐标，作为查找的键。 */
    private record DimPos(String dimension, long pos) {
    }

    /** 带编号的边，给列表和渲染用。 */
    public record EdgeEntry(int id, Edge edge) {
    }

    public WaypointGraph() {
    }

    /** 读档之后（以及每次改动之后）调用，重建所有索引。 */
    public void rebuildIndex() {
        syncSpawnWaypoint();
        syncSpawnEdge();

        byPos.clear();
        byName.clear();
        neighbours.clear();
        outgoing.clear();

        int maxId = SPAWN_ID;
        // 先登记普通路径点：位置索引优先给它们，
        // 这样出生点节点和某个路径点重合时，at()/ensureWaypoint() 拿到的还是原来那个点。
        for (Waypoint w : waypoints.values()) {
            if (w.isSpawn()) continue;
            byPos.put(new DimPos(w.dimension(), w.pos().asLong()), w.id());
            if (w.hasName()) byName.put(w.name(), w.id());
            neighbours.computeIfAbsent(w.id(), k -> new LinkedHashSet<>());
            outgoing.computeIfAbsent(w.id(), k -> new LinkedHashSet<>());
            if (w.id() > maxId) maxId = w.id();
        }

        Waypoint spawnNode = waypoints.get(SPAWN_ID);
        if (spawnNode != null) {
            neighbours.computeIfAbsent(SPAWN_ID, k -> new LinkedHashSet<>());
            outgoing.computeIfAbsent(SPAWN_ID, k -> new LinkedHashSet<>());
            byPos.putIfAbsent(new DimPos(spawnNode.dimension(), spawnNode.pos().asLong()), SPAWN_ID);
        }
        if (nextId <= maxId) nextId = maxId + 1;

        edges.values().removeIf(e -> !waypoints.containsKey(e.from()) || !waypoints.containsKey(e.to()));
        for (Map.Entry<Integer, Edge> entry : edges.entrySet()) {
            Edge e = entry.getValue();
            outgoing.get(e.from()).add(e.to());
            if (e.bi()) {
                outgoing.get(e.to()).add(e.from());
                neighbours.get(e.from()).add(e.to());
                neighbours.get(e.to()).add(e.from());
            }
        }
    }

    /**
     * 让出生点节点（0 号）跟着「当前出生点」那个普通路径点走。
     *
     * <p>只改 {@code waypoints} 这张表，索引由 {@link #rebuildIndex()} 之后统一重建。
     */
    private void syncSpawnWaypoint() {
        Waypoint target = spawnWaypointTarget();
        Waypoint node = waypoints.get(SPAWN_ID);

        if (target == null) {
            if (node != null) {
                waypoints.remove(SPAWN_ID);
                edges.values().removeIf(e -> e.touches(SPAWN_ID));
            }
            return;
        }

        if (node == null) {
            // 出生点节点没有名字：显示的时候用那个普通路径点的 id 和名字
            waypoints.put(SPAWN_ID, new Waypoint(SPAWN_ID, target.dimension(), target.pos(), null));
        } else {
            node.moveTo(target.dimension(), target.pos());
            node.name(null);
        }
    }

    /**
     * 出生点节点和「当前出生点」那个普通路径点之间的单向边：{@code 0 -> 那个点}。
     *
     * <p>两个点**共存**：原来的点不动（它身上还挂着别的边），只是让出生点有个出口，
     * 这样从末地回到出生点之后还能接着走。
     */
    private void syncSpawnEdge() {
        Waypoint target = spawnWaypointTarget();

        List<Integer> existing = new ArrayList<>();
        for (Map.Entry<Integer, Edge> entry : edges.entrySet()) {
            if (entry.getValue().from() == SPAWN_ID) existing.add(entry.getKey());
        }

        // 已经有一条正好对的就什么都不做，免得边的编号每次重建都变
        if (target != null && existing.size() == 1) {
            Edge only = edges.get(existing.get(0));
            if (only != null && only.to() == target.id() && !only.bi()) return;
        }

        for (int id : existing) edges.remove(id);
        if (target == null) return;
        edges.put(nextEdgeId(), new Edge(SPAWN_ID, target.id(), false));
    }

    /** 当前出生点对应的那个普通路径点。 */
    @Nullable
    public Waypoint spawnWaypointTarget() {        if (spawn == null) return null;
        Waypoint w = waypoints.get(spawn);
        if (w == null || w.isSpawn()) return null;
        return w;
    }

    /** 下一个可用的边编号（-1, -2, -3 ...）。 */
    private int nextEdgeId() {
        int min = 0;
        for (int id : edges.keySet()) {
            if (id < min) min = id;
        }
        return min - 1;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int size() {
        return waypoints.size();
    }

    /** 不含出生点节点的普通路径点数量。 */
    public int normalCount() {
        int n = 0;
        for (Waypoint w : waypoints.values()) {
            if (!w.isSpawn()) n++;
        }
        return n;
    }

    public int edgeCount() {
        return edges.size();
    }

    public boolean isEmpty() {
        return normalCount() == 0;
    }

    public List<Waypoint> allWaypoints() {
        List<Waypoint> list = new ArrayList<>(waypoints.values());
        list.sort(Comparator.comparingInt(Waypoint::id));
        return list;
    }

    public List<EdgeEntry> allEdges() {
        List<EdgeEntry> list = new ArrayList<>();
        for (Map.Entry<Integer, Edge> entry : edges.entrySet()) {
            list.add(new EdgeEntry(entry.getKey(), entry.getValue()));
        }
        // 倒序：-1, -2, -3 ...
        list.sort(Comparator.comparingInt(EdgeEntry::id).reversed());
        return list;
    }

    @Nullable
    public Waypoint get(int id) {
        return waypoints.get(id);
    }

    @Nullable
    public Edge edge(int id) {
        return edges.get(id);
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

    /** 双向邻居（只有 bi 边算）。 */
    public Set<Integer> neighbours(int id) {
        return neighbours.getOrDefault(id, Set.of());
    }

    /** 从这一点出发能直接到的点。 */
    public Set<Integer> outgoing(int id) {
        return outgoing.getOrDefault(id, Set.of());
    }

    /** 这条边是不是跨维度的（传送门）。 */
    public boolean isPortalEdge(Edge e) {
        Waypoint a = get(e.from());
        Waypoint b = get(e.to());
        if (a == null || b == null) return false;
        return !a.dimension().equals(b.dimension());
    }

    /** 这个路径点是不是和传送门有关（跨维度边），用来画紫色。 */
    public boolean isPortalWaypoint(int id) {
        for (Edge e : edges.values()) {
            if (!e.touches(id)) continue;
            if (isPortalEdge(e)) return true;
        }
        return false;
    }

    public boolean hasEdge(int a, int b) {
        for (Edge e : edges.values()) {
            if (e.between(a, b)) return true;
        }
        return false;
    }

    /**
     * 从这些点出发，沿着「走得过去」的方向能到达的所有路径点（含起点自己）。
     *
     * <p>走的方向按 {@link #outgoing(int)} 来：双向边两边都能走，单向边只能 from → to，
     * 跨维度（传送门）边也算。
     */
    public Set<Integer> reachableFrom(Collection<Integer> starts) {
        Set<Integer> seen = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();

        for (int id : starts) {
            if (waypoints.containsKey(id) && seen.add(id)) queue.add(id);
        }
        while (!queue.isEmpty()) {
            int id = queue.poll();
            for (int next : outgoing(id)) {
                if (seen.add(next)) queue.add(next);
            }
        }
        return seen;
    }

    /** 找一条能从 from 走到 to 的边（返回它的编号）。 */
    @Nullable
    public Integer findEdge(int from, int to) {
        for (Map.Entry<Integer, Edge> entry : edges.entrySet()) {
            if (entry.getValue().canTraverse(from, to)) return entry.getKey();
        }
        return null;
    }

    /**
     * 这个位置是不是落在某条走路边（双向边）的中间（不含两端）。
     *
     * <p>用来判断「站在边上」：站在边上可以在那里 {@link #addWaypoint} 建个点把边切开。
     * 单向边（出生点那条、以及跨维度传送门）不算。
     *
     * @return 那条边，没落在任何边上返回 null
     */
    @Nullable
    public EdgeEntry edgeAt(String dimension, BlockPos pos) {
        for (Map.Entry<Integer, Edge> entry : edges.entrySet()) {
            Edge edge = entry.getValue();
            if (!edge.bi()) continue;
            Waypoint a = get(edge.from());
            Waypoint b = get(edge.to());
            if (a == null || b == null) continue;
            if (!a.dimension().equals(dimension) || !b.dimension().equals(dimension)) continue;

            Direction.Axis axis = sharedAxis(a.pos(), b.pos());
            if (axis == null) continue;
            if (!onLine(pos, a.pos(), axis)) continue;

            int c = coord(pos, axis);
            if (c <= low(a.pos(), b.pos(), axis) || c >= high(a.pos(), b.pos(), axis)) continue;
            return new EdgeEntry(entry.getKey(), edge);
        }
        return null;
    }

    /** 边的通行代价：跨维度（传送门）算 0，同维度按方块距离。 */
    public double weight(int fromId, int toId) {
        Waypoint a = get(fromId);
        Waypoint b = get(toId);
        if (a == null || b == null) return Double.MAX_VALUE;
        if (!a.dimension().equals(b.dimension())) return 0.0;
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    /** 一条边的长度（只对同维度有意义，跨维度是 0）。 */
    public int edgeLength(Edge e) {
        Waypoint a = get(e.from());
        Waypoint b = get(e.to());
        if (a == null || b == null) return 0;
        if (!a.dimension().equals(b.dimension())) return 0;
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    // ------------------------------------------------------------------
    // 出生点
    // ------------------------------------------------------------------

    @Nullable
    public Integer spawnId() {
        return spawn;
    }

    @Nullable
    public Waypoint spawnNode() {
        return waypoints.get(SPAWN_ID);
    }

    public boolean hasSpawn() {
        return spawnWaypointTarget() != null;
    }

    /** 把一个普通路径点设为当前出生点。 */
    public boolean setSpawn(int id) {
        Waypoint w = waypoints.get(id);
        if (w == null || w.isSpawn()) return false;
        spawn = id;
        rebuildIndex();
        return true;
    }

    /** 取消出生点设置。 */
    public boolean clearSpawn() {
        if (spawn == null) return false;
        spawn = null;
        rebuildIndex();
        return true;
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

    /** 给路径点改名字。名字被别的点占用时返回 false。出生点节点不允许改。 */
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

    /**
     * 删除一个路径点，同时删掉所有和它相连的边。
     *
     * <p>出生点节点、当前出生点、末地初始平台都不能删，这些由调用方判断。
     */
    public boolean removeWaypoint(int id) {
        if (id == SPAWN_ID) return false;
        if (waypoints.remove(id) == null) return false;
        edges.values().removeIf(e -> e.touches(id));
        if (spawn != null && spawn == id) spawn = null;
        rebuildIndex();
        return true;
    }

    public boolean removeEdge(int edgeId) {
        if (edges.remove(edgeId) == null) return false;
        rebuildIndex();
        return true;
    }

    /** 加一条边，返回它的编号；和已有的重复时返回 null。 */
    @Nullable
    public Integer addEdge(int from, int to, boolean bi) {
        if (from == to) return null;
        if (!waypoints.containsKey(from) || !waypoints.containsKey(to)) return null;

        for (Edge e : edges.values()) {
            if (e.from() == from && e.to() == to && e.bi() == bi) return null;
            if (bi && e.bi() && e.between(from, to)) return null;
        }

        int id = nextEdgeId();
        edges.put(id, new Edge(from, to, bi));
        rebuildIndex();
        return id;
    }

    /** 直接连一条双向走路边（两个端点必须同维度且在同一轴向上），没有路径点就建。 */
    public boolean addWalkEdge(String dimension, BlockPos p, BlockPos q) {
        if (p.equals(q)) return false;
        if (sharedAxis(p, q) == null) return false;

        Waypoint a = ensureWaypoint(dimension, p);
        Waypoint b = ensureWaypoint(dimension, q);
        return addEdge(a.id(), b.id(), true) != null;
    }

    // ------------------------------------------------------------------
    // 加一段路
    // ------------------------------------------------------------------

    /**
     * 记录一段沿轴走路：起点、终点各 {@link #addWaypoint}（会自动把穿过它们的边切开），
     * 连上这条边之后，再把它和其它边所有「重合」的位置也建成路径点。
     *
     * <p>重合分两种，都取位置、不管方向：
     * <ul>
     *   <li><b>同一条线上重叠</b>：取重叠段的两端；</li>
     *   <li><b>十字交叉</b>：取交点。</li>
     * </ul>
     *
     * <p>建点的时候会顺手把穿过那个位置的边从中间切开，所以重叠 / 交叉的地方一定共用同一个路径点，
     * 这样走出来的图是自动连通的（交叉路口也能拐弯）。
     */
    public void addSegment(String dimension, BlockPos pa, BlockPos pb) {
        if (sharedAxis(pa, pb) == null) {
            throw new IllegalArgumentException("两个路径点必须有两个坐标相等（只能沿一个轴移动）");
        }

        Waypoint a = addWaypoint(dimension, pa);
        Waypoint b = addWaypoint(dimension, pb);
        addEdge(a.id(), b.id(), true);

        for (BlockPos crossing : crossingPositions(dimension, a, b)) {
            addWaypoint(dimension, crossing);
        }
        rebuildIndex();
    }

    /** 建一个路径点（已经有了就复用），并把穿过这一格的边从中间切开。 */
    public Waypoint addWaypoint(String dimension, BlockPos pos) {
        splitEdgeAt(dimension, pos);
        return ensureWaypoint(dimension, pos);
    }

    /** 这条边和其它边重合的所有位置（同线重叠的两端、十字交叉的交点）。 */
    private List<BlockPos> crossingPositions(String dimension, Waypoint a, Waypoint b) {
        List<BlockPos> result = new ArrayList<>();

        for (Edge edge : new ArrayList<>(edges.values())) {
            if (edge.between(a.id(), b.id())) continue;   // 自己这条边不算
            Waypoint p = get(edge.from());
            Waypoint q = get(edge.to());
            if (p == null || q == null) continue;
            if (!p.dimension().equals(dimension) || !q.dimension().equals(dimension)) continue;

            for (BlockPos pos : overlaps(a.pos(), b.pos(), p.pos(), q.pos())) {
                if (!result.contains(pos)) result.add(pos);
            }
        }
        return result;
    }

    /**
     * 两条轴向线段重合的位置。
     *
     * <p>共线重叠 → 重叠段两端；十字交叉 → 交点；其余情况（不共线、不共面、碰不到）→ 空。
     */
    private static List<BlockPos> overlaps(BlockPos a, BlockPos b, BlockPos p, BlockPos q) {
        Direction.Axis first = sharedAxis(a, b);
        Direction.Axis second = sharedAxis(p, q);
        if (first == null || second == null) return List.of();

        if (first == second) {
            // 同一条线上：不在这个轴上的两个坐标都得一样
            if (!onLine(a, p, first) || !onLine(b, p, first)) return List.of();
            int lo = Math.max(low(a, b, first), low(p, q, first));
            int hi = Math.min(high(a, b, first), high(p, q, first));
            if (lo > hi) return List.of();
            return List.of(pointAt(a, first, lo), pointAt(a, first, hi));
        }

        // 十字交叉：两条线还得在同一个平面上
        Direction.Axis third = thirdAxis(first, second);
        if (coord(a, third) != coord(p, third)) return List.of();

        int firstValue = coord(p, first);     // 第二条线在 first 轴上是固定的
        int secondValue = coord(a, second);   // 第一条线在 second 轴上是固定的
        if (firstValue < low(a, b, first) || firstValue > high(a, b, first)) return List.of();
        if (secondValue < low(p, q, second) || secondValue > high(p, q, second)) return List.of();

        return List.of(at(first, firstValue, second, secondValue, third, coord(a, third)));
    }

    /** 三条轴里剩下的那一条。 */
    private static Direction.Axis thirdAxis(Direction.Axis first, Direction.Axis second) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != first && axis != second) return axis;
        }
        return first;
    }

    /** 按三个轴上的值拼一个方块坐标。 */
    private static BlockPos at(Direction.Axis a1, int v1, Direction.Axis a2, int v2,
                               Direction.Axis a3, int v3) {
        return new BlockPos(
            valueOf(Direction.Axis.X, a1, v1, a2, v2, a3, v3),
            valueOf(Direction.Axis.Y, a1, v1, a2, v2, a3, v3),
            valueOf(Direction.Axis.Z, a1, v1, a2, v2, a3, v3));
    }

    private static int valueOf(Direction.Axis target, Direction.Axis a1, int v1,
                               Direction.Axis a2, int v2, Direction.Axis a3, int v3) {
        if (a1 == target) return v1;
        if (a2 == target) return v2;
        return v3;
    }

    private static int low(BlockPos a, BlockPos b, Direction.Axis axis) {
        return Math.min(coord(a, axis), coord(b, axis));
    }

    private static int high(BlockPos a, BlockPos b, Direction.Axis axis) {
        return Math.max(coord(a, axis), coord(b, axis));
    }

    /**
     * 如果这个位置落在某条双向边的内部（不是端点），就把那条边从中间断开。
     *
     * @return 拆掉了几条边
     */
    public int splitEdgeAt(String dimension, BlockPos pos) {
        int split = 0;

        for (Map.Entry<Integer, Edge> entry : new ArrayList<>(edges.entrySet())) {
            Edge e = entry.getValue();
            if (!e.bi()) continue;
            Waypoint a = get(e.from());
            Waypoint b = get(e.to());
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
            if (cp <= elo || cp >= ehi) continue;

            removeEdge(entry.getKey());
            addWalkEdge(dimension, a.pos(), pos);
            addWalkEdge(dimension, pos, b.pos());
            split++;
        }
        return split;
    }

    // ------------------------------------------------------------------
    // 优化
    // ------------------------------------------------------------------

    /**
     * 先合并“中间点”，再重新编号。
     *
     * <p>合并：一个路径点没有名字、只有两条双向边、两个邻居和它同维度、
     * 而且两条边在同一条直线上（它在中间），就把这个点和两条边删掉，换成一条直接相连的边。
     *
     * <p>重新编号：0 号以外的路径点按 x, y, z 递增排成 1, 2, 3, ...；
     * 边按两端 id 排序后重新编成 -1, -2, -3, ...；出生点记录跟着一起改。
     *
     * @return 合并掉的中间点数量
     */
    public int optimize() {
        int merged = mergeMiddlePoints();
        renumber();
        return merged;
    }

    private int mergeMiddlePoints() {
        int merged = 0;
        boolean changed = true;

        while (changed) {
            changed = false;
            for (Waypoint w : allWaypoints()) {
                if (w.hasName()) continue; // 有名字的点是用户指定的，保留
                if (w.isSpawn()) continue;
                // 连着传送门的点也不能删：删了点会连带把传送门边一起去掉
                if (isPortalWaypoint(w.id())) continue;
                List<Integer> ns = new ArrayList<>(neighbours(w.id()));
                if (ns.size() != 2) continue;

                Waypoint a = get(ns.get(0));
                Waypoint b = get(ns.get(1));
                if (a == null || b == null || a.id() == b.id()) continue;
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

                removeEdgeOf(a.id(), w.id());
                removeEdgeOf(w.id(), b.id());
                removeWaypoint(w.id());
                addWalkEdge(w.dimension(), a.pos(), b.pos());
                merged++;
                changed = true;
                break;
            }
        }
        return merged;
    }

    /**
     * 把所有普通路径点按 x, y, z 递增重新编号成 1, 2, 3, ...，
     * 边按两端 id 排序后重新编成 -1, -2, -3, ...，出生点记录一起改。
     */
    public void renumber() {
        List<Waypoint> normal = new ArrayList<>();
        for (Waypoint w : waypoints.values()) {
            if (!w.isSpawn()) normal.add(w);
        }
        normal.sort(Comparator.comparingInt(Waypoint::x)
            .thenComparingInt(Waypoint::y)
            .thenComparingInt(Waypoint::z));

        Map<Integer, Integer> remap = new HashMap<>();
        int id = 1;
        for (Waypoint w : normal) {
            remap.put(w.id(), id);
            w.id(id);
            id++;
        }
        nextId = id;

        if (spawn != null) {
            Integer mapped = remap.get(spawn);
            spawn = mapped;
        }

        // 重建 waypoints 表（0 号保持 0）
        Map<Integer, Waypoint> rebuilt = new LinkedHashMap<>();
        Waypoint spawnNode = waypoints.get(SPAWN_ID);
        if (spawnNode != null) rebuilt.put(SPAWN_ID, spawnNode);
        for (Waypoint w : normal) {
            rebuilt.put(w.id(), w);
        }
        waypoints.clear();
        waypoints.putAll(rebuilt);

        // 边跟着改端点，然后按两端 id 排序重新编号
        for (Edge e : edges.values()) {
            int from = e.from() == SPAWN_ID ? SPAWN_ID : remap.getOrDefault(e.from(), e.from());
            int to = e.to() == SPAWN_ID ? SPAWN_ID : remap.getOrDefault(e.to(), e.to());
            e.remap(from, to);
        }

        List<Edge> sorted = new ArrayList<>(edges.values());
        sorted.sort(Comparator.comparingInt(Edge::from).thenComparingInt(Edge::to));
        edges.clear();
        int edgeId = -1;
        for (Edge e : sorted) {
            edges.put(edgeId--, e);
        }

        rebuildIndex();
    }

    /** 只删边，不动路径点。 */
    private void removeEdgeOf(int a, int b) {
        edges.values().removeIf(e -> e.between(a, b));
        rebuildIndex();
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
