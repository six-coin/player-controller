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
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Edge;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;

/**
 * {@code /pc w ...} —— 路径点、边、出生点，全部走这一套命令。
 *
 * <pre>
 * /pc w show
 * /pc w edit
 * /pc w waypoint_add &lt;x&gt; &lt;y&gt; &lt;z&gt; [name]
 * /pc w waypoint_add_here [name]
 * /pc w waypoint_name &lt;id&gt; &lt;name&gt;
 * /pc w waypoint_name_here &lt;name&gt;
 * /pc w spawn_set_by_name &lt;name&gt;
 * /pc w spawn_set_by_id &lt;id&gt;
 * /pc w spawn_del
 * /pc w side_add &lt;id&gt; &lt;id&gt;
 * /pc w del &lt;id&gt;
 * /pc w optimize
 * /pc w list
 * </pre>
 */
public final class WaypointsCommand {

    /** 点击事件里补全命令用的前缀。 */
    private static final String ROOT = "/pc w ";

    /** list 每段最多列多少行。 */
    private static final int LIST_LIMIT = 80;

    private WaypointsCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("w")
            .executes(WaypointsCommand::status)
            .then(ClientCommandManager.literal("show")
                .executes(WaypointsCommand::toggleShow))
            .then(ClientCommandManager.literal("edit")
                .executes(WaypointsCommand::toggleEdit))
            .then(waypointAdd())
            .then(ClientCommandManager.literal("waypoint_add_here")
                .executes(context -> waypointAddHere(context, null))
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(context -> waypointAddHere(context, StringArgumentType.getString(context, "name")))))
            .then(ClientCommandManager.literal("waypoint_name")
                .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(WaypointsCommand::waypointName))))
            .then(ClientCommandManager.literal("waypoint_name_here")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(WaypointsCommand::waypointNameHere)))
            .then(ClientCommandManager.literal("spawn_set_by_name")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests((context, builder) -> net.minecraft.command.CommandSource
                        .suggestMatching(names(), builder))
                    .executes(WaypointsCommand::spawnSetByName)))
            .then(ClientCommandManager.literal("spawn_set_by_id")
                .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                    .executes(WaypointsCommand::spawnSetById)))
            .then(ClientCommandManager.literal("spawn_del")
                .executes(WaypointsCommand::spawnDel))
            .then(ClientCommandManager.literal("side_add")
                .then(ClientCommandManager.argument("id1", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("id2", IntegerArgumentType.integer())
                        .executes(WaypointsCommand::sideAdd))))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                    .executes(WaypointsCommand::delete)))
            .then(ClientCommandManager.literal("optimize")
                .executes(WaypointsCommand::optimize))
            .then(ClientCommandManager.literal("list")
                .executes(WaypointsCommand::list));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> waypointAdd() {
        return ClientCommandManager.literal("waypoint_add")
            .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .suggests(LookSuggestions::x)
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::y)
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::z)
                        .executes(context -> waypointAdd(context, null))
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                            .executes(context -> waypointAdd(context,
                                StringArgumentType.getString(context, "name")))))));
    }

    // ------------------------------------------------------------------
    // 路径点
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointManager manager = WaypointManager.get();
        WaypointGraph graph = manager.graph();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7路径点（world_" + manager.loadedWorld()
            + "，当前维度 " + DimensionUtils.display(DimensionUtils.current()) + "）："
            + graph.normalCount() + " 个点，" + graph.edgeCount() + " 条边"));
        source.sendFeedback(Text.literal("  显示: " + (manager.isShowing() ? "§a开" : "§c关")
            + "§r  编辑模式: " + (manager.isEditMode() ? "§a开" : "§c关")));
        Waypoint spawn = graph.spawnWaypointTarget();
        source.sendFeedback(Text.literal("  当前出生点: " + (spawn == null
            ? "§c未设置"
            : "§a#" + spawn.id() + "§r " + DimensionUtils.display(spawn.dimension())
                + " " + spawn.coordString())));
        source.sendFeedback(Text.literal("  文件: " + manager.currentFile()));
        return 1;
    }

    private static int waypointAdd(CommandContext<FabricClientCommandSource> context, @Nullable String name) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = readPos(context, "x", "y", "z");
        return addWaypointAt(source, DimensionUtils.current(), pos, name);
    }

    private static int waypointAddHere(CommandContext<FabricClientCommandSource> context, @Nullable String name) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.currentBlockPos();
        if (pos == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        return addWaypointAt(source, DimensionUtils.current(), pos, name);
    }

    private static int addWaypointAt(FabricClientCommandSource source, String dimension,
                                     BlockPos pos, @Nullable String name) {
        if (name != null && !WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint existing = graph.at(dimension, pos);

        if (existing != null) {
            if (name != null) {
                if (existing.isSpawn()) {
                    source.sendError(Text.literal("那是出生点节点，不能改名"));
                    return 0;
                }
                if (!graph.setName(existing.id(), name)) {
                    source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
                    return 0;
                }
                WaypointManager.get().save();
                source.sendFeedback(Text.literal("已把 #" + existing.id() + " " + pos.toShortString()
                    + " 命名为 " + name));
                return 1;
            }
            source.sendFeedback(Text.literal("该位置已经有路径点了：#" + existing.id()));
            return 1;
        }

        // 落在某条边中间的话，先把那条边拆开
        int splits = graph.splitEdgeAt(dimension, pos);
        Waypoint created = graph.ensureWaypoint(dimension, pos);
        if (name != null && !graph.setName(created.id(), name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已添加路径点 #" + created.id() + " "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + (name == null ? "" : "（" + name + "）")
            + (splits > 0 ? "，并把它所在的 " + splits + " 条边从中间拆开了" : "")));
        return 1;
    }

    private static int waypointName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int id = IntegerArgumentType.getInteger(context, "id");
        String name = StringArgumentType.getString(context, "name");

        if (!WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint waypoint = graph.get(id);
        if (waypoint == null) {
            source.sendError(Text.literal("没有 #" + id + " 这个路径点"));
            return 0;
        }
        if (waypoint.isSpawn()) {
            source.sendError(Text.literal("0 号是出生点节点，名字跟着出生点走，不能单独改"));
            return 0;
        }
        if (!graph.setName(id, name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已把 #" + id + " 命名为 " + name));
        return 1;
    }

    private static int waypointNameHere(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        if (!WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }

        BlockPos pos = PlayerUtils.currentBlockPos();
        if (pos == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint waypoint = graph.at(DimensionUtils.current(), pos);
        if (waypoint == null) {
            source.sendError(Text.literal("你现在站的 " + pos.toShortString() + " 不是路径点"));
            return 0;
        }
        if (waypoint.isSpawn()) {
            source.sendError(Text.literal("这里是出生点节点，名字跟着出生点走；"
                + "先在这里用 waypoint_add_here 建一个普通路径点"));
            return 0;
        }
        if (!graph.setName(waypoint.id(), name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已把 #" + waypoint.id() + " 命名为 " + name));
        return 1;
    }

    // ------------------------------------------------------------------
    // 边
    // ------------------------------------------------------------------

    private static int sideAdd(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int id1 = IntegerArgumentType.getInteger(context, "id1");
        int id2 = IntegerArgumentType.getInteger(context, "id2");

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint a = graph.get(id1);
        Waypoint b = graph.get(id2);
        if (a == null || b == null) {
            source.sendError(Text.literal("路径点不存在：" + (a == null ? "#" + id1 : "#" + id2)));
            return 0;
        }
        if (id1 == id2) {
            source.sendError(Text.literal("两端不能是同一个路径点"));
            return 0;
        }

        if (a.dimension().equals(b.dimension())) {
            // 同一个维度里只能沿一个轴走，而且要走重叠切分那一套
            if (WaypointGraph.sharedAxis(a.pos(), b.pos()) == null) {
                source.sendError(Text.literal("同一维度里的两个路径点必须有两个坐标相等（只能沿一个轴移动）"));
                return 0;
            }
            try {
                graph.addSegment(a.dimension(), a.pos(), b.pos());
            } catch (IllegalArgumentException e) {
                source.sendError(Text.literal(e.getMessage()));
                return 0;
            }
        } else {
            if (graph.addEdge(id1, id2, true) == null) {
                source.sendError(Text.literal("这两个路径点之间已经有边了"));
                return 0;
            }
        }

        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已添加双向边 #" + id1 + " <-> #" + id2));
        return 1;
    }

    // ------------------------------------------------------------------
    // 出生点
    // ------------------------------------------------------------------

    private static int spawnSetByName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        Waypoint waypoint = WaypointManager.get().graph().byName(name);
        if (waypoint == null) {
            source.sendError(Text.literal("没有叫 " + name + " 的路径点"));
            return 0;
        }
        return applySpawn(source, waypoint);
    }

    private static int spawnSetById(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int id = IntegerArgumentType.getInteger(context, "id");

        Waypoint waypoint = WaypointManager.get().graph().get(id);
        if (waypoint == null) {
            source.sendError(Text.literal("没有 #" + id + " 这个路径点"));
            return 0;
        }
        return applySpawn(source, waypoint);
    }

    private static int applySpawn(FabricClientCommandSource source, Waypoint waypoint) {
        if (waypoint.isSpawn()) {
            source.sendError(Text.literal("那已经是出生点节点了"));
            return 0;
        }
        if (!WaypointManager.get().setSpawn(waypoint.id())) {
            source.sendError(Text.literal("设置出生点失败"));
            return 0;
        }
        source.sendFeedback(Text.literal("当前出生点已设为 #" + waypoint.id() + " "
            + DimensionUtils.display(waypoint.dimension()) + " " + waypoint.coordString()));
        return 1;
    }

    private static int spawnDel(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (!WaypointManager.get().graph().hasSpawn()) {
            source.sendFeedback(Text.literal("当前没有设置出生点"));
            return 1;
        }
        WaypointManager.get().clearSpawn();
        source.sendFeedback(Text.literal("已取消出生点设置"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    private static int delete(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int id = IntegerArgumentType.getInteger(context, "id");

        WaypointGraph graph = WaypointManager.get().graph();

        if (id > 0) {
            Waypoint waypoint = graph.get(id);
            if (waypoint == null) {
                source.sendError(Text.literal("没有 #" + id + " 这个路径点"));
                return 0;
            }
            Waypoint spawn = graph.spawnWaypointTarget();
            if (spawn != null && spawn.id() == id) {
                source.sendError(Text.literal("#" + id + " 是当前出生点，不能删；先用 spawn_set_* 换一个"));
                return 0;
            }
            BlockPos endPlatform = DimensionUtils.endSpawnPos();
            if (waypoint.dimension().equals(DimensionUtils.END) && waypoint.pos().equals(endPlatform)) {
                source.sendError(Text.literal("#" + id + " 是末地初始平台，不能删"));
                return 0;
            }

            BlockPos pos = waypoint.pos();
            graph.removeWaypoint(id);
            WaypointManager.get().save();
            source.sendFeedback(Text.literal("已删除路径点 #" + id + " " + pos.toShortString()
                + " 以及和它相连的所有边"));
            return 1;
        }

        if (id == 0) {
            source.sendError(Text.literal("0 号是出生点节点，不能删"));
            return 0;
        }

        Edge edge = graph.edge(id);
        if (edge == null) {
            source.sendError(Text.literal("没有 #" + id + " 这条边"));
            return 0;
        }
        if (edge.from() == WaypointGraph.SPAWN_ID) {
            source.sendError(Text.literal("#" + id + " 是出生点自动生成的边，删了也会重建；"
                + "要取消出生点用 /pc w spawn_del"));
            return 0;
        }
        String describe = describeEdge(graph, edge);
        graph.removeEdge(id);
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除边 #" + id + " " + describe));
        return 1;
    }

    // ------------------------------------------------------------------
    // 优化
    // ------------------------------------------------------------------

    private static int optimize(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointGraph graph = WaypointManager.get().graph();

        int before = graph.normalCount();
        int merged = graph.optimize();
        WaypointManager.get().save();

        source.sendFeedback(Text.literal("已合并 " + merged + " 个中间路径点，路径点数量 "
            + before + " → " + graph.normalCount() + "；"
            + "所有路径点已按 x y z 递增重新编号，边已重新编号"));
        return 1;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointGraph graph = WaypointManager.get().graph();
        String here = DimensionUtils.current();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7所有路径点："));

        List<Waypoint> normal = graph.allWaypoints().stream()
            .filter(w -> !w.isSpawn())
            .toList();
        if (normal.isEmpty()) {
            source.sendFeedback(Text.literal("  §8（空）"));
        } else {
            int shown = 0;
            for (Waypoint waypoint : normal) {
                if (shown++ >= LIST_LIMIT) {
                    source.sendFeedback(Text.literal("  §8… 还有 " + (normal.size() - LIST_LIMIT) + " 个没有显示"));
                    break;
                }
                source.sendFeedback(waypointLine(waypoint, waypoint.dimension().equals(here)));
            }
        }

        source.sendFeedback(Text.literal("§7所有边："));
        List<WaypointGraph.EdgeEntry> edges = graph.allEdges();
        if (edges.isEmpty()) {
            source.sendFeedback(Text.literal("  §8（空）"));
        } else {
            int shown = 0;
            for (WaypointGraph.EdgeEntry entry : edges) {
                if (shown++ >= LIST_LIMIT) {
                    source.sendFeedback(Text.literal("  §8… 还有 " + (edges.size() - LIST_LIMIT) + " 条没有显示"));
                    break;
                }
                source.sendFeedback(edgeLine(graph, entry));
            }
        }

        source.sendFeedback(Text.literal("§7当前出生点："));
        Waypoint spawn = graph.spawnWaypointTarget();
        if (spawn == null) {
            source.sendFeedback(Text.literal("  §8（未设置）"));
        } else {
            source.sendFeedback(spawnLine(spawn));
        }
        return 1;
    }

    /** {@code {id} [设置名称] [删除] [主] [1 1 1] (name)} */
    private static Text waypointLine(Waypoint waypoint, boolean manageable) {
        MutableText line = Text.literal(" " + waypoint.id() + " ").formatted(Formatting.GRAY);

        if (!manageable) {
            line.append(Text.literal("[其他维度]").formatted(Formatting.DARK_GRAY));
        } else {
            line.append(actionButton(waypoint.hasName() ? "修改名称" : "设置名称",
                ROOT + "waypoint_name " + waypoint.id() + " ",
                "点击把命令填到聊天栏（" + ROOT + "waypoint_name <id> <name>）"));
            line.append(Text.literal(" "));
            line.append(actionButton("删除", ROOT + "del " + waypoint.id(),
                "点击把删除命令填到聊天栏"));
        }

        line.append(Text.literal(" "));
        line.append(dimensionTag(waypoint.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(waypoint.pos()));
        if (waypoint.hasName()) {
            line.append(Text.literal(" "));
            line.append(nameText(waypoint.name()));
        }
        return line;
    }

    /** {@code [当前] [主] [1 1 1] (name)} */
    private static Text spawnLine(Waypoint spawn) {
        MutableText line = Text.literal(" [当前]").formatted(Formatting.GREEN);
        line.append(Text.literal(" "));
        line.append(dimensionTag(spawn.dimension()));
        line.append(Text.literal(" "));
        line.append(clickableCoord(spawn.pos()));
        if (spawn.hasName()) {
            line.append(Text.literal(" "));
            line.append(nameText(spawn.name()));
        }
        return line;
    }

    /**
     * {@code {edge_id} [删除] [主] {point_id} (name) <-> [下] {point_id} (name)}
     *
     * <p>双向符号用绿色，单向符号用黄色。
     */
    private static Text edgeLine(WaypointGraph graph, WaypointGraph.EdgeEntry entry) {
        Edge edge = entry.edge();
        Waypoint from = graph.get(edge.from());
        Waypoint to = graph.get(edge.to());

        MutableText line = Text.literal(" " + entry.id() + " ").formatted(Formatting.GRAY);
        if (from == null || to == null) {
            line.append(Text.literal("§8（无效的边）"));
            return line;
        }

        line.append(actionButton("删除", ROOT + "del " + entry.id(),
            "点击把删除命令填到聊天栏"));
        line.append(Text.literal(" "));
        line.append(endpointText(from));
        line.append(Text.literal(" "));
        line.append(Text.literal(edge.bi() ? "<->" : "-->")
            .setStyle(Style.EMPTY.withColor(edge.bi() ? Formatting.GREEN : Formatting.YELLOW)));
        line.append(Text.literal(" "));
        line.append(endpointText(to));
        return line;
    }

    /** {@code [主] {point_id} (name)} */
    private static Text endpointText(Waypoint waypoint) {
        MutableText text = Text.literal("");
        text.append(dimensionTag(waypoint.dimension()));
        text.append(Text.literal(" " + waypoint.id()).formatted(Formatting.AQUA));
        if (waypoint.hasName()) {
            text.append(Text.literal(" "));
            text.append(nameText(waypoint.name()));
        }
        return text;
    }

    private static String describeEdge(WaypointGraph graph, Edge edge) {
        Waypoint from = graph.get(edge.from());
        Waypoint to = graph.get(edge.to());
        if (from == null || to == null) return "";
        return from.coordString() + (edge.bi() ? " <-> " : " -> ") + to.coordString();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 维度短标签。 */
    private static Text dimensionTag(String dimension) {
        String shortName = switch (dimension) {
            case DimensionUtils.NETHER -> "下";
            case DimensionUtils.END -> "末";
            default -> "主";
        };
        Formatting color = switch (dimension) {
            case DimensionUtils.NETHER -> Formatting.RED;
            case DimensionUtils.END -> Formatting.LIGHT_PURPLE;
            default -> Formatting.GREEN;
        };
        return Text.literal("[" + shortName + "]").formatted(color);
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

    /** 名字：(name)，点一下复制。 */
    private static Text nameText(String name) {
        return Text.literal("(" + name + ")")
            .setStyle(Style.EMPTY
                .withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent.CopyToClipboard(name))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击复制名称"))));
    }

    /** 会往聊天栏里补全命令的按钮。 */
    private static Text actionButton(String label, String command, String hint) {
        return Text.literal("[" + label + "]")
            .setStyle(Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hint))));
    }

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = WaypointManager.get().toggleShow();
        context.getSource().sendFeedback(Text.literal("路径点显示已"
            + (showing ? "§a开启" : "§c关闭")));
        return 1;
    }

    private static int toggleEdit(CommandContext<FabricClientCommandSource> context) {
        boolean editing = WaypointManager.get().toggleEditMode();
        context.getSource().sendFeedback(Text.literal("路径点编辑模式已"
            + (editing ? "§a开启" : "§c关闭")));
        if (editing) {
            context.getSource().sendFeedback(Text.literal(
                "§7此时执行 /pc move 会把起点、终点记成路径点，中间记成一条边（自动保存）"));
        }
        return 1;
    }

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context,
                                    String xName, String yName, String zName) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, xName),
            IntegerArgumentType.getInteger(context, yName),
            IntegerArgumentType.getInteger(context, zName));
    }

    /** 当前 world 下所有路径点的名字，给命令补全用。 */
    public static List<String> names() {
        return WaypointManager.get().graph().allWaypoints().stream()
            .filter(Waypoint::hasName)
            .map(Waypoint::name)
            .toList();
    }
}
