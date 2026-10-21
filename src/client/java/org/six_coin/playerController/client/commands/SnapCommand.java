package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.mine.station.MineStationAccess;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /pc snap ...} —— 把玩家「贴」到某个坐标上（只用沿轴的三段直线飞过去）。
 *
 * <pre>
 * /pc snap absolute &lt;x&gt; &lt;y&gt; &lt;z&gt;   目的地就是 x y z（当前维度）
 * /pc snap relative &lt;dx&gt; &lt;dy&gt; &lt;dz&gt; 目的地是当前方块坐标 + 偏移
 * /pc snap network                   目的地是当前维度里最近的「路径点 / 边上的坐标」
 * </pre>
 *
 * <p>走法：把「先走哪个轴」的 6 种排列都试一遍 —— x→y→z、x→z→y、y→x→z、y→z→x、z→x→y、z→y→x，
 * 取第一种通的，然后按 {@code /pc move x/y/z <格数>} 那样三段飞过去（就是排队三个
 * {@link MoveAction}，所以会先对齐方块中心、速度也用 {@code actions.move_speed}）。
 *
 * <p>「通」的判定：这条路经过的每一格方块、以及它上面那一格，都必须是空气
 * （人高 2 格，所以要看上面一格）。起点自己那一格不看（玩家就站在那儿，可能是草 / 雪片），
 * 但起点头顶那一格还是要空。
 *
 * <p>目的地必须在**已加载**的区块里（不然读不到方块、也飞不过去），否则直接拒绝。
 * {@code network} 只在 1000 格半径内找，而且只找已加载区块里的坐标。
 */
public final class SnapCommand {

    /** {@code network} 的搜索半径（格）。 */
    private static final int NETWORK_SEARCH_RADIUS = 1000;

    /** 6 种轴顺序，按这个顺序试，第一个通的就用。 */
    private static final Direction.Axis[][] ORDERS = {
        {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z},
        {Direction.Axis.X, Direction.Axis.Z, Direction.Axis.Y},
        {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z},
        {Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X},
        {Direction.Axis.Z, Direction.Axis.X, Direction.Axis.Y},
        {Direction.Axis.Z, Direction.Axis.Y, Direction.Axis.X},
    };

    /** 算好的一条走法：轴顺序 + 途经的拐点（含起点、终点）。 */
    private record Plan(Direction.Axis[] order, List<BlockPos> points) {
    }

    private SnapCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("snap")
            .executes(context -> {
                showHelp(context.getSource());
                return 1;
            })
            .then(ClientCommandManager.literal("absolute").then(coords(
                context -> {
                    BlockPos target = new BlockPos(
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"));
                    return snap(context.getSource(), target, "absolute");
                })))
            .then(ClientCommandManager.literal("relative").then(deltas(
                context -> {
                    BlockPos from = PlayerUtils.currentBlockPos();
                    if (from == null) {
                        context.getSource().sendError(Text.literal("拿不到玩家位置"));
                        return 0;
                    }
                    BlockPos target = from.add(
                        IntegerArgumentType.getInteger(context, "dx"),
                        IntegerArgumentType.getInteger(context, "dy"),
                        IntegerArgumentType.getInteger(context, "dz"));
                    return snap(context.getSource(), target, "relative");
                })))
            .then(ClientCommandManager.literal("network")
                .executes(context -> network(context.getSource())));
    }

