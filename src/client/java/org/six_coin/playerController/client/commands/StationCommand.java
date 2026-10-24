package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Container;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.stock.station.StationCheckAction;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationPart;
import org.six_coin.playerController.client.stock.station.StationPos;
import org.six_coin.playerController.client.stock.station.StationPrecheck;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc stock station ...} —— 工作站：站位点、工作台、切石机、任务前物品暂存处、
 * 空潜影盒提供处、潜影盒摆放处、物资存储地、最终产物地。
 *
 * <pre>
 * /pc stock station show                          开关高亮（各部分颜色见 StationPart）
 * /pc stock station list                          列出所有部分的坐标
 * /pc stock station check                         检查工作站是否就绪，过了就写 stock/station/check.json
 *
 * /pc stock station stand_point set_here          把站立点设在脚下（当前维度）
 * /pc stock station stand_point set &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * /pc stock station crafting_table set_target     工作台
 * /pc stock station stonecutter set_target        切石机
 * /pc stock station item_temp set_target          任务执行前的物品暂存处
 * /pc stock station shulker_box_provider set_target   空潜影盒提供处
 * /pc stock station &lt;crafting_table|stonecutter|item_temp|shulker_box_provider&gt; set &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * /pc stock station &lt;shulker_box_placement|item_storage|item_final&gt; add_target|add &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * /pc stock station &lt;...&gt; del_target|del &lt;x&gt; &lt;y&gt; &lt;z&gt; [dim] |list
 * </pre>
 *
 * <p>坐标都带维度（station.json 里也是）：set / add 用当前维度；del 想删别的维度的可以补一个 [dim]。
 * 所有 x y z 都支持一次 Tab 补齐成准星目标。设置 / 添加时如果站立点已经设过，
 * 就会要求新坐标和站立点同维度、而且落在它的触及范围内。
 */
public final class StationCommand {

    private static final String ROOT = "/pc stock station ";

