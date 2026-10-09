package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;

/**
 * {@code /pc move ...}
 *
 * <pre>
 * /pc move &lt;x|y|z&gt; &lt;blocks&gt;   沿轴移动指定格数
 * /pc move to &lt;x&gt; &lt;y&gt; &lt;z&gt;     沿路径点图走到某个路径点（坐标必须已经是路径点）
 * /pc move to &lt;name&gt;          同上，用名字指定
 * /pc move to_name &lt;name&gt;     用名字指定终点（跟 to &lt;name&gt; 等价）
 * /pc move to_id &lt;id&gt;         用编号指定终点
 * /pc move to_position &lt;x&gt; &lt;y&gt; &lt;z&gt;  用坐标指定终点：
 *                             ① 是路径点就直接走；② 在某条边中间就先在那边新建路径点再走；③ 都不是就报错
 * /pc move cancel             取消当前和排队的移动任务
 * </pre>
 *
 * <p>走路径的起点：站在路径点上直接用；站在某条边的中间就先在脚下新建一个路径点
 * （边会被切开，新建的点留着不回退）；既不在路径点上也不在任何边上就拒绝执行。
 *
 * <p>{@code move to} 走的都是已知边，不会记录任何东西（要记边请用 {@code move <轴>} / {@code move face}）。
 */
public final class MoveCommand {

