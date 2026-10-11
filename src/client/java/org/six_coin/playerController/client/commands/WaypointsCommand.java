package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc waypoints ...} —— 路径点、边、出生点，全部走这一套命令。
 *
 * <pre>
 * /pc waypoints show
 * /pc waypoints edit
 * /pc waypoints list
 * /pc waypoints optimize                          两条子命令的使用说明
 * /pc waypoints optimize default                  合并中间点，并重新编号
 * /pc waypoints optimize del_unreachable          先备份，再删掉走不到的点和边
 * /pc waypoints add_edge &lt;id1&gt; &lt;id2&gt;
 * /pc waypoints add_waypoint here
 * /pc waypoints add_waypoint target
 * /pc waypoints add_waypoint position &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; [name]
 * /pc waypoints name_waypoint here [name]
 * /pc waypoints name_waypoint id &lt;id&gt; [name]
 * /pc waypoints name_waypoint target [name]
 * /pc waypoints del_edge &lt;id&gt;                     id 必须是负数（边都是负数编号）
 * /pc waypoints del_waypoint id &lt;id&gt;
 * /pc waypoints del_waypoint here
 * /pc waypoints del_waypoint target
 * /pc waypoints del_waypoint position &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt;
 * /pc waypoints del_waypoint name &lt;name&gt;
 * /pc waypoints set_spawn id &lt;id&gt;
 * /pc waypoints set_spawn here
 * /pc waypoints set_spawn target
 * /pc waypoints set_spawn position &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt;
 * /pc waypoints set_spawn name &lt;name&gt;
 * /pc waypoints del_spawn
 * </pre>
 *
 * <p>几个统一的地方：
 * <ul>
 *   <li>{@code here} = 玩家脚下这一格；{@code target} = 准星指着的方块；
 *       {@code position <x> <y> <z> <dim>} = 指定维度指定坐标；{@code name <name>} = 按名字找；</li>
 *   <li>{@code position} 的维度是必填的（写 {@code minecraft:overworld} 这种，补全会给）；</li>
 *   <li>{@code name_waypoint} 后面不写名字就是「把名字删掉」——
 *       原来的 {@code waypoint_name_del} / {@code waypoint_name_del_here} 就用这种写法替代。</li>
 * </ul>
 */
public final class WaypointsCommand {

    /** 点击事件里补全命令用的前缀。 */
    private static final String ROOT = "/pc waypoints ";

    /** list 每段最多列多少行。 */
    private static final int LIST_LIMIT = 80;

