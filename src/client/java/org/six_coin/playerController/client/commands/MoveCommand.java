package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
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
 * /pc move to &lt;x&gt; &lt;y&gt; &lt;z&gt;     沿路径点图走到某个路径点
 * /pc move to &lt;name&gt;          同上，用名字指定
 * /pc move cancel             取消当前和排队的移动任务
 * </pre>
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
        node.then(ClientCommandManager.literal("cancel")
            .executes(context -> cancel(context.getSource())));
        return node;
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

    // ------------------------------------------------------------------

    private static int moveToBlock(FabricClientCommandSource source, BlockPos target) {
        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint to = graph.at(target);
        if (to == null) {
            source.sendError(Text.literal(target.toShortString() + " 不是路径点"));
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

    private static int startPath(FabricClientCommandSource source, Waypoint target) {
        if (source.getPlayer() == null) {
            source.sendError(Text.literal("没有玩家"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();

        // 条件一：当前所在位置（取整后）本身必须是路径点
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        Waypoint from = graph.at(here);
        if (from == null) {
            source.sendError(Text.literal("你现在所在的 " + here.toShortString()
                + " 不是路径点，先用 /pc waypoints add_waypoint 加一个"));
            return 0;
        }

        if (from.id() == target.id()) {
            source.sendFeedback(Text.literal("你已经站在 " + target.pos().toShortString() + " 了"));
            return 1;
        }

        // 条件二：必须找得到路
        List<BlockPos> path = graph.shortestPath(from.id(), target.id());
        if (path == null || path.size() < 2) {
            source.sendError(Text.literal("从 " + here.toShortString() + " 到 "
                + target.pos().toShortString() + " 找不到路径"));
            return 0;
        }

        ActionManager.get().submit(new PathMoveAction(path));
        source.sendFeedback(Text.literal("已提交任务：从 " + here.toShortString() + " 走到 "
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