    private MoveCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal("move");
        node.then(axis("x", Direction.Axis.X));
        node.then(axis("y", Direction.Axis.Y));
        node.then(axis("z", Direction.Axis.Z));
        node.then(moveTo());
        node.then(moveToNameNode());
        node.then(moveToIdNode());
        node.then(moveToPositionNode());
        node.then(ClientCommandManager.literal("face")
            .then(ClientCommandManager.argument("blocks", IntegerArgumentType.integer(1))
                .executes(context -> face(context.getSource(),
                    IntegerArgumentType.getInteger(context, "blocks")))));
        node.then(ClientCommandManager.literal("cancel")
            .executes(context -> cancel(context.getSource())));
        return node;
    }

    /**
     * 朝当前朝向走 num 格。
     *
     * <p>用视线向量取最近的六个正方向之一，所以抬头 / 低头就是上下（走 y 轴），
     * 平视就是东西南北。
     */
    private static int face(FabricClientCommandSource source, int blocks) {
        ClientPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("没有玩家"));
            return 0;
        }

        Vec3d look = player.getRotationVec(1.0f);
        Direction direction = Direction.getFacing(look);
        int sign = direction.getDirection().offset();

        ActionManager.get().submit(new MoveAction(direction.getAxis(), blocks * sign));

        ChatUtils.info("已提交任务：朝 " + directionName(direction) + " 移动 " + blocks + " 格"
            + "（速度 " + PlayerControllerConfig.getMoveSpeed() + " 格/tick）");
        return 1;
    }

    private static String directionName(Direction direction) {
        return switch (direction) {
            case UP -> "上";
            case DOWN -> "下";
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
        };
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> axis(String name, Direction.Axis axis) {
        return ClientCommandManager.literal(name).then(
            ClientCommandManager.argument("blocks", IntegerArgumentType.integer())
                .executes(context -> {
                    int blocks = IntegerArgumentType.getInteger(context, "blocks");
                    FabricClientCommandSource source = context.getSource();

                    if (blocks == 0) {
                        source.sendError(Text.literal("移动距离不能为 0"));
                        return 0;
                    }
                    if (source.getPlayer() == null) {
                        source.sendError(Text.literal("没有玩家"));
                        return 0;
                    }

                    ActionManager.get().submit(new MoveAction(axis, blocks));
                    ChatUtils.info("已提交任务：沿 " + name + " 轴移动 " + blocks + " 格"
                        + "（速度 " + PlayerControllerConfig.getMoveSpeed() + " 格/tick）");
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> moveTo() {
        return ClientCommandManager.literal("to")
            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .executes(context -> moveToBlock(context.getSource(), new BlockPos(
                            IntegerArgumentType.getInteger(context, "x"),
                            IntegerArgumentType.getInteger(context, "y"),
                            IntegerArgumentType.getInteger(context, "z")))))))
            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> CommandSource.suggestMatching(names(), builder))
                .executes(context -> moveToName(context.getSource(),
                    StringArgumentType.getString(context, "name"))));
    }

    /** {@code to_name <name>}：跟 {@code to <name>} 一样。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> moveToNameNode() {
        return ClientCommandManager.literal("to_name")
            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> CommandSource.suggestMatching(names(), builder))
                .executes(context -> moveToName(context.getSource(),
                    StringArgumentType.getString(context, "name"))));
    }

    /** {@code to_id <id>}：只能用路径点编号。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> moveToIdNode() {
        return ClientCommandManager.literal("to_id")
            .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                .executes(context -> moveToId(context.getSource(),
                    IntegerArgumentType.getInteger(context, "id"))));
    }

    /**
     * {@code to_position <x> <y> <z> [dim]}：是路径点就直接走，在边上就先建点，都不是就报错。
     *
     * <p>不写 {@code [dim]} 就是当前维度（补全会给维度）。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> moveToPositionNode() {
        return ClientCommandManager.literal("to_position")
            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .suggests(LookSuggestions::x)
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::y)
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::z)
                        .executes(context -> moveToPosition(context.getSource(), readPos(context),
                            DimensionUtils.current()))
                        .then(ClientCommandManager.argument("dim", StringArgumentType.greedyString())
                            .suggests(DimensionSuggestions::suggest)
                            .executes(context -> moveToPosition(context.getSource(), readPos(context),
                                readDimension(context)))))));
    }

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
    }

    /** 可选的 [dim]：没写就是当前维度。 */
    private static String readDimension(CommandContext<FabricClientCommandSource> context) {
        try {
            String dim = StringArgumentType.getString(context, "dim");
            return dim == null || dim.isBlank() ? DimensionUtils.current() : dim.trim();
        } catch (IllegalArgumentException e) {
            return DimensionUtils.current();
        }
    }

    // ------------------------------------------------------------------

    private static int moveToBlock(FabricClientCommandSource source, BlockPos target) {
        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint to = graph.at(DimensionUtils.current(), target);
        if (to == null) {
            source.sendError(Text.literal("当前维度（" + DimensionUtils.display(DimensionUtils.current())
                + "）的 " + target.toShortString() + " 不是路径点"
                + "（坐标在边上的情况请用 /pc move to_position）"));
            return 0;
        }
        return startPath(source, to);
    }

    private static int moveToName(FabricClientCommandSource source, String name) {
        Waypoint to = WaypointManager.get().graph().byName(name);
        if (to == null) {
            source.sendError(Text.literal("没有叫 " + name + " 的路径点"));
            return 0;
        }
        return startPath(source, to);
    }

    private static int moveToId(FabricClientCommandSource source, int id) {
        Waypoint to = WaypointManager.get().graph().get(id);
        if (to == null) {
            source.sendError(Text.literal("没有 #" + id + " 这个路径点"));
            return 0;
        }
        return startPath(source, to);
    }

    /**
     * 用坐标指定终点。
     *
     * <p>① 这个坐标已经是路径点 → 直接按编号走；
     * ② 这个坐标在某条边中间 → 先在那边新建一个路径点（边会被切开，点留着不回退），再走；
     * ③ 都不是 → 报错。
     *
     * <p>{@code dimension} 是终点所在的维度（不写就是当前维度）；跨维度的话路径会自动经过传送门边。
     */
    private static int moveToPosition(FabricClientCommandSource source, BlockPos target, String dimension) {
        WaypointGraph graph = WaypointManager.get().graph();

        Waypoint to = graph.at(dimension, target);
        if (to != null) {
            ChatUtils.debug("to_position：" + DimensionUtils.display(dimension) + " " + target.toShortString()
                + " 已经是路径点 #" + to.id() + "，直接走");
            return startPath(source, to);
        }

        WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, target);
        if (edge == null) {
            source.sendError(Text.literal(DimensionUtils.display(dimension) + " 的 "
                + target.toShortString() + " 既不是路径点，也不在任何边上"));
            return 0;
        }

        to = WaypointManager.get().createWaypoint(dimension, target);
        ChatUtils.debug("to_position：" + target.toShortString() + " 在边 #" + edge.id()
            + " 上，已新建路径点 #" + to.id());
        source.sendFeedback(Text.literal("目标 " + DimensionUtils.display(dimension) + " "
            + target.toShortString() + " 在边 #" + edge.id() + " 上，已在那边新建路径点 #" + to.id()));
        return startPath(source, to);
    }

    private static int startPath(FabricClientCommandSource source, Waypoint target) {
        if (source.getPlayer() == null) {
            source.sendError(Text.literal("没有玩家"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        String dimension = DimensionUtils.current();

        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }

        // 起点：站在路径点上就直接用；站在某条边中间，就先在脚下新建一个路径点（边会被切开）
        Waypoint from = WaypointManager.get().playerStartWaypoint();
        if (from == null) {
            source.sendError(Text.literal("你现在所在的 " + here.toShortString()
                + " 既不是路径点，也不在任何边上，先用 /pc w waypoint_add_here 加一个"));
            return 0;
        }

        if (from.id() == target.id()) {
            source.sendFeedback(Text.literal("你已经站在 " + target.pos().toShortString() + " 了"));
            return 1;
        }

        // 必须找得到路
        List<Waypoint> path = graph.shortestPath(from.id(), target.id());
        if (path == null || path.size() < 2) {
            source.sendError(Text.literal("从 " + DimensionUtils.display(dimension) + " " + here.toShortString()
                + " 到 " + DimensionUtils.display(target.dimension()) + " " + target.pos().toShortString()
                + " 找不到路径"));
            return 0;
        }

        ActionManager.get().submit(new PathMoveAction(path));
        source.sendFeedback(Text.literal("已提交任务：从 " + DimensionUtils.display(dimension) + " "
            + here.toShortString() + " 走到 " + DimensionUtils.display(target.dimension()) + " "
            + target.pos().toShortString() + "（" + (path.size() - 1) + " 段）"));
        return 1;
    }

    private static int cancel(FabricClientCommandSource source) {
        int cancelled = ActionManager.get().cancelMoves();
        if (cancelled == 0) {
            source.sendFeedback(Text.literal("当前没有移动任务"));
        } else {
            source.sendFeedback(Text.literal("已取消 " + cancelled + " 个移动任务"));
        }
        return 1;
    }

    private static List<String> names() {
        return WaypointsCommand.names();
    }
}
