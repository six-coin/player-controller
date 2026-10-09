package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.StationCheckAction;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /pc station ...} —— 工作站：站位点、工作台、切石机、任务前物品暂存处、
 * 空潜影盒提供处、潜影盒摆放处、物资存储地、最终产物地。
 *
 * <pre>
 * /pc station show                          开关高亮（各部分颜色见 StationPart）
 * /pc station list                          列出所有部分的坐标
 * /pc station check                         检查工作站是否就绪，过了就写 station/check.json
 *
 * /pc station stand_point set_here          把站立点设在脚下（当前维度）
 * /pc station stand_point set &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * /pc station crafting_table set_target     工作台
 * /pc station stonecutter set_target        切石机
 * /pc station item_temp set_target          任务执行前的物品暂存处
 * /pc station shulker_box_provider set_target   空潜影盒提供处
 * /pc station &lt;crafting_table|stonecutter|item_temp|shulker_box_provider&gt; set &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * /pc station &lt;shulker_box_placement|item_storage|item_final&gt; add_target|add &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * /pc station &lt;...&gt; del_target|del &lt;x&gt; &lt;y&gt; &lt;z&gt; [dim] |list
 * </pre>
 *
 * <p>坐标都带维度（station.json 里也是）：set / add 用当前维度；del 想删别的维度的可以补一个 [dim]。
 * 所有 x y z 都支持一次 Tab 补齐成准星目标。设置 / 添加时如果站立点已经设过，
 * 就会要求新坐标和站立点同维度、而且落在它的触及范围内。
 */
public final class StationCommand {

    private static final String ROOT = "/pc station ";

    /** check 要求至少这么多个潜影盒摆放处。 */
    private static final int REQUIRED_PLACEMENTS = 10;

    private StationCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder = ClientCommandManager.literal("station")
            .executes(StationCommand::status)
            .then(ClientCommandManager.literal("show").executes(StationCommand::toggleShow))
            .then(ClientCommandManager.literal("list").executes(StationCommand::list))
            .then(ClientCommandManager.literal("check").executes(StationCommand::check));

