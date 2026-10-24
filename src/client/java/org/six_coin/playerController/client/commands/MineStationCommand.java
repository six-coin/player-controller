package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
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
 * {@code /pc mine station ...} —— 挖掘站。
 *
 * <pre>
 * /pc mine station list                    列出所有部分的坐标 + 落点
 * /pc mine station check                   算落点、查可达性，再走过去逐个开箱检查，写 mine/check.json
 * /pc mine station show                    开关高亮（各部分颜色见 MineStationPart）
 *
 * /pc mine station safe_point set &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | set_here
 * /pc mine station food_provider set &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | set_target
 * /pc mine station totem_provider set &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | set_target
 *
 * /pc mine station item_collector add &lt;item_id&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt;
 * /pc mine station item_collector add_target &lt;item_id&gt;
 * /pc mine station item_collector del &lt;item_id&gt;
 * /pc mine station item_collector del &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt;
 * /pc mine station item_collector del_target
 *
 * /pc mine station tool_cache add &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | add_target
 *                          del &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dim&gt; | del_target | list
 * /pc mine station slime_cache 同上
 * /pc mine station cobblestone_cache 同上
 * /pc mine station unloading_shulker_box_placement 同上（要正好 9 个，而且都空着）
 * </pre>
 *
 * <p>和备货的工作站不一样：挖掘站不要求所有容器都在触及范围内，所以 set / add 只记坐标、不查距离；
 * 每个容器在哪里开（{@code access_position}）由 {@code check} 算出来。
 *
 * <p>产物收集（{@code item_collector}）是可选的：不配就不收集产物，{@code check} 也不会报错；
 * 别的部分（食物、图腾、工具缓存、黏液块缓存、圆石缓存、卸货潜影盒摆放处）都必须配。
 * 配了的东西都要在路径点的可达范围内，否则 {@code check} 会报错。
 */
public final class MineStationCommand {

    private static final String ROOT = "/pc mine station ";