    private WaypointsCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("waypoints")
            .executes(WaypointsCommand::status)
            .then(literal("show")
                .executes(WaypointsCommand::toggleShow))
            .then(literal("edit")
                .executes(WaypointsCommand::toggleEdit))
            .then(literal("list")
                .executes(WaypointsCommand::list))
            .then(optimize())
            .then(addEdge())
            .then(addWaypoint())
            .then(nameWaypoint())
            .then(delEdge())
            .then(picks("del_waypoint", WaypointsCommand::delWaypoint,
                "id", "here", "target", "position", "name")
                .executes(context -> usage(context.getSource(),
                    "del_waypoint id <id>",
                    "del_waypoint here",
                    "del_waypoint target",
                    "del_waypoint position <x> <y> <z> <dim>",
                    "del_waypoint name <name>")))
            .then(picks("set_spawn", WaypointsCommand::setSpawn,
                "id", "here", "target", "position", "name")
                .executes(context -> usage(context.getSource(),
                    "set_spawn id <id>",
                    "set_spawn here",
                    "set_spawn target",
                    "set_spawn position <x> <y> <z> <dim>",
                    "set_spawn name <name>")))
            .then(literal("del_spawn")
                .executes(WaypointsCommand::delSpawn));
    }

    /** 少写参数时打一行用法（比 brigadier 那句英文报错友好）。 */
    private static int usage(FabricClientCommandSource source, String... lines) {
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7用法：" + ROOT + lines[0]));
        for (int i = 1; i < lines.length; i++) {
            source.sendFeedback(Text.literal("§7  " + ROOT + lines[i]));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 选择器：把「是哪个路径点」指出来
    // ------------------------------------------------------------------

    /** 一个选择器叶子要做的事：{@code kind} 是 {@code id / here / target / position / name}。 */
    @FunctionalInterface
    private interface Pick {
        int run(FabricClientCommandSource source, CommandContext<FabricClientCommandSource> context, String kind);
    }

    /** 把若干个选择器挂到同一层下面。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> picks(String name, Pick runner,
                                                                          String... kinds) {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal(name);
        for (String kind : kinds) node.then(pick(kind, runner));
        return node;
    }

    /** 一个选择器叶子。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> pick(String kind, Pick runner) {
        return switch (kind) {
            case "here" -> literal("here")
                .executes(context -> runner.run(context.getSource(), context, "here"));
            case "target" -> literal("target")
                .executes(context -> runner.run(context.getSource(), context, "target"));
            case "id" -> literal("id")
                .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                    .executes(context -> runner.run(context.getSource(), context, "id")));
            case "position" -> positionNode(
                context -> runner.run(context.getSource(), context, "position"));
            case "name" -> literal("name")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests((context, builder) -> CommandSource.suggestMatching(names(), builder))
                    .executes(context -> runner.run(context.getSource(), context, "name")));
            default -> throw new IllegalArgumentException("未知选择器: " + kind);
        };
    }

    /** {@code position <x> <y> <z> <dim>}（维度必填；补全会给维度建议）。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> positionNode(
        com.mojang.brigadier.Command<FabricClientCommandSource> runner) {

        // 注意顺序：brigadier 的 then(builder) 会立刻 build 出节点，所以要从里往外挂
        RequiredArgumentBuilder<FabricClientCommandSource, Identifier> dim =
            ClientCommandManager.argument("dim", IdentifierArgumentType.identifier())
                .suggests(DimensionSuggestions::suggest)
                .executes(runner);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z =
            ClientCommandManager.argument("z", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        z.then(dim);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y =
            ClientCommandManager.argument("y", IntegerArgumentType.integer()).suggests(LookSuggestions::y).then(z);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x =
            ClientCommandManager.argument("x", IntegerArgumentType.integer()).suggests(LookSuggestions::x).then(y);

        return literal("position").then(x);
    }

    /**
     * 按选择器找一个路径点，找不到就报错并返回 null。
     *
     * <p>所有 {@code del_waypoint / set_spawn / name_waypoint} 共用这一套解析。
     */
    @Nullable
    private static Waypoint resolve(FabricClientCommandSource source,
                                    CommandContext<FabricClientCommandSource> context, String kind) {
        WaypointGraph graph = WaypointManager.get().graph();
        String dimension = DimensionUtils.current();

        switch (kind) {
            case "id" -> {
                int id = IntegerArgumentType.getInteger(context, "id");
                Waypoint waypoint = graph.get(id);
                if (waypoint == null) {
                    source.sendError(Text.literal("没有 #" + id + " 这个路径点"));
                }
                return waypoint;
            }
            case "here" -> {
                BlockPos pos = PlayerUtils.currentBlockPos();
                if (pos == null) {
                    source.sendError(Text.literal("拿不到玩家位置"));
                    return null;
                }
                Waypoint waypoint = graph.at(dimension, pos);
                if (waypoint == null) {
                    source.sendError(Text.literal("你现在站的 " + DimensionUtils.display(dimension) + " "
                        + pos.toShortString() + " 不是路径点"));
                }
                return waypoint;
            }
            case "target" -> {
                BlockPos pos = PlayerUtils.lookedAtBlock();
                if (pos == null) {
                    source.sendError(Text.literal("你没有看向任何方块"));
                    return null;
                }
                Waypoint waypoint = graph.at(dimension, pos);
                if (waypoint == null) {
                    source.sendError(Text.literal("你看向的 " + DimensionUtils.display(dimension) + " "
                        + pos.toShortString() + " 不是路径点"));
                }
                return waypoint;
            }
            case "position" -> {
                BlockPos pos = readPos(context, "x", "y", "z");
                String at = readDimension(context);
                Waypoint waypoint = graph.at(at, pos);
                if (waypoint == null) {
                    source.sendError(Text.literal(DimensionUtils.display(at) + " 的 "
                        + pos.toShortString() + " 不是路径点"));
                }
                return waypoint;
            }
            case "name" -> {
                String name = StringArgumentType.getString(context, "name");
                Waypoint waypoint = graph.byName(name);
                if (waypoint == null) {
                    source.sendError(Text.literal("没有叫 " + name + " 的路径点"));
                }
                return waypoint;
            }
            default -> {
                source.sendError(Text.literal("未知的选择器: " + kind));
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 增：路径点 / 边
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> addWaypoint() {
        return literal("add_waypoint")
            .executes(context -> usage(context.getSource(),
                "add_waypoint here",
                "add_waypoint target",
                "add_waypoint position <x> <y> <z> <dim> [name]"))
            .then(literal("here")
                .executes(WaypointsCommand::addWaypointHere))
            .then(literal("target")
                .executes(WaypointsCommand::addWaypointTarget))
            .then(literal("position").then(
                ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::x)
                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::y)
                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                            .suggests(LookSuggestions::z)
                            .then(ClientCommandManager.argument("dim", IdentifierArgumentType.identifier())
                                .suggests(DimensionSuggestions::suggest)
                                .executes(context -> addWaypointPosition(context, null))
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                    .executes(context -> addWaypointPosition(context,
                                        StringArgumentType.getString(context, "name")))))))));
    }

    /** {@code add_waypoint here}：在脚下建一个路径点（不设名字）。 */
    private static int addWaypointHere(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.currentBlockPos();
        if (pos == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        return addWaypointAt(source, DimensionUtils.current(), pos, null);
    }

    /** {@code add_waypoint target}：在准星指着的方块建一个路径点（不设名字）。 */
    private static int addWaypointTarget(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return addWaypointAt(source, DimensionUtils.current(), pos, null);
    }

    /** {@code add_waypoint position <x> <y> <z> <dim> [name]}。 */
    private static int addWaypointPosition(CommandContext<FabricClientCommandSource> context,
                                           @Nullable String name) {
        FabricClientCommandSource source = context.getSource();
        return addWaypointAt(source, readDimension(context), readPos(context, "x", "y", "z"), name);
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

    private static LiteralArgumentBuilder<FabricClientCommandSource> addEdge() {
        return literal("add_edge")
            .executes(context -> usage(context.getSource(), "add_edge <id1> <id2>"))
            .then(ClientCommandManager.argument("id1", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("id2", IntegerArgumentType.integer())
                    .executes(WaypointsCommand::edgeAdd)));
    }

    private static int edgeAdd(CommandContext<FabricClientCommandSource> context) {
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
    // 命名
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> nameWaypoint() {
        LiteralArgumentBuilder<FabricClientCommandSource> node = literal("name_waypoint")
            .executes(context -> usage(context.getSource(),
                "name_waypoint here [name]",
                "name_waypoint target [name]",
                "name_waypoint id <id> [name]",
                "（名字不写就是删掉名字）"));

        node.then(literal("here")
            .executes(context -> nameWaypointAt(context, "here", null))
            .then(optionalName(context -> nameWaypointAt(context, "here",
                StringArgumentType.getString(context, "name")))));

        node.then(literal("target")
            .executes(context -> nameWaypointAt(context, "target", null))
            .then(optionalName(context -> nameWaypointAt(context, "target",
                StringArgumentType.getString(context, "name")))));

        node.then(literal("id")
            .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                .executes(context -> nameWaypointAt(context, "id", null))
                .then(optionalName(context -> nameWaypointAt(context, "id",
                    StringArgumentType.getString(context, "name"))))));

        return node;
    }

    /** 可选的 {@code [name]} 参数。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, String> optionalName(
        com.mojang.brigadier.Command<FabricClientCommandSource> runner) {
        return ClientCommandManager.argument("name", StringArgumentType.word()).executes(runner);
    }

    /**
     * {@code name_waypoint here|target|id}：{@code name} 为 null 表示「后面没写名字」，也就是把名字删掉。
     */
    private static int nameWaypointAt(CommandContext<FabricClientCommandSource> context,
                                      String kind, @Nullable String name) {
        FabricClientCommandSource source = context.getSource();
        Waypoint waypoint = resolve(source, context, kind);
        if (waypoint == null) return 0;

        if (name == null) return deleteName(source, waypoint);

        if (!WaypointGraph.isValidName(name)) {
            source.sendError(Text.literal("名字只能用大小写字母、数字和下划线"));
            return 0;
        }
        if (waypoint.isSpawn()) {
            source.sendError(Text.literal("0 号是出生点节点，名字跟着出生点走，不能单独改"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        if (!graph.setName(waypoint.id(), name)) {
            source.sendError(Text.literal("名字 " + name + " 已经被别的路径点占用了"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已把 #" + waypoint.id() + " 命名为 " + name));
        return 1;
    }

    /** 清掉一个路径点的名字（出生点节点除外）。 */
    private static int deleteName(FabricClientCommandSource source, Waypoint waypoint) {
        int id = waypoint.id();

        if (waypoint.isSpawn()) {
            source.sendError(Text.literal("0 号是出生点节点，名字跟着出生点走，不能单独改"));
            return 0;
        }
        if (!waypoint.hasName()) {
            source.sendFeedback(Text.literal("#" + id + " 本来就没有名字"));
            return 1;
        }
        String oldName = waypoint.name();
        if (!WaypointManager.get().graph().setName(id, null)) {
            source.sendError(Text.literal("删除 #" + id + " 的名字失败"));
            return 0;
        }
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除 #" + id + " 的名字（" + oldName + "）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 删：路径点 / 边
    // ------------------------------------------------------------------

    /** {@code del_edge <id>}：只删边（边都是负数编号）。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> delEdge() {
        return literal("del_edge")
            .executes(context -> usage(context.getSource(), "del_edge <id>（id 是负数）"))
            .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                .executes(WaypointsCommand::delEdgeAt));
    }

    private static int delEdgeAt(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int id = IntegerArgumentType.getInteger(context, "id");

        if (id >= 0) {
            source.sendError(Text.literal("边的编号都是负数（-1、-2、…）；"
                + "要删路径点用 " + ROOT + "del_waypoint id <id>"));
            return 0;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Edge edge = graph.edge(id);
        if (edge == null) {
            source.sendError(Text.literal("没有 #" + id + " 这条边"));
            return 0;
        }
        if (edge.from() == WaypointGraph.SPAWN_ID) {
            source.sendError(Text.literal("#" + id + " 是出生点自动生成的边，删了也会重建；"
                + "要取消出生点用 " + ROOT + "del_spawn"));
            return 0;
        }

        String describe = describeEdge(graph, edge);
        graph.removeEdge(id);
        WaypointManager.get().save();
        source.sendFeedback(Text.literal("已删除边 #" + id + " " + describe));
        return 1;
    }

    /** {@code del_waypoint <选择器>}：删掉选中的路径点。 */
    private static int delWaypoint(FabricClientCommandSource source,
                                   CommandContext<FabricClientCommandSource> context, String kind) {
        Waypoint waypoint = resolve(source, context, kind);
        if (waypoint == null) return 0;
        return deleteWaypoint(source, waypoint);
    }

    /** 删除一个路径点，带各种保护。 */
    private static int deleteWaypoint(FabricClientCommandSource source, Waypoint waypoint) {
        WaypointGraph graph = WaypointManager.get().graph();
        int id = waypoint.id();

        if (waypoint.isSpawn()) {
            source.sendError(Text.literal("这里是出生点节点，不能删；要取消出生点用 " + ROOT + "del_spawn"));
            return 0;
        }

        Waypoint spawn = graph.spawnWaypointTarget();
        if (spawn != null && spawn.id() == id) {
            source.sendError(Text.literal("#" + id + " 是当前出生点，不能删；"
                + "先用 " + ROOT + "set_spawn <选择器> 换一个"));
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
        source.sendFeedback(Text.literal("已删除路径点 #" + id + " "
            + DimensionUtils.display(waypoint.dimension()) + " " + pos.toShortString()
            + " 以及和它相连的所有边"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 出生点
    // ------------------------------------------------------------------

    /** {@code set_spawn <选择器>}：把选中的路径点设为当前出生点。 */
    private static int setSpawn(FabricClientCommandSource source,
                                CommandContext<FabricClientCommandSource> context, String kind) {
        Waypoint waypoint = resolve(source, context, kind);
        if (waypoint == null) return 0;
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

    /** {@code del_spawn}：取消出生点设置（等于把出生点弄回「未设置」）。 */
    private static int delSpawn(CommandContext<FabricClientCommandSource> context) {
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
    // 优化
    // ------------------------------------------------------------------

    /** {@code optimize}：只打两条子命令的使用说明。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> optimize() {
        return literal("optimize")
            .executes(WaypointsCommand::optimizeUsage)
            .then(literal("default")
                .executes(WaypointsCommand::optimizeDefault))
            .then(literal("del_unreachable")
                .executes(WaypointsCommand::optimizeDelUnreachable));
    }

    private static int optimizeUsage(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7" + ROOT + "optimize 有两种："));
        source.sendFeedback(Text.literal("§7  " + ROOT + "optimize §fdefault§7"
            + " §8- §7合并中间点（没有名字、只有两条边、且在一条直线上的点），"
            + "再把路径点按坐标、边按两端重新编号"));
        source.sendFeedback(Text.literal("§7  " + ROOT + "optimize §fdel_unreachable§7"
            + " §8- §7先备份 waypoints.json，再删掉从你现在的位置出发走不到的路径点和边"));
        source.sendFeedback(Text.literal("§7    §7走不到 = 沿着已知边（含传送门边）走不过去；"
            + "出生点节点、当前出生点、末地初始平台不会被删"));
        return 1;
    }

    private static int optimizeDefault(CommandContext<FabricClientCommandSource> context) {
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

    /** {@code optimize del_unreachable}：先把 waypoints.json 备份，再把不可达的点和边全删掉。 */
    private static int optimizeDelUnreachable(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        List<Waypoint> starts = startWaypoints(source);
        if (starts == null) return 0;

        WaypointManager manager = WaypointManager.get();
        WaypointGraph graph = manager.graph();
        WaypointManager.Unreachable result = manager.findUnreachable(idsOf(starts));

        if (result.waypoints().isEmpty() && result.edges().isEmpty()) {
            source.sendFeedback(Text.literal("从 " + describeStarts(starts)
                + " 出发没有不可达的路径点和边，什么都没删（也没备份）"));
            reportSkipped(source, result.skipped());
            return 1;
        }

        // 1. 先备份
        Path backup = manager.backupFile();
        if (backup == null) {
            source.sendError(Text.literal("备份路径点文件失败（文件不存在或者写不进去），已中止，什么都没删"));
            return 0;
        }
        source.sendFeedback(Text.literal("已备份到 " + backup));

        // 2. 删点（挂在上面的边会一起删掉），再删剩下的不可达边
        int beforeNodes = graph.normalCount();
        int beforeEdges = graph.edgeCount();

        int removedNodes = 0;
        for (Waypoint waypoint : result.waypoints()) {
            ChatUtils.debug("删除不可达路径点 #" + waypoint.id() + " "
                + DimensionUtils.display(waypoint.dimension()) + " " + waypoint.coordString());
            if (graph.removeWaypoint(waypoint.id())) removedNodes++;
        }

        int removedEdges = 0;
        for (WaypointGraph.EdgeEntry entry : result.edges()) {
            ChatUtils.debug("删除不可达边 #" + entry.id() + " " + describeEdge(graph, entry.edge()));
            if (graph.removeEdge(entry.id())) removedEdges++;
        }
        manager.save();

        source.sendFeedback(Text.literal("已删除 " + removedNodes + " 个不可达路径点；路径点 "
            + beforeNodes + " → " + graph.normalCount() + "，边 " + beforeEdges + " → " + graph.edgeCount()
            + "（删点会连带删掉挂在上面的边，另外单独删了 " + removedEdges + " 条不可达边）"));
        reportSkipped(source, result.skipped());
        return 1;
    }

    /** 没删的受保护点提醒一下。 */
    private static void reportSkipped(FabricClientCommandSource source, List<Waypoint> skipped) {
        if (skipped.isEmpty()) return;

        List<String> ids = new ArrayList<>();
        for (Waypoint waypoint : skipped) ids.add("#" + waypoint.id());
        source.sendFeedback(Text.literal("有 " + skipped.size() + " 个不可达但受保护的路径点没删（"
            + String.join("、", ids) + "）：出生点节点、当前出生点、末地初始平台；"
            + "要删当前出生点的话先用 " + ROOT + "set_spawn <选择器> 换一个"));
    }

    /**
     * 玩家现在算站在哪：站在路径点上就是它；站在某条边中间就是这条边的两个端点
     * （两边都走得过去）；都不在就报错并返回 null。
     */
    @Nullable
    private static List<Waypoint> startWaypoints(FabricClientCommandSource source) {
        BlockPos pos = PlayerUtils.currentBlockPos();
        if (pos == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return null;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        String dimension = DimensionUtils.current();

        Waypoint here = graph.at(dimension, pos);
        if (here != null) return List.of(here);

        WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, pos);
        if (edge == null) {
            source.sendError(Text.literal("你现在所在的 " + pos.toShortString()
                + " 既不是路径点，也不在任何边上，算不出可达范围"));
            return null;
        }

        List<Waypoint> starts = new ArrayList<>();
        Waypoint from = graph.get(edge.edge().from());
        Waypoint to = graph.get(edge.edge().to());
        if (from != null) starts.add(from);
        if (to != null) starts.add(to);

        ChatUtils.debug("站在边 #" + edge.id() + " 上，可达范围按两端算");
        return starts;
    }

    private static List<Integer> idsOf(List<Waypoint> waypoints) {
        List<Integer> ids = new ArrayList<>();
        for (Waypoint waypoint : waypoints) ids.add(waypoint.id());
        return ids;
    }

    private static String describeStarts(List<Waypoint> starts) {
        if (starts.size() == 1) {
            Waypoint only = starts.get(0);
            return "#" + only.id() + " " + only.coordString();
        }
        return "边上的两端 #" + starts.get(0).id() + " / #" + starts.get(1).id();
    }

    // ------------------------------------------------------------------
    // 状态 / 开关
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
                "§7此时 /pc move <轴> 和 /pc move face 会把起点、终点记成路径点，中间记成一条边（自动保存）"));
            context.getSource().sendFeedback(Text.literal(
                "§7终点是下界传送门方块（下面垫着黑曜石）时，走过去被传送后还会记一条传送门边"));
            context.getSource().sendFeedback(Text.literal(
                "§7/pc move to 走的都是已知边，不会记录任何东西"));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        WaypointGraph graph = WaypointManager.get().graph();

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
                source.sendFeedback(waypointLine(waypoint));
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

    /** {@code {id} [删除] [设置名称|修改名称] [主] [1 1 1] (name)} */
    private static Text waypointLine(Waypoint waypoint) {
        MutableText line = Text.literal(" " + waypoint.id() + " ").formatted(Formatting.GRAY);

        line.append(actionButton("删除", ROOT + "del_waypoint id " + waypoint.id(),
            "点击把命令填到聊天栏（" + ROOT + "del_waypoint id <id>）"));
        line.append(Text.literal(" "));
        line.append(actionButton(waypoint.hasName() ? "修改名称" : "设置名称",
            ROOT + "name_waypoint id " + waypoint.id() + " ",
            "点击把命令填到聊天栏（" + ROOT + "name_waypoint id <id> <name>）；"
                + "名字留空直接回车就是删除名字"));

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

    /** {@code {id} [主] [1 1 1] (name)} —— 只有一个出生点，所以不用标「当前」 */
    private static Text spawnLine(Waypoint spawn) {
        MutableText line = Text.literal(" " + spawn.id() + " ").formatted(Formatting.GRAY);
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
     * {@code {edge_id} [删除] {from_id} <符号> {to_id} / [主] [0 0 0] (name) <符号> [下] [0 0 0] (name)}
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

        line.append(actionButton("删除", ROOT + "del_edge " + entry.id(),
            "点击把命令填到聊天栏（" + ROOT + "del_edge <id>）"));
        line.append(Text.literal(" "));

        // 前半段：两个路径点的编号
        line.append(Text.literal(String.valueOf(from.id())).formatted(Formatting.AQUA));
        line.append(Text.literal(" "));
        line.append(edgeSymbol(edge));
        line.append(Text.literal(" "));
        line.append(Text.literal(String.valueOf(to.id())).formatted(Formatting.AQUA));

        line.append(Text.literal(" / ").formatted(Formatting.DARK_GRAY));

        // 后半段：维度 + 坐标 + 名字
        line.append(endpointText(from));
        line.append(Text.literal(" "));
        line.append(edgeSymbol(edge));
        line.append(Text.literal(" "));
        line.append(endpointText(to));
        return line;
    }

    /** 双向 {@code <->} 绿色，单向 {@code -->} 黄色。 */
    private static MutableText edgeSymbol(Edge edge) {
        return Text.literal(edge.bi() ? "<->" : "-->")
            .setStyle(Style.EMPTY.withColor(edge.bi() ? Formatting.GREEN : Formatting.YELLOW));
    }

    /** {@code [主] [1 1 1] (name)} */
    private static Text endpointText(Waypoint waypoint) {
        MutableText text = Text.literal("");
        text.append(dimensionTag(waypoint.dimension()));
        text.append(Text.literal(" "));
        text.append(clickableCoord(waypoint.pos()));
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

    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return ClientCommandManager.literal(name);
    }

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context,
                                    String xName, String yName, String zName) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, xName),
            IntegerArgumentType.getInteger(context, yName),
            IntegerArgumentType.getInteger(context, zName));
    }

    /**
     * {@code position} 的 {@code <dim>} 参数（必填）。
     *
     * <p>用原版的 {@code IdentifierArgumentType} 而不是 {@code StringArgumentType.word()}：
     * word 只吃 {@code [a-zA-Z0-9_]}，会在 {@code minecraft:overworld} 的冒号那儿断掉。
     *
     * <p>注意 {@code IdentifierArgumentType.getIdentifier(...)} 只收服务端命令源，
     * 这里直接 {@code getArgument} 拿（补全用的是客户端命令源）。
     */
    private static String readDimension(CommandContext<FabricClientCommandSource> context) {
        Identifier dim = context.getArgument("dim", Identifier.class);
        return dim == null ? DimensionUtils.current() : dim.toString();
    }

    /** 当前 world 下所有路径点的名字，给命令补全用。 */
    public static List<String> names() {
        return WaypointManager.get().graph().allWaypoints().stream()
            .filter(Waypoint::hasName)
            .map(Waypoint::name)
            .toList();
    }
}
