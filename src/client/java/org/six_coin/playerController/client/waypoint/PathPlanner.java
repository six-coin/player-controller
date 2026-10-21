package org.six_coin.playerController.client.waypoint;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.List;

/**
 * 算一条「从玩家现在的位置走过去」的路径：{@code /pc move to_position} 用的那套。
 *
 * <p>规则：终点已经是路径点就直接走；终点在某条边中间就先在那边新建一个路径点再走；
 * 都不是就报错。起点同理（见 {@link WaypointManager#playerStartWaypoint()}）。
 *
 * <p>命令（{@code MoveCommand}）和 mending 走的都是这里，免得两处各写一份。
 */
public final class PathPlanner {

    /**
     * 算出来的结果。
     *
     * @param path   要走的路（{@code error == null} 时才有意义；为 null 表示「已经站在那儿了」）
     * @param target 终点路径点
     * @param error  为什么走不了（null = 能走）
     * @param info   值得说一句的提示（比如「终点在边上，已新建路径点 #3」）
     */
    public record Plan(@Nullable List<Waypoint> path, @Nullable Waypoint target,
                       @Nullable String error, @Nullable String info) {

        public boolean ok() {
            return error == null;
        }

        /** 已经站在终点上了，不用移动。 */
        public boolean alreadyThere() {
            return ok() && path == null;
        }
    }

    private PathPlanner() {
    }

    /**
     * 用坐标当终点。
     *
     * @param dimension 终点所在的维度
     */
    public static Plan toPosition(BlockPos target, String dimension) {
        WaypointGraph graph = WaypointManager.get().graph();

        Waypoint to = graph.at(dimension, target);
        String info = null;

        if (to == null) {
            WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, target);
            if (edge == null) {
                return new Plan(null, null, DimensionUtils.display(dimension) + " "
                    + target.toShortString() + " 既不是路径点，也不在任何边上", null);
            }
            to = WaypointManager.get().createWaypoint(dimension, target);
            info = "目标 " + DimensionUtils.display(dimension) + " " + target.toShortString()
                + " 在边 #" + edge.id() + " 上，已在那边新建路径点 #" + to.id();
        }

        Plan plan = toWaypoint(to);
        return new Plan(plan.path(), plan.target(), plan.error(),
            info != null ? info : plan.info());
    }

    /** 用路径点当终点（起点永远是玩家现在站的地方）。 */
    public static Plan toWaypoint(Waypoint to) {
        if (PlayerUtils.player() == null) {
            return new Plan(null, to, "没有玩家", null);
        }

        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            return new Plan(null, to, "拿不到玩家位置", null);
        }

        Waypoint from = WaypointManager.get().playerStartWaypoint();
        if (from == null) {
            return new Plan(null, to, "你现在所在的 " + here.toShortString()
                + " 既不是路径点，也不在任何边上，先用 /pc waypoint add_waypoint here 加一个", null);
        }

        if (from.id() == to.id()) {
            return new Plan(null, to, null, null);
        }

        List<Waypoint> path = WaypointManager.get().graph().shortestPath(from.id(), to.id());
        if (path == null || path.size() < 2) {
            return new Plan(null, to, "从 " + DimensionUtils.display(DimensionUtils.current()) + " "
                + here.toShortString() + " 到 " + DimensionUtils.display(to.dimension()) + " "
                + to.pos().toShortString() + " 找不到路径", null);
        }

        return new Plan(path, to, null, null);
    }
}
