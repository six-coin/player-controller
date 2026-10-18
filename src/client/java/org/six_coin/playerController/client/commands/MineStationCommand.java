package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.mine.station.MineAccessPos;
import org.six_coin.playerController.client.mine.station.MinePlace;
import org.six_coin.playerController.client.mine.station.MineStationCheckAction;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.mine.station.MineStationPart;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /pc mine complex station ...} —— 挖掘站。
 *
 * <pre>
 * /pc mine complex station list                    列出所有部分的坐标 + 落点
 * /pc mine complex station check                   算落点、查可达性，再走过去逐个开箱检查，写 mine/check.json
 * /pc mine complex station show                    开关高亮（各部分颜色见 MineStationPart）
 *
 * /pc mine complex station safe_point set &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | set_here
 * /pc mine complex station food_provider set &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | set_target
 * /pc mine complex station totem_provider set &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | set_target
 *
 * /pc mine complex station item_collector add &lt;item_id&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt;
 * /pc mine complex station item_collector add_target &lt;item_id&gt;
 * /pc mine complex station item_collector del &lt;item_id&gt;
 * /pc mine complex station item_collector del &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt;
 * /pc mine complex station item_collector del_target
 *
 * /pc mine complex station tool_cache add &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | add_target
 *                                del &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | del_target | list
 * /pc mine complex station slime_cache 同上
 * </pre>
 *
 * <p>和备货的工作站不一样：挖掘站不要求所有容器都在触及范围内，所以 set / add 只记坐标、不查距离；
 * 每个容器在哪里开（{@code access_position}）由 {@code check} 算出来。
 */
public final class MineStationCommand {

    private static final String ROOT = "/pc mine complex station ";