        for (StationPart part : StationPart.values()) {
            builder.then(part.isList() ? listPart(part) : singlePart(part));
        }
        return builder;
    }

    // ------------------------------------------------------------------
    // 状态 / 高亮 / 列表
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        StationManager manager = StationManager.get();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7工作站（world_" + manager.loadedWorld()
            + "，当前维度 " + DimensionUtils.display(DimensionUtils.current()) + "）："
            + manager.totalCount() + " 个坐标，高亮 " + (manager.isShowing() ? "§a开" : "§c关")));
        for (StationPart part : StationPart.values()) {
            int count = manager.positions(part).size();
            source.sendFeedback(Text.literal("  " + part.display() + "：" + count + " 个"
                + (count == 0 ? "§c（无）" : "")));
        }
        source.sendFeedback(Text.literal("  文件: " + manager.currentFile()));
        return 1;
    }

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = StationManager.get().toggleShow();
        context.getSource().sendFeedback(Text.literal("工作站高亮已"
            + (showing ? "§a开启" : "§c关闭") + "§r（各部分的颜色见 /pc station list 的行首标签）"));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        StationManager manager = StationManager.get();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7工作站（world_" + manager.loadedWorld()
            + "，共 " + manager.totalCount() + " 个坐标）："));

        // 单点部分在前面
        for (StationPart part : StationPart.values()) {
            if (part.isList()) continue;
            StationPos pos = manager.single(part);
            MutableText line = Text.literal(" ").append(colorTag(part)).append(Text.literal(" "));
            line.append(pos == null ? Text.literal("§c未设置") : line(part, pos));
            source.sendFeedback(line);
        }

        for (StationPart part : StationPart.values()) {
            if (!part.isList()) continue;
            List<StationPos> positions = manager.list(part);
            source.sendFeedback(Text.literal(" ").append(colorTag(part))
                .append(Text.literal("（" + positions.size() + " 个）：")));
            if (positions.isEmpty()) {
                source.sendFeedback(Text.literal("    §8（空）"));
                continue;
            }
            for (StationPos pos : positions) {
                source.sendFeedback(Text.literal("  ").append(line(part, pos)));
            }
        }
        return 1;
    }

    private static int listPart(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();
        List<StationPos> positions = StationManager.get().list(part);

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7" + part.display() + "（" + positions.size() + " 个）："));
        if (positions.isEmpty()) {
            source.sendFeedback(Text.literal("  §8（空）"));
            return 1;
        }
        for (StationPos pos : positions) {
            source.sendFeedback(Text.literal("  ").append(line(part, pos)));
        }
        return 1;
    }

    /** 一行的显示：{@code #id [主] [x y z]} + （列表才有）删除按钮。 */
    private static Text line(StationPart part, StationPos stationPos) {
        MutableText text = Text.literal("#" + stationPos.id() + " ").formatted(Formatting.DARK_GRAY);
        text.append(dimensionTag(stationPos.dimension()));
        text.append(Text.literal(" "));
        text.append(Text.literal("[" + stationPos.coordString() + "]")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(stationPos.coordString()))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(
                    "点击复制坐标（" + stationPos.describe() + "）")))));

        if (part.isList()) {
            text.append(Text.literal(" "));
            text.append(Text.literal("[删除]").setStyle(Style.EMPTY
                .withColor(Formatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(ROOT + part.id() + " del "
                    + stationPos.coordString() + " " + stationPos.dimension()))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(
                    "点击把删除命令填到聊天栏：" + ROOT + part.id() + " del "
                        + stationPos.coordString() + " " + stationPos.dimension())))));
        }
        return text;
    }

    private static MutableText colorTag(StationPart part) {
        return Text.literal("[" + part.display() + "]")
            .setStyle(Style.EMPTY.withColor(part.color() & 0xFFFFFF));
    }

    private static MutableText dimensionTag(String dimension) {
        String shortName = switch (dimension) {
            case DimensionUtils.NETHER -> "下";
            case DimensionUtils.END -> "末";
            case DimensionUtils.OVERWORLD -> "主";
            default -> dimension;
        };
        Formatting color = switch (dimension) {
            case DimensionUtils.NETHER -> Formatting.RED;
            case DimensionUtils.END -> Formatting.LIGHT_PURPLE;
            case DimensionUtils.OVERWORLD -> Formatting.GREEN;
            default -> Formatting.GRAY;
        };
        return Text.literal("[" + shortName + "]").formatted(color);
    }

    // ------------------------------------------------------------------
    // 单点部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> singlePart(StationPart part) {
        LiteralArgumentBuilder<FabricClientCommandSource> builder = ClientCommandManager.literal(part.id());

        if (part == StationPart.STAND_POINT) {
            builder.then(ClientCommandManager.literal("set_here")
                .executes(context -> setHere(context, part)));
        } else {
            builder.then(ClientCommandManager.literal("set_target")
                .executes(context -> setTarget(context, part)));
        }

        builder.then(ClientCommandManager.literal("set")
            .then(coords(context -> setCoords(context, part))));
        return builder;
    }

    private static int setHere(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            source.sendError(Text.literal("没有玩家"));
            return 0;
        }

        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        String dimension = DimensionUtils.current();

        // 站立点要能覆盖所有已经设过的坐标（同维度 + 够得着）
        List<StationPos> tooFar = new ArrayList<>();
        List<StationPos> otherDimension = new ArrayList<>();
        for (StationPos pos : StationManager.get().allPositions()) {
            if (pos.dimension().equals(dimension) && pos.pos().equals(here)) continue;
            if (!pos.dimension().equals(dimension)) {
                otherDimension.add(pos);
            } else if (!PlayerUtils.isWithinReachFrom(here, pos.pos())) {
                tooFar.add(pos);
            }
        }

        if (!otherDimension.isEmpty()) {
            source.sendError(Text.literal("有 " + otherDimension.size() + " 个工作站方块不在"
                + DimensionUtils.display(dimension) + "："));
            for (StationPos pos : otherDimension) {
                source.sendError(Text.literal("  · " + pos.describe()));
            }
            return 0;
        }
        if (!tooFar.isEmpty()) {
            source.sendError(Text.literal("有 " + tooFar.size() + " 个工作站方块不在这个位置的触及范围内"
                + "（触及距离 " + format(PlayerUtils.reach()) + "）："));
            for (StationPos pos : tooFar) {
                source.sendError(Text.literal("  · " + pos.coordString() + "  距离 "
                    + format(PlayerUtils.eyeDistanceFrom(here, pos.pos()))));
            }
            return 0;
        }

        boolean wasSet = StationManager.get().single(part) != null;
        StationManager.get().setSingle(part, dimension, here);
        source.sendFeedback(Text.literal((wasSet ? "已更新 " : "已设置 ") + part.display()
            + " = " + DimensionUtils.display(dimension) + " " + here.toShortString()
            + "（其它工作站坐标都够得着）"));
        return 1;
    }

    private static int setTarget(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return set(context.getSource(), part, pos);
    }

    private static int setCoords(CommandContext<FabricClientCommandSource> context, StationPart part) {
        return set(context.getSource(), part, readPos(context));
    }

    private static int set(FabricClientCommandSource source, StationPart part, BlockPos pos) {
        String dimension = DimensionUtils.current();
        if (!withinStandReach(source, dimension, pos)) return 0;

        StationManager.get().setSingle(part, dimension, pos);
        source.sendFeedback(Text.literal("已设置 " + part.display() + " = "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()));
        hintBlockType(source, part, pos);
        return 1;
    }

    // ------------------------------------------------------------------
    // 列表部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> listPart(StationPart part) {
        return ClientCommandManager.literal(part.id())
            .then(ClientCommandManager.literal("add_target")
                .executes(context -> addTarget(context, part)))
            .then(ClientCommandManager.literal("add")
                .then(coords(context -> addCoords(context, part))))
            .then(ClientCommandManager.literal("del_target")
                .executes(context -> delTarget(context, part)))
            .then(ClientCommandManager.literal("del")
                .then(dimCoords(context -> delCoords(context, part))))
            .then(ClientCommandManager.literal("list")
                .executes(context -> listPart(context, part)));
    }

    private static int addTarget(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return add(context, part, DimensionUtils.current(), pos);
    }

    private static int addCoords(CommandContext<FabricClientCommandSource> context, StationPart part) {
        return add(context, part, DimensionUtils.current(), readPos(context));
    }

    private static int add(CommandContext<FabricClientCommandSource> context, StationPart part,
                           String dimension, BlockPos pos) {
        FabricClientCommandSource source = context.getSource();
        if (!withinStandReach(source, dimension, pos)) return 0;

        if (!StationManager.get().add(part, dimension, pos)) {
            source.sendFeedback(Text.literal(part.display() + " 里已经有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString() + " 了"));
            return 1;
        }

        source.sendFeedback(Text.literal("已加入 " + part.display() + " "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + StationManager.get().list(part).size() + " 个）"));
        hintBlockType(source, part, pos);
        return 1;
    }

    private static int delTarget(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return remove(context, part, DimensionUtils.current(), pos);
    }

    private static int delCoords(CommandContext<FabricClientCommandSource> context, StationPart part) {
        return remove(context, part, readDimension(context), readPos(context));
    }

    private static int remove(CommandContext<FabricClientCommandSource> context, StationPart part,
                              String dimension, BlockPos pos) {
        FabricClientCommandSource source = context.getSource();

        if (!StationManager.get().remove(part, dimension, pos)) {
            source.sendFeedback(Text.literal(part.display() + " 里没有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString()));
            return 1;
        }
        source.sendFeedback(Text.literal("已从 " + part.display() + " 删掉 "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + StationManager.get().list(part).size() + " 个）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // check
    // ------------------------------------------------------------------

    private static int check(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        StationManager manager = StationManager.get();
        String dimension = DimensionUtils.current();

        // 必须站在站立点上（同一个维度、同一个坐标）
        StationPos stand = manager.single(StationPart.STAND_POINT);
        if (stand == null) {
            source.sendError(Text.literal("还没有设置站立点：站到工作站中间，然后 /pc station stand_point set_here"));
            return 0;
        }
        if (!stand.dimension().equals(dimension)) {
            source.sendError(Text.literal("站立点在 " + stand.describe() + "，你现在在 "
                + DimensionUtils.display(dimension) + "，先过去再检查"));
            return 0;
        }
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null || !here.equals(stand.pos())) {
            source.sendError(Text.literal("必须站在站立点 " + stand.coordString() + " 才能开始检查（你现在在 "
                + (here == null ? "?" : here.toShortString()) + "）"));
            return 0;
        }

        List<String> problems = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        WaypointGraph graph = WaypointManager.get().graph();

        // 站立点本身必须是路径点
        Waypoint standNode = graph.at(dimension, stand.pos());
        if (standNode == null) {
            problems.add("站立点 " + stand.coordString()
                + " 本身还不是路径点（站在这里用 /pc w waypoint_add_here 加一个）");
        }

        // 所有提及的方块都要和站立点同维度、且在其触及范围内
        for (StationPart part : StationPart.values()) {
            for (StationPos pos : manager.positions(part)) {
                if (!pos.dimension().equals(stand.dimension())) {
                    problems.add(part.display() + " " + pos.describe() + " 和站立点不在一个维度");
                    continue;
                }
                if (!PlayerUtils.isWithinReachFrom(stand.pos(), pos.pos())) {
                    problems.add(part.display() + " " + pos.coordString() + " 不在站立点的触及范围内（距离 "
                        + format(PlayerUtils.eyeDistanceFrom(stand.pos(), pos.pos())) + " > "
                        + format(PlayerUtils.reach()) + "）");
                }
            }
        }

        // 任务前物品暂存处：一处、大箱子
        StationPos temp = manager.single(StationPart.ITEM_TEMP);
        if (temp == null) {
            problems.add("还没有设置任务前物品暂存处（item_temp）");
        } else {
            BlockState state = mc.world.getBlockState(temp.pos());
            if (!(state.getBlock() instanceof ChestBlock)) {
                problems.add("任务前物品暂存处 " + temp.coordString() + " 不是箱子（现在是 "
                    + ContainerTypes.idOf(state) + "）");
            } else if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
                problems.add("任务前物品暂存处 " + temp.coordString() + " 是单格箱子，必须是大箱子");
            }
        }

        // 空潜影盒提供处：一处、木桶
        StationPos provider = manager.single(StationPart.SHULKER_BOX_PROVIDER);
        if (provider == null) {
            problems.add("还没有设置空潜影盒提供处（shulker_box_provider）");
        } else if (!(mc.world.getBlockState(provider.pos()).getBlock() instanceof BarrelBlock)) {
            problems.add("空潜影盒提供处 " + provider.coordString() + " 不是木桶（现在是 "
                + ContainerTypes.idOf(mc.world.getBlockState(provider.pos())) + "）");
        }

        // 工作台
        StationPos table = manager.single(StationPart.CRAFTING_TABLE);
        if (table == null) {
            problems.add("还没有设置工作台（crafting_table）");
        } else if (!(mc.world.getBlockState(table.pos()).getBlock() instanceof CraftingTableBlock)) {
            problems.add("工作台 " + table.coordString() + " 那里不是工作台方块（现在是 "
                + ContainerTypes.idOf(mc.world.getBlockState(table.pos())) + "）");
        }

        // 切石机：设了就顺便看一眼（需求里的清单没有它，所以只提示不报错）
        StationPos cutter = manager.single(StationPart.STONECUTTER);
        if (cutter != null && !(mc.world.getBlockState(cutter.pos()).getBlock() instanceof StonecutterBlock)) {
            hints.add("切石机 " + cutter.coordString() + " 那里不是切石机方块（现在是 "
                + ContainerTypes.idOf(mc.world.getBlockState(cutter.pos())) + "）");
        }

        // 潜影盒摆放处：数量、空气、上方空气、本身是路径点、从站立点走得到
        List<StationPos> placements = manager.list(StationPart.SHULKER_BOX_PLACEMENT);
        if (placements.size() < REQUIRED_PLACEMENTS) {
            problems.add("潜影盒摆放处只有 " + placements.size() + " 个，至少要 " + REQUIRED_PLACEMENTS + " 个");
        }
        for (StationPos stationPos : placements) {
            BlockPos pos = stationPos.pos();
            BlockState state = mc.world.getBlockState(pos);
            // 本身是空气、上面是空气、下面不是空气（潜影盒要摆在这一格、站在上面那一格去开）
            if (!state.isAir()) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 本身不是空气（现在是 "
                    + ContainerTypes.idOf(state) + "）");
            }
            if (!mc.world.getBlockState(pos.up()).isAir()) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 上面不是空气（那里应该是能站人的地方）");
            }
            if (mc.world.getBlockState(pos.down()).isAir()) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 下面一格是空气（潜影盒没地方放）");
            }

            // 路径点要在上面那一格（站上去开潜影盒），本身不能是路径点
            if (graph.at(stationPos.dimension(), pos) != null) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 本身不该是路径点（路径点应该在它上面那一格）");
            }
            Waypoint above = graph.at(stationPos.dimension(), pos.up());
            if (above == null) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 上面那一格 "
                    + pos.up().toShortString() + " 还不是路径点");
            } else if (standNode != null && graph.shortestPath(standNode.id(), above.id()) == null) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 上面那一格从站立点走不到");
            }
        }

        // 物资存储地 / 最终产物地：都要有、都要是容器（容量和内容在动作里数）
        checkContainerList(mc, manager, StationPart.ITEM_STORAGE, problems);
        checkContainerList(mc, manager, StationPart.ITEM_FINAL, problems);

        for (String hint : hints) source.sendFeedback(Text.literal("§e提示：" + hint));

        if (!problems.isEmpty()) {
            source.sendError(Text.literal("工作站检查没通过（" + problems.size() + " 条）："));
            for (String problem : problems) source.sendError(Text.literal("  · " + problem));
            return 0;
        }

        ActionManager.get().submit(new StationCheckAction());
        source.sendFeedback(Text.literal("方块都对了，开始逐个打开容器数内容"
            + "（物品暂存处、空潜影盒提供处、存储区、产物区；不显示界面，要几秒）"));
        return 1;
    }

    private static void checkContainerList(MinecraftClient mc, StationManager manager,
                                           StationPart part, List<String> problems) {
        List<StationPos> positions = manager.list(part);
        if (positions.isEmpty()) {
            problems.add("还没有设置" + part.display() + "（" + part.id() + "）");
            return;
        }
        for (StationPos stationPos : positions) {
            BlockPos pos = stationPos.pos();
            if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
                problems.add(part.display() + " " + stationPos.coordString() + " 不是容器（现在是 "
                    + ContainerTypes.idOf(mc.world.getBlockState(pos)) + "）");
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * x y z 三个参数，补全和别处一样：一次 Tab 补齐成准星指着的方块。
     *
     * <p>注意命令必须挂在**最里面**那个参数（z）上：brigadier 只有在「读到输入结尾、而且当前节点有命令」
     * 的时候才认这条命令，挂在 x 上的话 {@code add 1 2 3} 走到 z 就没有命令了，会报 Unknown command。
     */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> coords(
            Command<FabricClientCommandSource> command) {
        return ClientCommandManager.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(command)));
    }

    /** del 用的 x y z：z 上挂「不带 dim」的版本，可选的 [dim] 上挂同一个命令。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> dimCoords(
            Command<FabricClientCommandSource> command) {
        return ClientCommandManager.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(command)
                    .then(ClientCommandManager.argument("dim", StringArgumentType.greedyString())
                        .suggests(StationCommand::suggestDimensions)
                        .executes(command))));
    }

    /** [dim] 的补全：原版三个维度 + 工作站里已经出现过的维度。 */
    private static CompletableFuture<Suggestions> suggestDimensions(
            CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        Set<String> dimensions = new TreeSet<>();
        dimensions.add(DimensionUtils.OVERWORLD);
        dimensions.add(DimensionUtils.NETHER);
        dimensions.add(DimensionUtils.END);
        for (StationPos pos : StationManager.get().allPositions()) {
            dimensions.add(pos.dimension());
        }
        return CommandSource.suggestMatching(dimensions, builder);
    }

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
    }

    /** 可选 [dim]：没写就是当前维度。 */
    private static String readDimension(CommandContext<FabricClientCommandSource> context) {
        try {
            String dim = StringArgumentType.getString(context, "dim");
            return dim == null || dim.isBlank() ? DimensionUtils.current() : dim.trim();
        } catch (IllegalArgumentException e) {
            return DimensionUtils.current();
        }
    }

    /** 站立点已经设了的话，新坐标必须和它同维度、而且落在它的触及范围内。 */
    private static boolean withinStandReach(FabricClientCommandSource source, String dimension, BlockPos pos) {
        StationPos stand = StationManager.get().single(StationPart.STAND_POINT);
        if (stand == null) return true;

        if (!stand.dimension().equals(dimension)) {
            source.sendError(Text.literal(DimensionUtils.display(dimension) + " " + pos.toShortString()
                + " 和站立点（" + stand.describe() + "）不在一个维度，"
                + "工作站的所有方块都要在站立点的触及范围内"));
            return false;
        }
        if (!PlayerUtils.isWithinReachFrom(stand.pos(), pos)) {
            source.sendError(Text.literal(pos.toShortString() + " 离站立点 " + stand.coordString()
                + " 太远（距离 " + format(PlayerUtils.eyeDistanceFrom(stand.pos(), pos))
                + " > " + format(PlayerUtils.reach()) + "），工作站的所有方块都要在站立点的触及范围内"));
            return false;
        }
        return true;
    }

    /** 设置的时候顺便提醒方块类型对不对（真正的硬性检查在 check 里）。 */
    private static void hintBlockType(FabricClientCommandSource source, StationPart part, BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        BlockState state = mc.world.getBlockState(pos);
        String expected = null;

        if (part == StationPart.CRAFTING_TABLE && !(state.getBlock() instanceof CraftingTableBlock)) {
            expected = "工作台方块";
        } else if (part == StationPart.STONECUTTER && !(state.getBlock() instanceof StonecutterBlock)) {
            expected = "切石机方块";
        } else if (part == StationPart.ITEM_TEMP) {
            if (!(state.getBlock() instanceof ChestBlock)) {
                expected = "大箱子";
            } else if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
                expected = "大箱子（两个连在一起的箱子）";
            }
        } else if (part == StationPart.SHULKER_BOX_PROVIDER && !(state.getBlock() instanceof BarrelBlock)) {
            expected = "木桶";
        } else if (part == StationPart.SHULKER_BOX_PLACEMENT && !state.isAir()) {
            expected = "空气";
        } else if ((part == StationPart.ITEM_STORAGE || part == StationPart.ITEM_FINAL)
            && !(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            expected = "容器";
        }

        if (expected != null) {
            source.sendFeedback(Text.literal("§e提示：这里现在是 " + ContainerTypes.idOf(state)
                + "，check 会要求是" + expected));
        }
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }
}