    private static void showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7snap：沿轴三段直线贴到某个坐标（先试 6 种轴顺序，取第一种通的）"));
        source.sendFeedback(Text.literal("  §f/pc snap absolute <x> <y> <z>§7 - 去这个坐标（当前维度）"));
        source.sendFeedback(Text.literal("  §f/pc snap relative <dx> <dy> <dz>§7 - 去当前坐标 + 偏移"));
        source.sendFeedback(Text.literal("  §f/pc snap network§7 - 去最近的路径点 / 边上的坐标（"
            + NETWORK_SEARCH_RADIUS + " 格内，只找已加载的区块）"));
        source.sendFeedback(Text.literal("  §7经过的每一格和它上面一格都必须是空气；目的地不在已加载范围内直接拒绝"));
    }

    // ------------------------------------------------------------------
    // 三种入口
    // ------------------------------------------------------------------

    private static int network(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        BlockPos target = nearestNetwork();
        if (target == null) {
            source.sendError(Text.literal("当前维度（" + DimensionUtils.display(DimensionUtils.current())
                + "）" + NETWORK_SEARCH_RADIUS + " 格内没有已加载的路径点 / 边，没法 snap"));
            return 0;
        }
        return snap(source, target, "network");
    }

    /**
     * 执行一次 snap。
     *
     * @param how 命令形态（只用于聊天栏里说明）
     */
    private static int snap(FabricClientCommandSource source, BlockPos target, String how) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = source.getPlayer();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        BlockPos from = PlayerUtils.currentBlockPos();
        if (from == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        if (from.equals(target)) {
            source.sendFeedback(Text.literal("你已经站在 " + target.toShortString() + " 了（" + how + "）"));
            return 1;
        }

        // 目的地不在已加载的区块里就直接拒绝：读不到方块、也不该往没加载的地方飞
        if (!isLoaded(mc, target)) {
            source.sendError(Text.literal("目的地 " + target.toShortString()
                + " 不在当前已加载的区块里（离你 " + format(distanceTo(player, target))
                + " 格），先走近一点再 snap"));
            return 0;
        }

        Plan plan = plan(mc, from, target);
        if (plan == null) {
            source.sendError(Text.literal("6 种走法都不通：从 " + from.toShortString() + " 到 "
                + target.toShortString() + "，沿轴走的每一格和它上面一格都得是空气"));
            return 0;
        }

        for (int i = 1; i < plan.points().size(); i++) {
            BlockPos previous = plan.points().get(i - 1);
            BlockPos next = plan.points().get(i);
            Direction.Axis axis = WaypointGraph.sharedAxis(previous, next);
            if (axis == null) continue;

            int delta = coord(next, axis) - coord(previous, axis);
            if (delta != 0) ActionManager.get().submit(new MoveAction(axis, delta));
        }

        source.sendFeedback(Text.literal("已提交任务：snap 到 " + DimensionUtils.display(DimensionUtils.current())
            + " " + target.toShortString() + "（" + how + "）"));
        source.sendFeedback(Text.literal("  走法 " + describeOrder(plan.order())
            + "：" + describeSegments(plan.points())));
        return 1;
    }

    // ------------------------------------------------------------------
    // 找走法
    // ------------------------------------------------------------------

    /** 6 种轴顺序里第一种通的；都不通返回 null。 */
    @Nullable
    private static Plan plan(MinecraftClient mc, BlockPos from, BlockPos to) {
        for (Direction.Axis[] order : ORDERS) {
            List<BlockPos> points = new ArrayList<>(4);
            points.add(from);

            BlockPos current = from;
            for (Direction.Axis axis : order) {
                BlockPos next = withAxis(current, axis, coord(to, axis));
                if (!next.equals(current)) points.add(next);
                current = next;
            }
            if (points.size() == 1) continue;   // 原地，上面已经处理过了

            if (clear(mc, points)) return new Plan(order, points);
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

    // ------------------------------------------------------------------
    // network：最近的路径点 / 边上的坐标
    // ------------------------------------------------------------------

    /** 当前维度里离玩家最近的「路径点或者某条边上的方块」；{@link #NETWORK_SEARCH_RADIUS} 内找不到返回 null。 */
    @Nullable
    private static BlockPos nearestNetwork() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return null;

        Map<String, Set<Long>> occupied = MineStationAccess.buildOccupied(WaypointManager.get().graph());
        Set<Long> positions = occupied.get(DimensionUtils.current());
        if (positions == null || positions.isEmpty()) return null;

        double limit = (double) NETWORK_SEARCH_RADIUS * NETWORK_SEARCH_RADIUS;
        Vec3d here = player.getEntityPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (long packed : positions) {
            BlockPos pos = BlockPos.fromLong(packed);
            double distance = here.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance > limit) continue;         // 超出搜索半径
            if (!isLoaded(mc, pos)) continue;       // 没加载的区块不进去（进了也读不到方块）

            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /** 这个坐标所在的区块加载了没有。 */
    private static boolean isLoaded(MinecraftClient mc, BlockPos pos) {
        return mc.world != null && mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static String format(double value) {
        return String.format("%.1f", value);
    }

    private static double distanceTo(ClientPlayerEntity player, BlockPos pos) {
        return player.getEntityPos().distanceTo(Vec3d.ofCenter(pos));
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    private interface SnapExecutor {
        int run(CommandContext<FabricClientCommandSource> context);
    }

    /** {@code x y z} 三个整数（absolute）。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> coords(SnapExecutor executor) {
        return ClientCommandManager.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(executor::run)));
    }

    /** {@code dx dy dz} 三个整数（relative）。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> deltas(SnapExecutor executor) {
        return ClientCommandManager.argument("dx", IntegerArgumentType.integer())
            .then(ClientCommandManager.argument("dy", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("dz", IntegerArgumentType.integer())
                    .executes(executor::run)));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static int coord(BlockPos pos, Direction.Axis axis) {
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

    private static String describeOrder(Direction.Axis[] order) {
        StringBuilder text = new StringBuilder();
        for (Direction.Axis axis : order) {
            if (text.length() > 0) text.append("→");
            text.append(MoveAction.axisName(axis));
        }
        return text.toString();
    }

    /** 「x +50、y -3、z +100」这种，按实际要走的段列出来。 */
    private static String describeSegments(List<BlockPos> points) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < points.size(); i++) {
            BlockPos previous = points.get(i - 1);
            BlockPos next = points.get(i);
            Direction.Axis axis = WaypointGraph.sharedAxis(previous, next);
            if (axis == null) continue;

            int delta = coord(next, axis) - coord(previous, axis);
            if (text.length() > 0) text.append("、");
            text.append(MoveAction.axisName(axis)).append(' ')
                .append(delta > 0 ? "+" : "").append(delta);
        }
        return text.toString();
    }
}
