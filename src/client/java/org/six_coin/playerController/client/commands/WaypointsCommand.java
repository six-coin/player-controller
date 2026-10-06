package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.DirectedEdge;
import org.six_coin.playerController.client.waypoint.Edge;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;

/**
 * {@code /pc waypoints ...} —— 路径点与边的管理。
 */
public final class WaypointsCommand {

    /** 点击事件里补全命令用的前缀。 */
    private static final String ROOT = "/pc waypoints ";

    /** list 最多列多少行（避免刷屏）。 */
    private static final int LIST_LIMIT = 80;

    private WaypointsCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("waypoints")
            .executes(WaypointsCommand::status)
            .then(addWaypoint())
            .then(addSide())
            .then(delWaypoint())
            .then(delByName())
            .then(delSide())
            .then(ClientCommandManager.literal("del_portal")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                            .executes(WaypointsCommand::delPortal)))))
            .then(ClientCommandManager.literal("optimize")
                .executes(WaypointsCommand::optimize))
            .then(ClientCommandManager.literal("show")
                .executes(WaypointsCommand::toggleShow))
            .then(ClientCommandManager.literal("edit")
                .executes(WaypointsCommand::toggleEdit))
            .then(ClientCommandManager.literal("set_here_name")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(WaypointsCommand::setHereName)))
            .then(setName())
            .then(ClientCommandManager.literal("list")
                .executes(WaypointsCommand::list));
    }

    // ------------------------------------------------------------------
    // 子命令定义
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> addWaypoint() {
        return ClientCommandManager.literal("add_waypoint")
            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .suggests(LookSuggestions::x)
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::y)
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::z)
                        .executes(context -> addWaypoint(context, null))
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                            .executes(context -> addWaypoint(context, StringArgumentType.getString(context, "name")))))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> addSide() {
        return ClientCommandManager.literal("add_side")
            .then(ClientCommandManager.argument("x1", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y1", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z1", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("x2", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y2", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z2", IntegerArgumentType.integer())
                                    .executes(WaypointsCommand::addSide)))))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> delWaypoint() {
        return ClientCommandManager.literal("del_waypoint")
            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .executes(WaypointsCommand::delWaypoint))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> delByName() {
        return ClientCommandManager.literal("del")
            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                .executes(WaypointsCommand::delByName));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> delSide() {
        return ClientCommandManager.literal("del_side")
            .then(ClientCommandManager.argument("x1", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y1", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z1", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("x2", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y2", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z2", IntegerArgumentType.integer())
                                    .executes(WaypointsCommand::delSide)))))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> setName() {
        return ClientCommandManager.literal("set_name")
            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                            .executes(WaypointsCommand::setName)))));
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointManager manager = WaypointManager.get();
        WaypointGraph graph = manager.graph();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7路径点（world_" + manager.loadedWorld()
            + "，当前维度 " + DimensionUtils.display(DimensionUtils.current()) + "）："
            + graph.size() + " 个点，" + graph.normalEdgeCount() + " 条普通边，"
            + graph.oneWayEdgeCount() + " 条单向边"));
        source.sendFeedback(Text.literal("  显示: " + (manager.isShowing() ? "§a开" : "§c关")
            + "§r  编辑模式: " + (manager.isEditMode() ? "§a开" : "§c关")));
        source.sendFeedback(Text.literal("  文件: " + manager.currentFile()));
        return 1;
    }

    private static int addWaypoint(CommandContext<FabricClientCommandSource> context, String name) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = readPos(context, "x", "y", "z");
        String dimension = DimensionUtils.current();

        if (name != null && !WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint existing = graph.at(dimension, pos);

        if (existing != null) {
            if (name != null) {
                if (!graph.setName(existing.id(), name)) {
                    source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
                    return 0;
                }
                WaypointManager.get().save();
                source.sendFeedback(Text.literal("已把 " + pos.toShortString() + " 命名为 " + name));
                return 1;
            }
            source.sendFeedback(Text.literal("该位置已经有路径点了: " + pos.toShortString()));
            return 1;
        }

        // 如果这个位置正好在某条边的中间，先把那条边拆开，
        // 保证「路径点不会落在边的内部」这个不变量一直成立
        int splits = graph.splitEdgeAt(dimension, pos);
        Waypoint created = graph.ensureWaypoint(dimension, pos);
        if (name != null && !graph.setName(created.id(), name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已在 " + DimensionUtils.display(dimension) + " 添加路径点 "
            + pos.toShortString()
            + (name == null ? "" : "（名称 " + name + "）")
            + (splits > 0 ? "，并把它所在的 " + splits + " 条边从中间拆开了" : "")));
        return 1;
    }

    private static int addSide(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos a = readPos(context, "x1", "y1", "z1");
        BlockPos b = readPos(context, "x2", "y2", "z2");
        String dimension = DimensionUtils.current();

        if (WaypointGraph.sharedAxis(a, b) == null) {
            source.sendError(Text.literal("两个路径点必须有两个坐标相等（只能沿一个轴移动）"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        try {
            graph.addSegment(dimension, a, b);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal(e.getMessage()));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已在 " + DimensionUtils.display(dimension) + " 添加边 "
            + a.toShortString() + " <-> " + b.toShortString()));
        return 1;
    }

    private static int delWaypoint(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = readPos(context, "x", "y", "z");
        String dimension = DimensionUtils.current();

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint waypoint = graph.at(dimension, pos);
        if (waypoint == null) {
            source.sendError(Text.literal("当前维度（" + DimensionUtils.display(dimension) + "）的 "
                + pos.toShortString() + " 没有路径点"));
            return 0;
        }

        graph.removeWaypoint(waypoint.id());
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除路径点 " + pos.toShortString() + " 以及和它相连的所有边"));
        return 1;
    }

    private static int delByName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint waypoint = graph.byName(name);
        if (waypoint == null) {
            source.sendError(Text.literal("没有叫 " + name + " 的路径点"));
            return 0;
        }

        BlockPos pos = waypoint.pos();
        graph.removeWaypoint(waypoint.id());
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除路径点 " + name + " " + pos.toShortString() + " 以及和它相连的所有边"));
        return 1;
    }

    private static int delSide(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos a = readPos(context, "x1", "y1", "z1");
        BlockPos b = readPos(context, "x2", "y2", "z2");
        String dimension = DimensionUtils.current();

        WaypointGraph graph = WaypointManager.get().graph();
        if (!graph.removeEdgeAt(dimension, a, b)) {
            source.sendError(Text.literal("这两个路径点之间没有边"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除边 " + a.toShortString() + " <-> " + b.toShortString()));
        return 1;
    }

    private static int delPortal(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = readPos(context, "x", "y", "z");
        String dimension = DimensionUtils.current();

        WaypointGraph graph = WaypointManager.get().graph();
        if (graph.at(dimension, pos) == null) {
            source.sendError(Text.literal("当前维度（" + DimensionUtils.display(dimension)
                + "）的 " + pos.toShortString() + " 没有路径点"));
            return 0;
        }

        int removed = graph.removePortalEdgesAt(dimension, pos);
        if (removed == 0) {
            source.sendError(Text.literal(pos.toShortString() + " 上没有传送门边"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除 " + pos.toShortString() + " 上的 " + removed + " 条传送门边"));
        return 1;
    }

    private static int optimize(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointGraph graph = WaypointManager.get().graph();

        int before = graph.size();
        int merged = graph.optimize();
        WaypointManager.get().save();

        if (merged == 0) {
            source.sendFeedback(Text.literal("没有可以合并的路径点"));
        } else {
            source.sendFeedback(Text.literal("已合并 " + merged + " 个中间路径点，路径点数量 "
                + before + " → " + graph.size()));
        }
        return 1;
    }

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = WaypointManager.get().toggleShow();
        context.getSource().sendFeedback(Text.literal("路径点显示已"
            + (showing ? "§a开启" : "§c关闭")));
        return 1;
    }

    private static int toggleEdit(CommandContext<FabricClientCommandSource> context) {
        WaypointManager manager = WaypointManager.get();
        boolean editing = manager.toggleEditMode();
        context.getSource().sendFeedback(Text.literal("路径点编辑模式已"
            + (editing ? "§a开启" : "§c关闭")));
        if (editing) {
            context.getSource().sendFeedback(Text.literal(
                "§7此时执行 /pc move 会把起点、终点记成路径点，中间记成一条边（自动保存）"));
        }
        return 1;
    }

    private static int setHereName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        if (!WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }

        BlockPos pos = PlayerUtils.currentBlockPos();
        if (pos == null) {
            source.sendError(Text.literal("没有玩家"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint waypoint = graph.at(DimensionUtils.current(), pos);
        if (waypoint == null) {
            source.sendError(Text.literal("你现在站的 " + pos.toShortString() + " 不是路径点"));
            return 0;
        }
        if (!graph.setName(waypoint.id(), name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已把 " + pos.toShortString() + " 命名为 " + name));
        return 1;
    }

    private static int setName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = readPos(context, "x", "y", "z");
        String name = StringArgumentType.getString(context, "name");

        if (!WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint waypoint = graph.at(DimensionUtils.current(), pos);
        if (waypoint == null) {
            source.sendError(Text.literal("当前维度（" + DimensionUtils.display(DimensionUtils.current())
                + "）的 " + pos.toShortString() + " 没有路径点"));
            return 0;
        }
        if (!graph.setName(waypoint.id(), name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已把 " + pos.toShortString() + " 命名为 " + name));
        return 1;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointManager manager = WaypointManager.get();
        WaypointGraph graph = manager.graph();
        String here = DimensionUtils.current();

        ChatUtils.debug("输出路径点列表：world_" + manager.loadedWorld() + "，当前维度 " + here);

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7所有路径点（" + DimensionUtils.display(here) + " 里的可以直接管理）："));
        if (graph.isEmpty()) {
            source.sendFeedback(Text.literal("  §8（空）"));
        } else {
            int shown = 0;
            for (Waypoint waypoint : graph.allWaypoints()) {
                if (shown++ >= LIST_LIMIT) {
                    source.sendFeedback(Text.literal("  §8… 还有 " + (graph.size() - LIST_LIMIT) + " 个没有显示"));
                    break;
                }
                source.sendFeedback(waypointLine(graph, waypoint, waypoint.dimension().equals(here)));
            }
        }

        source.sendFeedback(Text.literal("§7所有边："));
        int normal = 0;
        for (Edge edge : graph.allEdges()) {
            if (graph.isPortalEdge(edge)) continue;
            if (normal++ >= LIST_LIMIT) {
                source.sendFeedback(Text.literal("  §8… 还有更多没有显示"));
                break;
            }
            source.sendFeedback(edgeLine(graph, edge, here));
        }
        if (normal == 0) source.sendFeedback(Text.literal("  §8（空）"));

        source.sendFeedback(Text.literal("§d传送门边（长度 0）："));
        int portals = 0;
        for (Edge edge : graph.allEdges()) {
            if (!graph.isPortalEdge(edge)) continue;
            if (portals++ >= LIST_LIMIT) break;
            source.sendFeedback(portalEdgeLine(graph, edge, here));
        }
        for (DirectedEdge edge : graph.allOneWayEdges()) {
            if (portals++ >= LIST_LIMIT) break;
            source.sendFeedback(oneWayEdgeLine(graph, edge, here));
        }
        if (portals == 0) source.sendFeedback(Text.literal("  §8（空）"));

        return 1;
    }

    private static Text waypointLine(WaypointGraph graph, Waypoint waypoint, boolean manageable) {
        MutableText line = Text.literal(" 坐标：").formatted(Formatting.GRAY);
        line.append(dimensionTag(waypoint.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(waypoint.pos()));

        if (waypoint.hasName()) {
            line.append(Text.literal("；名称：").formatted(Formatting.GRAY));
            line.append(Text.literal("[" + waypoint.name() + "]")
                .setStyle(Style.EMPTY
                    .withColor(Formatting.GREEN)
                    .withClickEvent(new ClickEvent.CopyToClipboard(waypoint.name()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击复制名称")))));
        }

        if (!manageable) {
            line.append(Text.literal(" §8（在 " + DimensionUtils.display(waypoint.dimension())
                + "，过去之后才能改）"));
            return line;
        }

        line.append(Text.literal(" "));
        line.append(actionButton(waypoint.hasName() ? "修改名称" : "设置名称",
            ROOT + "set_name " + waypoint.coordString() + " ", "点击把命令填到聊天栏"));
        line.append(Text.literal(" "));
        line.append(actionButton("删除", ROOT + "del_waypoint " + waypoint.coordString(),
            "点击把删除命令填到聊天栏"));
        return line;
    }

    private static Text edgeLine(WaypointGraph graph, Edge edge, String here) {
        Waypoint a = graph.get(edge.a());
        Waypoint b = graph.get(edge.b());

        MutableText line = Text.literal(" ").formatted(Formatting.GRAY);
        if (a == null || b == null) {
            line.append(Text.literal("（无效的边）"));
            return line;
        }

        line.append(clickableCoord(a.pos()));
        line.append(Text.literal(" <-> ").formatted(Formatting.GRAY));
        line.append(clickableCoord(b.pos()));
        line.append(Text.literal(" 长度：" + graph.edgeLength(edge)).formatted(Formatting.GRAY));

        if (a.dimension().equals(here) && b.dimension().equals(here)) {
            line.append(Text.literal(" "));
            line.append(actionButton("删除",
                ROOT + "del_side " + a.coordString() + " " + b.coordString(),
                "点击把删除命令填到聊天栏"));
        }
        return line;
    }

    private static Text portalEdgeLine(WaypointGraph graph, Edge edge, String here) {
        Waypoint a = graph.get(edge.a());
        Waypoint b = graph.get(edge.b());

        MutableText line = Text.literal(" ").formatted(Formatting.GRAY);
        if (a == null || b == null) {
            line.append(Text.literal("（无效的边）"));
            return line;
        }

        line.append(dimensionTag(a.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(a.pos()));
        line.append(Text.literal(" <-> ").formatted(Formatting.GRAY));
        line.append(dimensionTag(b.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(b.pos()));

        // 传送门边跨维度，删除命令只能在当前维度的那一端执行
        Waypoint local = a.dimension().equals(here) ? a : (b.dimension().equals(here) ? b : null);
        if (local != null) {
            line.append(Text.literal(" "));
            line.append(actionButton("删除", ROOT + "del_portal " + local.coordString(),
                "点击把删除命令填到聊天栏（站在这一端所在维度执行）"));
        } else {
            line.append(Text.literal(" §8（两端都不在你当前维度，先去其中一端）"));
        }
        return line;
    }

    private static Text oneWayEdgeLine(WaypointGraph graph, DirectedEdge edge, String here) {
        Waypoint from = graph.get(edge.from());
        Waypoint to = graph.get(edge.to());

        MutableText line = Text.literal(" ").formatted(Formatting.GRAY);
        if (from == null || to == null) {
            line.append(Text.literal("（无效的边）"));
            return line;
        }

        line.append(dimensionTag(from.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(from.pos()));
        line.append(Text.literal(" -> ").formatted(Formatting.GRAY));
        line.append(dimensionTag(to.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(to.pos()));
        line.append(Text.literal(" §8单向").formatted(Formatting.GRAY));

        if (from.dimension().equals(here)) {
            line.append(Text.literal(" "));
            line.append(actionButton("删除", ROOT + "del_portal " + from.coordString(),
                "点击把删除命令填到聊天栏"));
        }
        return line;
    }

    /** 维度小标签。 */
    private static Text dimensionTag(String dimension) {
        Formatting color = switch (dimension) {
            case DimensionUtils.NETHER -> Formatting.RED;
            case DimensionUtils.END -> Formatting.LIGHT_PURPLE;
            default -> Formatting.GREEN;
        };
        return Text.literal("[" + DimensionUtils.display(dimension) + "]").formatted(color);
    }

    /** 坐标：[1 1 1]，点一下复制。 */
    private static Text clickableCoord(BlockPos pos) {
        String text = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Text.literal("[" + text + "]")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(text))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击复制坐标"))));
    }

    /** 会往聊天栏里补全命令的按钮。 */
    private static Text actionButton(String label, String command, String hint) {
        return Text.literal("[" + label + "]")
            .setStyle(Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hint))));
    }

    // ------------------------------------------------------------------

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context,
                                    String xName, String yName, String zName) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, xName),
            IntegerArgumentType.getInteger(context, yName),
            IntegerArgumentType.getInteger(context, zName));
    }

    /** 供别的命令复用：当前 world 下所有路径点的名字。 */
    public static List<String> names() {
        return WaypointManager.get().graph().allWaypoints().stream()
            .filter(Waypoint::hasName)
            .map(Waypoint::name)
            .toList();
    }
}