    private StationCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder = ClientCommands.literal("station")
            .executes(StationCommand::status)
            .then(ClientCommands.literal("show").executes(StationCommand::toggleShow))
            .then(ClientCommands.literal("list").executes(StationCommand::list))
            .then(ClientCommands.literal("check").executes(StationCommand::check));

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

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7工作站（world_" + manager.loadedWorld()
            + "，当前维度 " + DimensionUtils.display(DimensionUtils.current()) + "）："
            + manager.totalCount() + " 个坐标，高亮 " + (manager.isShowing() ? "§a开" : "§c关")));
        for (StationPart part : StationPart.values()) {
            int count = manager.positions(part).size();
            source.sendFeedback(Component.literal("  " + part.display() + "：" + count + " 个"
                + (count == 0 ? "§c（无）" : "")));
        }
        source.sendFeedback(Component.literal("  文件: " + manager.currentFile()));
        return 1;
    }

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = StationManager.get().toggleShow();
        context.getSource().sendFeedback(Component.literal("工作站高亮已"
            + (showing ? "§a开启" : "§c关闭") + "§r（各部分的颜色见 /pc stock station list 的行首标签）"));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        StationManager manager = StationManager.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7工作站（world_" + manager.loadedWorld()
            + "，共 " + manager.totalCount() + " 个坐标）："));

        // 单点部分在前面
        for (StationPart part : StationPart.values()) {
            if (part.isList()) continue;
            StationPos pos = manager.single(part);
            MutableComponent line = Component.literal(" ").append(colorTag(part)).append(Component.literal(" "));
            line.append(pos == null ? Component.literal("§c未设置") : line(part, pos));
            source.sendFeedback(line);
        }

        for (StationPart part : StationPart.values()) {
            if (!part.isList()) continue;
            List<StationPos> positions = manager.list(part);
            source.sendFeedback(Component.literal(" ").append(colorTag(part))
                .append(Component.literal("（" + positions.size() + " 个）：")));
            if (positions.isEmpty()) {
                source.sendFeedback(Component.literal("    §8（空）"));
                continue;
            }
            for (StationPos pos : positions) {
                source.sendFeedback(Component.literal("  ").append(line(part, pos)));
            }
        }
        return 1;
    }

    private static int listPart(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();
        List<StationPos> positions = StationManager.get().list(part);

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7" + part.display() + "（" + positions.size() + " 个）："));
        if (positions.isEmpty()) {
            source.sendFeedback(Component.literal("  §8（空）"));
            return 1;
        }
        for (StationPos pos : positions) {
            source.sendFeedback(Component.literal("  ").append(line(part, pos)));
        }
        return 1;
    }

    /** 一行的显示：{@code #id [主] [x y z]} + （列表才有）删除按钮。 */
    private static Component line(StationPart part, StationPos stationPos) {
        MutableComponent text = Component.literal("#" + stationPos.id() + " ").withStyle(ChatFormatting.DARK_GRAY);
        text.append(dimensionTag(stationPos.dimension()));
        text.append(Component.literal(" "));
        text.append(Component.literal("[" + stationPos.coordString() + "]")
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(stationPos.coordString()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                    "点击复制坐标（" + stationPos.describe() + "）")))));

        if (part.isList()) {
            text.append(Component.literal(" "));
            text.append(Component.literal("[删除]").setStyle(Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(ROOT + part.id() + " del "
                    + stationPos.coordString() + " " + stationPos.dimension()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                    "点击把删除命令填到聊天栏：" + ROOT + part.id() + " del "
                        + stationPos.coordString() + " " + stationPos.dimension())))));
        }
        return text;
    }

    private static MutableComponent colorTag(StationPart part) {
        return Component.literal("[" + part.display() + "]")
            .setStyle(Style.EMPTY.withColor(part.color() & 0xFFFFFF));
    }

    private static MutableComponent dimensionTag(String dimension) {
        String shortName = switch (dimension) {
            case DimensionUtils.NETHER -> "下";
            case DimensionUtils.END -> "末";
            case DimensionUtils.OVERWORLD -> "主";
            default -> dimension;
        };
        ChatFormatting color = switch (dimension) {
            case DimensionUtils.NETHER -> ChatFormatting.RED;
            case DimensionUtils.END -> ChatFormatting.LIGHT_PURPLE;
            case DimensionUtils.OVERWORLD -> ChatFormatting.GREEN;
            default -> ChatFormatting.GRAY;
        };
        return Component.literal("[" + shortName + "]").withStyle(color);
    }

    // ------------------------------------------------------------------
    // 单点部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> singlePart(StationPart part) {
        LiteralArgumentBuilder<FabricClientCommandSource> builder = ClientCommands.literal(part.id());

        if (part == StationPart.STAND_POINT) {
            builder.then(ClientCommands.literal("set_here")
                .executes(context -> setHere(context, part)));
        } else {
            builder.then(ClientCommands.literal("set_target")
                .executes(context -> setTarget(context, part)));
        }

        builder.then(ClientCommands.literal("set")
            .then(coords(context -> setCoords(context, part))));
        return builder;
    }

    private static int setHere(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            source.sendError(Component.literal("没有玩家"));
            return 0;
        }

        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Component.literal("拿不到玩家位置"));
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
            source.sendError(Component.literal("有 " + otherDimension.size() + " 个工作站方块不在"
                + DimensionUtils.display(dimension) + "："));
            for (StationPos pos : otherDimension) {
                source.sendError(Component.literal("  · " + pos.describe()));
            }
            return 0;
        }
        if (!tooFar.isEmpty()) {
            source.sendError(Component.literal("有 " + tooFar.size() + " 个工作站方块不在这个位置的触及范围内"
                + "（触及距离 " + format(PlayerUtils.reach()) + "）："));
            for (StationPos pos : tooFar) {
                source.sendError(Component.literal("  · " + pos.coordString() + "  距离 "
                    + format(PlayerUtils.eyeDistanceFrom(here, pos.pos()))));
            }
            return 0;
        }

        boolean wasSet = StationManager.get().single(part) != null;
        StationManager.get().setSingle(part, dimension, here);
        source.sendFeedback(Component.literal((wasSet ? "已更新 " : "已设置 ") + part.display()
            + " = " + DimensionUtils.display(dimension) + " " + here.toShortString()
            + "（其它工作站坐标都够得着）"));
        return 1;
    }

    private static int setTarget(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
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
        source.sendFeedback(Component.literal("已设置 " + part.display() + " = "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()));
        hintBlockType(source, part, pos);
        return 1;
    }

    // ------------------------------------------------------------------
    // 列表部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> listPart(StationPart part) {
        return ClientCommands.literal(part.id())
            .then(ClientCommands.literal("add_target")
                .executes(context -> addTarget(context, part)))
            .then(ClientCommands.literal("add")
                .then(coords(context -> addCoords(context, part))))
            .then(ClientCommands.literal("del_target")
                .executes(context -> delTarget(context, part)))
            .then(ClientCommands.literal("del")
                .then(dimCoords(context -> delCoords(context, part))))
            .then(ClientCommands.literal("list")
                .executes(context -> listPart(context, part)));
    }

    private static int addTarget(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
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
            source.sendFeedback(Component.literal(part.display() + " 里已经有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString() + " 了"));
            return 1;
        }

        source.sendFeedback(Component.literal("已加入 " + part.display() + " "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + StationManager.get().list(part).size() + " 个）"));
        hintBlockType(source, part, pos);
        return 1;
    }

    private static int delTarget(CommandContext<FabricClientCommandSource> context, StationPart part) {
        FabricClientCommandSource source = context.getSource();

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
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
            source.sendFeedback(Component.literal(part.display() + " 里没有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString()));
            return 1;
        }
        source.sendFeedback(Component.literal("已从 " + part.display() + " 删掉 "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + StationManager.get().list(part).size() + " 个）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // check
    // ------------------------------------------------------------------

    private static int check(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        // 不开箱子就能查的那部分（/pc stock task start_collect 用的是同一套）
        StationPrecheck.Result result = StationPrecheck.run(mc);
        for (String hint : result.hints()) source.sendFeedback(Component.literal("§e提示：" + hint));

        if (!result.ok()) {
            source.sendError(Component.literal("工作站检查没通过（" + result.problems().size() + " 条）："));
            for (String problem : result.problems()) source.sendError(Component.literal("  · " + problem));
            return 0;
        }

        ActionManager.get().submit(new StationCheckAction());
        source.sendFeedback(Component.literal("方块都对了，开始逐个打开容器数内容"
            + "（物品暂存处、空潜影盒提供处、存储区、产物区，以及摆放处上已经放着的潜影盒；"
            + "不显示界面，要几秒）"));
        return 1;
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
        return ClientCommands.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(command)));
    }

    /** del 用的 x y z：z 上挂「不带 dim」的版本，可选的 [dim] 上挂同一个命令。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> dimCoords(
            Command<FabricClientCommandSource> command) {
        return ClientCommands.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(command)
                    .then(ClientCommands.argument("dim", StringArgumentType.greedyString())
                        .suggests(DimensionSuggestions::suggest)
                        .executes(command))));
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
            source.sendError(Component.literal(DimensionUtils.display(dimension) + " " + pos.toShortString()
                + " 和站立点（" + stand.describe() + "）不在一个维度，"
                + "工作站的所有方块都要在站立点的触及范围内"));
            return false;
        }
        if (!PlayerUtils.isWithinReachFrom(stand.pos(), pos)) {
            source.sendError(Component.literal(pos.toShortString() + " 离站立点 " + stand.coordString()
                + " 太远（距离 " + format(PlayerUtils.eyeDistanceFrom(stand.pos(), pos))
                + " > " + format(PlayerUtils.reach()) + "），工作站的所有方块都要在站立点的触及范围内"));
            return false;
        }
        return true;
    }

    /** 设置的时候顺便提醒方块类型对不对（真正的硬性检查在 check 里）。 */
    private static void hintBlockType(FabricClientCommandSource source, StationPart part, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockState state = mc.level.getBlockState(pos);
        String expected = null;

        if (part == StationPart.CRAFTING_TABLE && !(state.getBlock() instanceof CraftingTableBlock)) {
            expected = "工作台方块";
        } else if (part == StationPart.STONECUTTER && !(state.getBlock() instanceof StonecutterBlock)) {
            expected = "切石机方块";
        } else if (part == StationPart.ITEM_TEMP) {
            if (!(state.getBlock() instanceof ChestBlock)) {
                expected = "大箱子";
            } else if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                expected = "大箱子（两个连在一起的箱子）";
            }
        } else if (part == StationPart.SHULKER_BOX_PROVIDER && !(state.getBlock() instanceof BarrelBlock)) {
            expected = "木桶";
        } else if (part == StationPart.SHULKER_BOX_PLACEMENT && !state.isAir()) {
            expected = "空气";
        } else if ((part == StationPart.ITEM_STORAGE || part == StationPart.ITEM_FINAL)
            && !(mc.level.getBlockEntity(pos) instanceof Container)) {
            expected = "容器";
        }

        if (expected != null) {
            source.sendFeedback(Component.literal("§e提示：这里现在是 " + ContainerTypes.idOf(state)
                + "，check 会要求是" + expected));
        }
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }
}
