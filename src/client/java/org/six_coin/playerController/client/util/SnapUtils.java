package org.six_coin.playerController.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.mine.station.MineStationAccess;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「沿轴三段直线飞过去」的公共算法：{@code /pc snap} 和 {@code /pc move to} 的自动恢复都用它。
 *
 * <p>走法：把「先走哪个轴」的 6 种排列都试一遍 —— x→y→z、x→z→y、y→x→z、y→z→x、z→x→y、z→y→x，
 * 取第一种通的。判定：经过的每一格方块、以及它上面那一格，都必须是空气
 * （起点自己那一格不看，玩家就站在那儿，但起点头顶那一格还是要空）。
 *
 * <p>另外两条硬性限制：
 * <ul>
 *   <li>目的地必须在**已加载**的区块里（见 {@link #isLoaded}）；</li>
 *   <li>{@code network} 只在 {@value #SEARCH_RADIUS} 格内找，只找已加载区块里的坐标。</li>
 * </ul>
 */
public final class SnapUtils {

    /** {@code network} 的搜索半径（格）。 */
    public static final int SEARCH_RADIUS = 1000;

    /** 坐标的合理上限（原版世界边界是 ±29999984，这里留点余量）。 */
    public static final long MAX_COORDINATE = 30_000_000L;

    /** 6 种轴顺序，按这个顺序试，第一个通的就用。 */
    private static final Direction.Axis[][] ORDERS = {
        {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z},
        {Direction.Axis.X, Direction.Axis.Z, Direction.Axis.Y},
        {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z},
        {Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X},
        {Direction.Axis.Z, Direction.Axis.X, Direction.Axis.Y},
        {Direction.Axis.Z, Direction.Axis.Y, Direction.Axis.X},
    };

    /** 一段沿轴移动。 */
    public record Segment(Direction.Axis axis, int blocks) {
    }

    /** 一种走法：轴顺序 + 途经的拐点（含起点、终点）+ 拆好的段。 */
    public record Plan(Direction.Axis[] order, List<BlockPos> points, List<Segment> segments) {

        public BlockPos target() {
            return points.get(points.size() - 1);
        }

        /** 「x→y→z」。 */
        public String describeOrder() {
            StringBuilder text = new StringBuilder();
            for (Direction.Axis axis : order) {
                if (text.length() > 0) text.append("→");
                text.append(MoveAction.axisName(axis));
            }
            return text.toString();
        }

        /** 「x +50、y -3、z +100」。 */
        public String describeSegments() {
            StringBuilder text = new StringBuilder();
            for (Segment segment : segments) {
                if (text.length() > 0) text.append("、");
                text.append(MoveAction.axisName(segment.axis())).append(' ')
                    .append(segment.blocks() > 0 ? "+" : "").append(segment.blocks());
            }
            return text.toString();
        }
    }

    /** 一个能用的 snap：目的地 + 走法。 */
    public record Snap(BlockPos target, Plan plan) {
    }

    private SnapUtils() {
    }

    // ------------------------------------------------------------------
    // 区块加载
    // ------------------------------------------------------------------

    /**
     * 这个坐标所在的区块，客户端上到底加载了没有。
     *
     * <p><b>不能用 {@code mc.world.isChunkLoaded(x, z)}</b>：这个版本里
     * {@code ClientWorld.isChunkLoaded(int, int)} 是写死的 {@code return true;}，
     * 拿它判断等于没判断。
     *
     * <p>所以分两步：
     * <ol>
     *   <li>先按视距粗筛：区块坐标离玩家超过 {@code options.viewDistance + 2} 的一定没加载；</li>
     *   <li>再问客户端要这块区块（{@code ClientChunkManager.getChunk(..., false)}）：
     *       没加载时它直接返回 null，拿到了才算加载。</li>
     * </ol>
     */
    public static boolean isLoaded(MinecraftClient mc, BlockPos pos) {
        if (mc.world == null) return false;

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        ClientPlayerEntity player = mc.player;
        if (player != null) {
            BlockPos here = player.getBlockPos();
            int limit = mc.options.getViewDistance().getValue() + 2;
            if (Math.abs(chunkX - (here.getX() >> 4)) > limit
                || Math.abs(chunkZ - (here.getZ() >> 4)) > limit) {
                return false;
            }
        }

        WorldChunk chunk = mc.world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return chunk != null && chunk.getPos().toLong() == ChunkPos.toLong(chunkX, chunkZ);
    }

    // ------------------------------------------------------------------
    // network 候选
    // ------------------------------------------------------------------

    /**
     * 当前维度里、{@value #SEARCH_RADIUS} 格内、已加载的「路径点 / 边上的方块」，按距离由近到远。
     */
    public static List<BlockPos> networkCandidates(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return List.of();

        Map<String, Set<Long>> occupied = MineStationAccess.buildOccupied(WaypointManager.get().graph());
        Set<Long> positions = occupied.get(DimensionUtils.current());
        if (positions == null || positions.isEmpty()) return List.of();

        double limit = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        Vec3d here = player.getEntityPos();

        List<BlockPos> candidates = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (long packed : positions) {
            BlockPos pos = BlockPos.fromLong(packed);
            double distance = here.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance > limit) continue;         // 超出搜索半径
            if (!isLoaded(mc, pos)) continue;       // 没加载的区块不进去（进了也读不到方块）
            candidates.add(pos);
            distances.add(distance);
        }

        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble(distances::get));

        List<BlockPos> sorted = new ArrayList<>(order.length);
        for (int index : order) sorted.add(candidates.get(index));
        return sorted;
    }

    /**
     * 依次试 network 候选（由近到远），返回第一个走得通的。
     *
     * <p>最近的不可达就试次近的，一直试到 1000 格内所有已加载的坐标都不行为止。
     */
    @Nullable
    public static Snap findSnap(MinecraftClient mc, BlockPos from) {
        for (BlockPos candidate : networkCandidates(mc)) {
            if (candidate.equals(from)) continue;
            // 便宜的预筛：目的地那一格和它上面一格得是空气，不然连人形都放不下
            if (!hasHeadroom(mc, candidate)) continue;

            Plan plan = plan(mc, from, candidate);
            if (plan != null) return new Snap(candidate, plan);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 走法
    // ------------------------------------------------------------------

    /** 6 种轴顺序里第一种通的；都不通返回 null。 */
    @Nullable
    public static Plan plan(MinecraftClient mc, BlockPos from, BlockPos to) {
        if (mc.world == null || from.equals(to)) return null;

        for (Direction.Axis[] order : ORDERS) {
            List<BlockPos> points = new ArrayList<>(4);
            points.add(from);

            BlockPos current = from;
            for (Direction.Axis axis : order) {
                BlockPos next = withAxis(current, axis, coord(to, axis));
                if (!next.equals(current)) points.add(next);
                current = next;
            }
            if (points.size() == 1) continue;

            if (!clear(mc, points)) continue;

            List<Segment> segments = new ArrayList<>(points.size() - 1);
            for (int i = 1; i < points.size(); i++) {
                BlockPos previous = points.get(i - 1);
                BlockPos next = points.get(i);
                Direction.Axis axis = WaypointGraph.sharedAxis(previous, next);
                if (axis == null) continue;
                int blocks = coord(next, axis) - coord(previous, axis);
                if (blocks != 0) segments.add(new Segment(axis, blocks));
            }
            return new Plan(order, points, segments);
        }
        return null;
    }

    /**
     * 这条路通不通：经过的每一格 + 它上面一格都是空气。
     *
     * <p>起点自己那一格不查（玩家就站在那儿），但起点头顶那一格要查。
     */
    private static boolean clear(MinecraftClient mc, List<BlockPos> points) {
        BlockPos start = points.get(0);
        if (!mc.world.getBlockState(start.up()).isAir()) return false;

        Set<Long> checked = new HashSet<>();
        checked.add(start.asLong());

        for (int i = 1; i < points.size(); i++) {
            BlockPos previous = points.get(i - 1);
            BlockPos next = points.get(i);

            Direction.Axis axis = WaypointGraph.sharedAxis(previous, next);
            if (axis == null) return false;

            int delta = coord(next, axis) - coord(previous, axis);
            int step = Integer.signum(delta);

            for (int moved = 1; moved <= Math.abs(delta); moved++) {
                BlockPos pos = moveAlong(previous, axis, step * moved);
                if (!checked.add(pos.asLong())) continue;      // 两段重叠的地方只查一次

                if (!mc.world.getBlockState(pos).isAir()) return false;
                if (!mc.world.getBlockState(pos.up()).isAir()) return false;
            }
        }
        return true;
    }

    /** 这一格能不能站人（方块本身和上面一格都是空气）。 */
    private static boolean hasHeadroom(MinecraftClient mc, BlockPos pos) {
        return mc.world.getBlockState(pos).isAir() && mc.world.getBlockState(pos.up()).isAir();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    public static int coord(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private static BlockPos withAxis(BlockPos pos, Direction.Axis axis, int value) {
        return switch (axis) {
            case X -> new BlockPos(value, pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), value, pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), value);
        };
    }

    /** 沿某个轴走 {@code distance} 格（负数就是反方向）。 */
    private static BlockPos moveAlong(BlockPos pos, Direction.Axis axis, int distance) {
        return switch (axis) {
            case X -> new BlockPos(pos.getX() + distance, pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), pos.getY() + distance, pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), pos.getZ() + distance);
        };
    }
}