    private MineStationCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommandManager.literal("station")
                .executes(context -> {
                    showStatus(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.literal("list")
                    .executes(MineStationCommand::list))
                .then(ClientCommandManager.literal("check")
                    .executes(MineStationCommand::check))
                .then(ClientCommandManager.literal("show")
                    .executes(MineStationCommand::toggleShow));

        for (MineStationPart part : MineStationPart.values()) {
            builder.then(part.isList() ? listPart(part) : singlePart(part));
        }
        return builder;
    }

    // ------------------------------------------------------------------
    // 状态 / 列表 / 高亮 / 检查
    // ------------------------------------------------------------------

    /** {@code /pc mine complex} 和 {@code ... station} 共用的总览。 */
    public static void showStatus(FabricClientCommandSource source) {
        MineStationManager manager = MineStationManager.get();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7挖掘站（world_" + manager.loadedWorld()
            + "，共 " + manager.totalCount() + " 个坐标，高亮 "
            + (manager.isShowing() ? "§a开" : "§c关") + "§r§7）："));
        for (MineStationPart part : MineStationPart.values()) {
            int count = manager.positions(part).size();
            source.sendFeedback(Text.literal("  " + part.display() + "：" + count + " 个"
                + (count == 0 ? "§c（无）" : "")));
        }
        source.sendFeedback(Text.literal("  文件: " + manager.currentFile()));
        source.sendFeedback(Text.literal("  报告: " + manager.currentCheckFile()));
        source.sendFeedback(Text.literal("§7  §f" + ROOT + "check§7 - 算落点并走过去开箱检查"
            + "；§f" + ROOT + "list§7 - 看坐标和落点"));
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MineStationManager manager = MineStationManager.get();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7挖掘站（world_" + manager.loadedWorld()
            + "，共 " + manager.totalCount() + " 个坐标）："));

        for (MineStationPart part : MineStationPart.values()) {
            if (part.isList()) continue;
            MinePlace place = manager.single(part);
            MutableText line = Text.literal(" ").append(colorTag(part)).append(Text.literal(" "));
            line.append(place == null ? Text.literal("§c未设置") : placeLine(part, place));
            source.sendFeedback(line);
        }

        for (MineStationPart part : MineStationPart.values()) {
            if (!part.isList()) continue;
            List<MinePlace> places = manager.list(part);
            source.sendFeedback(Text.literal(" ").append(colorTag(part))
                .append(Text.literal("（" + places.size() + " 个）：")));
            if (places.isEmpty()) {
                source.sendFeedback(Text.literal("    §8（空）"));
                continue;
            }
            for (MinePlace place : places) {
                source.sendFeedback(Text.literal("  ").append(placeLine(part, place)));
            }
        }
        return 1;
    }

    /** 一行：{@code #id [产物 物品] [主] [x y z] [删除] 落点 x y z}。 */
    private static Text placeLine(MineStationPart part, MinePlace place) {
        MutableText text = Text.literal("#" + place.id() + " ").formatted(Formatting.DARK_GRAY);

        if (part == MineStationPart.ITEM_COLLECTOR && place.itemId() != null) {
            text.append(Text.literal(place.itemId()).formatted(Formatting.GREEN)).append(Text.literal(" "));
        }

        text.append(dimensionTag(place.dimension()));
        text.append(Text.literal(" "));
        text.append(Text.literal("[" + place.coordString() + "]")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(place.coordString()))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(
                    "点击复制坐标（" + place.describe() + "）")))));

        if (part.isList()) {
            text.append(Text.literal(" "));
            text.append(deleteButton(part, place));
        }

        MineAccessPos access = place.access();
        text.append(Text.literal("  " + (access == null ? "§8落点未生成" : "§8落点 " + access.coordString())));
        return text;
    }

    private static Text deleteButton(MineStationPart part, MinePlace place) {
        String command = ROOT + part.id() + " del " + place.coordString() + " " + place.dimension();
        return Text.literal("[删除]").setStyle(Style.EMPTY
            .withColor(Formatting.YELLOW)
            .withClickEvent(new ClickEvent.SuggestCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击把删除命令填到聊天栏：" + command))));
    }

    private static MutableText colorTag(MineStationPart part) {
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

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = MineStationManager.get().toggleShow();
        context.getSource().sendFeedback(Text.literal("挖掘站高亮已"
            + (showing ? "§a开启" : "§c关闭") + "§r（各部分的颜色见 /pc mine complex station list 的行首标签）"));
        return 1;
    }

    private static int check(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        ActionManager.get().submit(new MineStationCheckAction());
        source.sendFeedback(Text.literal("开始检查挖掘站：先给每个容器算落点，再按路径图一个接一个走过去开箱"
            + "（不显示界面，会走很久）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 单点部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> singlePart(MineStationPart part) {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommandManager.literal(part.id());

        if (part == MineStationPart.SAFE_POINT) {
            builder.then(ClientCommandManager.literal("set_here")
                .executes(context -> setHere(context, part)));
        } else {
            builder.then(ClientCommandManager.literal("set_target")
                .executes(context -> setTarget(context, part)));
        }

        builder.then(ClientCommandManager.literal("set")
            .then(coordsWithDim(context -> setCoords(context, part))));
        return builder;
    }

    private static int setHere(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        return set(source, part, DimensionUtils.current(), here);
    }

    private static int setTarget(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return set(source, part, DimensionUtils.current(), pos);
    }

    private static int setCoords(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        return set(context.getSource(), part, readDimension(context), readPos(context));
    }

    private static int set(FabricClientCommandSource source, MineStationPart part,
                           String dimension, BlockPos pos) {
        boolean wasSet = MineStationManager.get().single(part) != null;
        MineStationManager.get().setSingle(part, dimension, pos);

        source.sendFeedback(Text.literal((wasSet ? "已更新 " : "已设置 ") + part.display()
            + " = " + DimensionUtils.display(dimension) + " " + pos.toShortString()));
        hintBlockType(source, part, pos);
        return 1;
    }

    // ------------------------------------------------------------------
    // 列表部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> listPart(MineStationPart part) {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommandManager.literal(part.id());

        if (part == MineStationPart.ITEM_COLLECTOR) {
            builder.then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("item_id", IdentifierArgumentType.identifier())
                    .suggests(MineStationCommand::suggestItems)
                    .then(coordsWithDim(context -> addCoords(context, part)))));
            builder.then(ClientCommandManager.literal("add_target")
                .then(ClientCommandManager.argument("item_id", IdentifierArgumentType.identifier())
                    .suggests(MineStationCommand::suggestItems)
                    .executes(context -> addTargetWithItem(context, part))));
            builder.then(ClientCommandManager.literal("del")
                .then(coordsWithDim(context -> delCoords(context, part)))
                .then(ClientCommandManager.argument("item_id", IdentifierArgumentType.identifier())
                    .suggests(MineStationCommand::suggestItems)
                    .executes(MineStationCommand::delByItemId)));
        } else {
            builder.then(ClientCommandManager.literal("add")
                .then(coordsWithDim(context -> addCoords(context, part))));
            builder.then(ClientCommandManager.literal("add_target")
                .executes(context -> addTarget(context, part)));
            builder.then(ClientCommandManager.literal("del")
                .then(coordsWithDim(context -> delCoords(context, part))));
        }

        builder.then(ClientCommandManager.literal("del_target")
            .executes(context -> delTarget(context, part)));
        builder.then(ClientCommandManager.literal("list")
            .executes(context -> listPart(part, context)));
        return builder;
    }

    private static int addTarget(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return add(source, part, DimensionUtils.current(), pos, null);
    }

    private static int addTargetWithItem(CommandContext<FabricClientCommandSource> context,
                                         MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return add(source, part, DimensionUtils.current(), pos, readItemId(context));
    }

    private static int addCoords(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        return add(context.getSource(), part, readDimension(context), readPos(context),
            part == MineStationPart.ITEM_COLLECTOR ? readItemId(context) : null);
    }

    private static int add(FabricClientCommandSource source, MineStationPart part,
                           String dimension, BlockPos pos, String itemId) {
        if (part == MineStationPart.ITEM_COLLECTOR) {
            if (itemId == null || itemId.isBlank()) {
                source.sendError(Text.literal("产物收集要写清楚产物 id："
                    + ROOT + "item_collector add <item_id> <x> <y> <z> <dim>"));
                return 0;
            }
            Identifier id = Identifier.tryParse(itemId);
            if (id == null || Registries.ITEM.getOptionalValue(id).isEmpty()) {
                source.sendError(Text.literal("没有这个物品：" + itemId));
                return 0;
            }
        }

        if (!MineStationManager.get().add(part, dimension, pos, itemId)) {
            source.sendFeedback(Text.literal(part.display() + " 里已经有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString() + " 了"));
            return 1;
        }

        List<MinePlace> places = MineStationManager.get().list(part);
        MinePlace added = places.isEmpty() ? null : places.get(places.size() - 1);
        source.sendFeedback(Text.literal("已加入 " + part.display()
            + (itemId == null ? "" : "（产物 " + itemId + "）")
            + " " + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + places.size() + " 个" + (added == null ? "" : "，#" + added.id()) + "）"));
        hintBlockType(source, part, pos);
        return 1;
    }

    private static int delTarget(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return remove(source, part, DimensionUtils.current(), pos);
    }

    private static int delCoords(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        return remove(context.getSource(), part, readDimension(context), readPos(context));
    }

    private static int remove(FabricClientCommandSource source, MineStationPart part,
                              String dimension, BlockPos pos) {
        if (!MineStationManager.get().remove(part, dimension, pos)) {
            source.sendFeedback(Text.literal(part.display() + " 里没有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString()));
            return 1;
        }
        source.sendFeedback(Text.literal("已从 " + part.display() + " 删掉 "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + MineStationManager.get().list(part).size() + " 个）"));
        return 1;
    }

    private static int delByItemId(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String itemId = readItemId(context);

        int removed = MineStationManager.get().removeByItemId(MineStationPart.ITEM_COLLECTOR, itemId);
        if (removed == 0) {
            source.sendFeedback(Text.literal("产物收集里没有产物是 " + itemId + " 的条目"));
            return 1;
        }
        source.sendFeedback(Text.literal("已删掉产物收集里 " + removed + " 条产物为 " + itemId
            + " 的（现在 " + MineStationManager.get().list(MineStationPart.ITEM_COLLECTOR).size() + " 个）"));
        return 1;
    }

    private static int listPart(MineStationPart part, CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        List<MinePlace> places = MineStationManager.get().list(part);

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7" + part.display() + "（" + places.size() + " 个）："));
        if (places.isEmpty()) {
            source.sendFeedback(Text.literal("  §8（空）"));
            return 1;
        }
        for (MinePlace place : places) {
            source.sendFeedback(Text.literal("  ").append(placeLine(part, place)));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    /**
     * {@code x y z dim} 四个参数：x y z 一次 Tab 补齐成准星指着的方块（和 add_batch 一样），
     * dim 用原版的 id 参数类型（{@code minecraft:overworld} 的冒号才不会被 word 截断）。
     */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordsWithDim(
        Command<FabricClientCommandSource> command) {

        return ClientCommandManager.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .then(ClientCommandManager.argument("dim", IdentifierArgumentType.identifier())
                        .suggests(DimensionSuggestions::suggest)
                        .executes(command))));
    }

    private static CompletableFuture<Suggestions> suggestItems(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder);
    }

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
    }

    private static String readDimension(CommandContext<FabricClientCommandSource> context) {
        Identifier dim = context.getArgument("dim", Identifier.class);
        return dim == null ? DimensionUtils.current() : dim.toString();
    }

    private static String readItemId(CommandContext<FabricClientCommandSource> context) {
        Identifier id = context.getArgument("item_id", Identifier.class);
        return id == null ? null : id.toString();
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    /** 设置 / 添加的时候顺便提醒方块类型对不对（硬性检查在 check 里）。 */
    private static void hintBlockType(FabricClientCommandSource source, MineStationPart part, BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        BlockState state = mc.world.getBlockState(pos);
        String expected = null;

        if (part == MineStationPart.ITEM_COLLECTOR) {
            if (!(state.getBlock() instanceof ShulkerBoxBlock)) expected = "潜影盒";
        } else if (part != MineStationPart.SAFE_POINT && !ContainerTypes.isSupported(state.getBlock())) {
            expected = "容器（" + ContainerTypes.describeSupported() + "）";
        }

        if (expected != null) {
            source.sendFeedback(Text.literal("§e提示：这里现在是 " + ContainerTypes.idOf(state)
                + "，check 会要求是" + expected));
        }
    }
}