    private MineStationCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommands.literal("station")
                .executes(context -> {
                    showStatus(context.getSource());
                    return 1;
                })
                .then(ClientCommands.literal("list")
                    .executes(MineStationCommand::list))
                .then(ClientCommands.literal("check")
                    .executes(MineStationCommand::check))
                .then(ClientCommands.literal("show")
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

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7挖掘站（world_" + manager.loadedWorld()
            + "，共 " + manager.totalCount() + " 个坐标，高亮 "
            + (manager.isShowing() ? "§a开" : "§c关") + "§r§7）："));
        for (MineStationPart part : MineStationPart.values()) {
            int count = manager.positions(part).size();
            String note = count > 0 ? "" : (part.optional() ? "§7（无，可选）" : "§c（无）");
            source.sendFeedback(Component.literal("  " + part.display() + "：" + count + " 个" + note));
        }
        source.sendFeedback(Component.literal("  文件: " + manager.currentFile()));
        source.sendFeedback(Component.literal("  报告: " + manager.currentCheckFile()));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "check§7 - 算落点并走过去开箱检查"
            + "；§f" + ROOT + "list§7 - 看坐标和落点"));
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MineStationManager manager = MineStationManager.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7挖掘站（world_" + manager.loadedWorld()
            + "，共 " + manager.totalCount() + " 个坐标）："));

        for (MineStationPart part : MineStationPart.values()) {
            if (part.isList()) continue;
            MinePlace place = manager.single(part);
            MutableComponent line = Component.literal(" ").append(colorTag(part)).append(Component.literal(" "));
            line.append(place == null ? Component.literal("§c未设置") : placeLine(part, place));
            source.sendFeedback(line);
        }

        for (MineStationPart part : MineStationPart.values()) {
            if (!part.isList()) continue;
            List<MinePlace> places = manager.list(part);
            source.sendFeedback(Component.literal(" ").append(colorTag(part))
                .append(Component.literal("（" + places.size() + " 个）：")));
            if (places.isEmpty()) {
                source.sendFeedback(Component.literal("    §8（空）"));
                continue;
            }
            for (MinePlace place : places) {
                source.sendFeedback(Component.literal("  ").append(placeLine(part, place)));
            }
        }
        return 1;
    }

    /** 一行：{@code #id [产物 物品] [主] [x y z] [删除] 落点 x y z}。 */
    private static Component placeLine(MineStationPart part, MinePlace place) {
        MutableComponent text = Component.literal("#" + place.id() + " ").withStyle(ChatFormatting.DARK_GRAY);

        if (part == MineStationPart.ITEM_COLLECTOR && place.itemId() != null) {
            text.append(Component.literal(place.itemId()).withStyle(ChatFormatting.GREEN)).append(Component.literal(" "));
        }

        text.append(dimensionTag(place.dimension()));
        text.append(Component.literal(" "));
        text.append(Component.literal("[" + place.coordString() + "]")
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(place.coordString()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                    "点击复制坐标（" + place.describe() + "）")))));

        if (part.isList()) {
            text.append(Component.literal(" "));
            text.append(deleteButton(part, place));
        }

        MineAccessPos access = place.access();
        text.append(Component.literal("  " + (access == null ? "§8落点未生成" : "§8落点 " + access.coordString())));
        return text;
    }

    private static Component deleteButton(MineStationPart part, MinePlace place) {
        String command = ROOT + part.id() + " del " + place.coordString() + " " + place.dimension();
        return Component.literal("[删除]").setStyle(Style.EMPTY
            .withColor(ChatFormatting.YELLOW)
            .withClickEvent(new ClickEvent.SuggestCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击把删除命令填到聊天栏：" + command))));
    }

    private static MutableComponent colorTag(MineStationPart part) {
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

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = MineStationManager.get().toggleShow();
        context.getSource().sendFeedback(Component.literal("挖掘站高亮已"
            + (showing ? "§a开启" : "§c关闭") + "§r（各部分的颜色见 /pc mine station list 的行首标签）"));
        return 1;
    }

    private static int check(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        ActionManager.get().submit(new MineStationCheckAction());
        source.sendFeedback(Component.literal("开始检查挖掘站：先给每个容器算落点，再按路径图一个接一个走过去开箱"
            + "（不显示界面，会走很久）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 单点部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> singlePart(MineStationPart part) {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommands.literal(part.id());

        if (part == MineStationPart.SAFE_POINT) {
            builder.then(ClientCommands.literal("set_here")
                .executes(context -> setHere(context, part)));
        } else {
            builder.then(ClientCommands.literal("set_target")
                .executes(context -> setTarget(context, part)));
        }

        builder.then(ClientCommands.literal("set")
            .then(coordsWithDim(context -> setCoords(context, part))));
        return builder;
    }

    private static int setHere(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Component.literal("拿不到玩家位置"));
            return 0;
        }
        return set(source, part, DimensionUtils.current(), here);
    }

    private static int setTarget(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
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

        source.sendFeedback(Component.literal((wasSet ? "已更新 " : "已设置 ") + part.display()
            + " = " + DimensionUtils.display(dimension) + " " + pos.toShortString()));
        hintBlockType(source, part, pos);
        return 1;
    }

    // ------------------------------------------------------------------
    // 列表部分
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> listPart(MineStationPart part) {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommands.literal(part.id());

        if (part == MineStationPart.ITEM_COLLECTOR) {
            builder.then(ClientCommands.literal("add")
                .then(ClientCommands.argument("item_id", IdentifierArgument.id())
                    .suggests(MineStationCommand::suggestItems)
                    .then(coordsWithDim(context -> addCoords(context, part)))));
            builder.then(ClientCommands.literal("add_target")
                .then(ClientCommands.argument("item_id", IdentifierArgument.id())
                    .suggests(MineStationCommand::suggestItems)
                    .executes(context -> addTargetWithItem(context, part))));
            builder.then(ClientCommands.literal("del")
                .then(coordsWithDim(context -> delCoords(context, part)))
                .then(ClientCommands.argument("item_id", IdentifierArgument.id())
                    .suggests(MineStationCommand::suggestItems)
                    .executes(MineStationCommand::delByItemId)));
        } else {
            builder.then(ClientCommands.literal("add")
                .then(coordsWithDim(context -> addCoords(context, part))));
            builder.then(ClientCommands.literal("add_target")
                .executes(context -> addTarget(context, part)));
            builder.then(ClientCommands.literal("del")
                .then(coordsWithDim(context -> delCoords(context, part))));
        }

        builder.then(ClientCommands.literal("del_target")
            .executes(context -> delTarget(context, part)));
        builder.then(ClientCommands.literal("list")
            .executes(context -> listPart(part, context)));
        return builder;
    }

    private static int addTarget(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
            return 0;
        }
        return add(source, part, DimensionUtils.current(), pos, null);
    }

    private static int addTargetWithItem(CommandContext<FabricClientCommandSource> context,
                                         MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
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
                source.sendError(Component.literal("产物收集要写清楚产物 id："
                    + ROOT + "item_collector add <item_id> <x> <y> <z> <dim>"));
                return 0;
            }
            Identifier id = Identifier.tryParse(itemId);
            if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                source.sendError(Component.literal("没有这个物品：" + itemId));
                return 0;
            }
        }

        if (!MineStationManager.get().add(part, dimension, pos, itemId)) {
            source.sendFeedback(Component.literal(part.display() + " 里已经有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString() + " 了"));
            return 1;
        }

        List<MinePlace> places = MineStationManager.get().list(part);
        MinePlace added = places.isEmpty() ? null : places.get(places.size() - 1);
        source.sendFeedback(Component.literal("已加入 " + part.display()
            + (itemId == null ? "" : "（产物 " + itemId + "）")
            + " " + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + places.size() + " 个" + (added == null ? "" : "，#" + added.id()) + "）"));

        // 卸货潜影盒摆放处要正好 9 个：允许先多配，但超了要说一声
        if (part == MineStationPart.UNLOADING_PLACEMENT
            && places.size() > MineStationCheckAction.UNLOADING_PLACEMENT_COUNT) {
            source.sendFeedback(Component.literal("§e提示：" + part.display() + " 必须正好 "
                + MineStationCheckAction.UNLOADING_PLACEMENT_COUNT + " 个，现在有 " + places.size()
                + " 个，check 会报错（多出来的请 del 掉）"));
        }

        hintBlockType(source, part, pos);
        return 1;
    }

    private static int delTarget(CommandContext<FabricClientCommandSource> context, MineStationPart part) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
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
            source.sendFeedback(Component.literal(part.display() + " 里没有 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString()));
            return 1;
        }
        source.sendFeedback(Component.literal("已从 " + part.display() + " 删掉 "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()
            + "（现在 " + MineStationManager.get().list(part).size() + " 个）"));
        return 1;
    }

    private static int delByItemId(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String itemId = readItemId(context);

        int removed = MineStationManager.get().removeByItemId(MineStationPart.ITEM_COLLECTOR, itemId);
        if (removed == 0) {
            source.sendFeedback(Component.literal("产物收集里没有产物是 " + itemId + " 的条目"));
            return 1;
        }
        source.sendFeedback(Component.literal("已删掉产物收集里 " + removed + " 条产物为 " + itemId
            + " 的（现在 " + MineStationManager.get().list(MineStationPart.ITEM_COLLECTOR).size() + " 个）"));
        return 1;
    }

    private static int listPart(MineStationPart part, CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        List<MinePlace> places = MineStationManager.get().list(part);

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7" + part.display() + "（" + places.size() + " 个）："));
        if (places.isEmpty()) {
            source.sendFeedback(Component.literal("  §8（空）"));
            return 1;
        }
        for (MinePlace place : places) {
            source.sendFeedback(Component.literal("  ").append(placeLine(part, place)));
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

        return ClientCommands.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .then(ClientCommands.argument("dim", IdentifierArgument.id())
                        .suggests(DimensionSuggestions::suggest)
                        .executes(command))));
    }

    private static CompletableFuture<Suggestions> suggestItems(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockState state = mc.level.getBlockState(pos);
        String expected = null;

        if (!part.isContainer()) {
            // 摆放处：check 要求这里是空的
            if (part != MineStationPart.SAFE_POINT && !state.isAir()) {
                source.sendFeedback(Component.literal("§e提示：这里现在是 " + ContainerTypes.idOf(state)
                    + "，check 会要求这里空着（是空气）"));
            }
        } else if (part == MineStationPart.ITEM_COLLECTOR) {
            if (!(state.getBlock() instanceof ShulkerBoxBlock)) expected = "潜影盒";
        } else if (!ContainerTypes.isSupported(state.getBlock())) {
            expected = "容器（" + ContainerTypes.describeSupported() + "）";
        }

        if (expected != null) {
            source.sendFeedback(Component.literal("§e提示：这里现在是 " + ContainerTypes.idOf(state)
                + "，check 会要求是" + expected));
        }
    }
}
