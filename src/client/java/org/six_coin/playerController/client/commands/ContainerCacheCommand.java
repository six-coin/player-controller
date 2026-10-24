package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.ContainerCacheAddAction;
import org.six_coin.playerController.client.container.CachedContainer;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerItemsExporter;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /pc container cache ...} —— 容器缓存的增、删、查、重排、高亮。
 *
 * <pre>
 * /pc container cache add &lt;x&gt; &lt;y&gt; &lt;z&gt;            把当前维度这个位置的容器加进缓存（等价 add_position）
 * /pc container cache add_position &lt;x&gt; &lt;y&gt; &lt;z&gt;   同上
 * /pc container cache add_target                  把准星指着的容器加进缓存
 * /pc container cache add_batch &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;  把这个长方体里所有支持的容器都加进缓存
 * /pc container cache del &lt;x&gt; &lt;y&gt; &lt;z&gt; [dim]       取消缓存（等价 del_position）
 * /pc container cache del_position &lt;x&gt; &lt;y&gt; &lt;z&gt; [dim]
 * /pc container cache del_id &lt;id&gt;                 按编号取消缓存
 * /pc container cache del_target                  取消准星指着的那个容器的缓存
 * /pc container cache del_batch &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; [dim]
 * /pc container cache optimize                    按维度（主世界、下界、末地）、xyz 递增重新编号（从 1 开始）
 * /pc container cache list                        列出所有缓存
 * /pc container cache show                        开关紫色高亮
 * /pc container cache get_all_items               给每个缓存容器找一个走得到的落点，汇总成 all_items.json
 * </pre>
 *
 * <p>加缓存必须能打开容器（要在触及范围内、是支持的容器），所以是一次真实的开箱流程；
 * 删和重排不用开箱，填了 {@code dim} 就能远程操作。
 */
public final class ContainerCacheCommand {

    private static final String ROOT = "/pc container cache ";

    /** list 最多列多少行。 */
    private static final int LIST_LIMIT = 80;

    /** add_batch / del_batch 一次最多处理多少个位置。 */
    private static final int MAX_BATCH = 4096;

    private static final String[] BOX_ARGS = {"x1", "y1", "z1", "x2", "y2", "z2"};

    private ContainerCacheCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("cache")
            .executes(ContainerCacheCommand::status)
            .then(positionAdd("add"))
            .then(positionAdd("add_position"))
            .then(ClientCommands.literal("add_target")
                .executes(ContainerCacheCommand::addTarget))
            .then(batchAdd())
            .then(positionDel("del"))
            .then(positionDel("del_position"))
            .then(ClientCommands.literal("del_id")
                .then(ClientCommands.argument("id", IntegerArgumentType.integer())
                    .executes(ContainerCacheCommand::delId)))
            .then(ClientCommands.literal("del_target")
                .executes(ContainerCacheCommand::delTarget))
            .then(batchDel())
            .then(ClientCommands.literal("optimize")
                .executes(ContainerCacheCommand::optimize))
            .then(ClientCommands.literal("list")
                .executes(ContainerCacheCommand::list))
            .then(ClientCommands.literal("show")
                .executes(ContainerCacheCommand::toggleShow))
            .then(ClientCommands.literal("get_all_items")
                .executes(ContainerCacheCommand::getAllItems));
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ContainerCacheManager cache = ContainerCacheManager.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7容器缓存（world_" + cache.loadedWorld()
            + "）：" + cache.size() + " 个容器"));
        source.sendFeedback(Component.literal("  高亮: " + (cache.isShowing() ? "§a开" : "§c关")
            + "§r  支持的类型: " + ContainerTypes.describeSupported()));
        source.sendFeedback(Component.literal("  文件: " + cache.currentFile()));
        return 1;
    }

    // ------------------------------------------------------------------
    // 加缓存
    // ------------------------------------------------------------------

    /** {@code add} / {@code add_position}：两个名字是同一条命令。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> positionAdd(String name) {
        return ClientCommands.literal(name)
            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                .suggests(LookSuggestions::x)
                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::y)
                    .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::z)
                        .executes(context -> addPosition(context, readPos(context))))));
    }

    private static int addPosition(CommandContext<FabricClientCommandSource> context, BlockPos pos) {
        return addOne(context.getSource(), pos);
    }

    private static int addTarget(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
            return 0;
        }
        return addOne(source, pos);
    }

    private static int addOne(FabricClientCommandSource source, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (!ContainerTypes.isSupported(state.getBlock())) {
            source.sendError(Component.literal(pos.toShortString() + " 是 " + ContainerTypes.idOf(state)
                + "，不是支持的容器（只支持 " + ContainerTypes.describeSupported() + "）"));
            return 0;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            source.sendError(Component.literal("容器 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）"));
            return 0;
        }

        ChatUtils.debug("加入缓存：" + pos.toShortString() + "（" + ContainerTypes.idOf(state) + "）");
        ActionManager.get().submit(new ContainerCacheAddAction(List.of(pos), 0, 0, true));
        source.sendFeedback(Component.literal("已排队缓存 " + pos.toShortString() + "（"
            + ContainerTypes.idOf(state) + "），跑完会输出结果"));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> batchAdd() {
        return ClientCommands.literal("add_batch")
            .then(intChain(BOX_ARGS, 0, builder -> builder.executes(ContainerCacheCommand::addBatch)));
    }

    private static int addBatch(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        List<BlockPos> box = readBox(context);
        if (box == null) {
            source.sendError(Component.literal("范围太大，一次最多 " + MAX_BATCH + " 个位置"));
            return 0;
        }

        List<BlockPos> valid = new ArrayList<>();
        int outOfReach = 0;
        int unsupported = 0;
        for (BlockPos pos : box) {
            BlockState state = mc.level.getBlockState(pos);
            if (!ContainerTypes.isSupported(state.getBlock())) {
                if (!state.isAir()) unsupported++;
                continue;
            }
            if (!PlayerUtils.isWithinReach(pos)) {
                outOfReach++;
                continue;
            }
            valid.add(pos);
        }

        ChatUtils.debug("批量加缓存：范围 " + box.size() + " 个位置，可用 " + valid.size()
            + "，超出触及范围 " + outOfReach + "，不是支持的容器 " + unsupported);
        source.sendFeedback(Component.literal("已排队批量缓存：范围 " + box.size() + " 个位置，其中 "
            + valid.size() + " 个会打开记录（跑完输出汇总）"));
        ActionManager.get().submit(new ContainerCacheAddAction(valid, outOfReach, unsupported, false));
        return 1;
    }

    // ------------------------------------------------------------------
    // 删缓存
    // ------------------------------------------------------------------

    /** {@code del} / {@code del_position}：两个名字是同一条命令。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> positionDel(String name) {
        return ClientCommands.literal(name)
            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                .suggests(LookSuggestions::x)
                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::y)
                    .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::z)
                        .executes(ContainerCacheCommand::delPosition)
                        .then(ClientCommands.argument("dim", StringArgumentType.greedyString())
                            .suggests(DimensionSuggestions::suggest)
                            .executes(ContainerCacheCommand::delPosition)))));
    }

    private static int delPosition(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = readPos(context);
        String dimension = dimensionArg(context);

        int removed = ContainerCacheManager.get().removeAt(dimension, pos);
        if (removed == 0) {
            source.sendFeedback(Component.literal(DimensionUtils.display(dimension) + " "
                + pos.toShortString() + " 没有缓存"));
        } else {
            source.sendFeedback(Component.literal("已取消缓存 " + DimensionUtils.display(dimension)
                + " " + pos.toShortString()));
        }
        return 1;
    }

    private static int delId(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int id = IntegerArgumentType.getInteger(context, "id");

        CachedContainer container = ContainerCacheManager.get().byId(id);
        if (container == null) {
            source.sendError(Component.literal("没有 #" + id + " 这个缓存"));
            return 0;
        }
        ContainerCacheManager.get().remove(id);
        source.sendFeedback(Component.literal("已取消缓存 #" + id + " " + container.type() + " "
            + DimensionUtils.display(container.dimension()) + " " + container.coordString()));
        return 1;
    }

    private static int delTarget(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
            return 0;
        }

        String dimension = DimensionUtils.current();
        int removed = ContainerCacheManager.get().removeAt(dimension, pos);
        if (removed == 0) {
            source.sendFeedback(Component.literal(DimensionUtils.display(dimension) + " "
                + pos.toShortString() + " 没有缓存"));
        } else {
            source.sendFeedback(Component.literal("已取消缓存 " + DimensionUtils.display(dimension)
                + " " + pos.toShortString()));
        }
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> batchDel() {
        return ClientCommands.literal("del_batch")
            .then(intChain(BOX_ARGS, 0, builder -> {
                builder.executes(ContainerCacheCommand::delBatch);
                builder.then(ClientCommands.argument("dim", StringArgumentType.greedyString())
                    .suggests(DimensionSuggestions::suggest)
                    .executes(ContainerCacheCommand::delBatch));
            }));
    }

    private static int delBatch(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        List<BlockPos> box = readBox(context);
        if (box == null) {
            source.sendError(Component.literal("范围太大，一次最多 " + MAX_BATCH + " 个位置"));
            return 0;
        }

        BlockPos a = box.get(0);
        BlockPos b = box.get(box.size() - 1);
        String dimension = dimensionArg(context);
        int removed = ContainerCacheManager.get().removeInBox(dimension, a, b);

        if (removed == 0) {
            source.sendFeedback(Component.literal(DimensionUtils.display(dimension) + " 这个范围里没有缓存"));
        } else {
            source.sendFeedback(Component.literal("已取消 " + removed + " 个缓存（"
                + DimensionUtils.display(dimension) + " " + a.toShortString() + " ~ " + b.toShortString() + "）"));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 重排 / 列表 / 高亮
    // ------------------------------------------------------------------

    private static int optimize(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ContainerCacheManager cache = ContainerCacheManager.get();

        int before = cache.size();
        cache.optimize();
        source.sendFeedback(Component.literal("容器缓存已重排：" + before + " 个容器按「"
            + "主世界、下界、末地、其它」+ xyz 递增重新编号（id 从 1 开始）"));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ContainerCacheManager cache = ContainerCacheManager.get();
        List<CachedContainer> all = cache.all();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7容器缓存（world_" + cache.loadedWorld()
            + "，" + all.size() + " 个）："));
        if (all.isEmpty()) {
            source.sendFeedback(Component.literal("  §8（空）"));
            return 1;
        }

        int shown = 0;
        for (CachedContainer container : all) {
            if (shown++ >= LIST_LIMIT) {
                source.sendFeedback(Component.literal("  §8… 还有 " + (all.size() - LIST_LIMIT) + " 个没有显示"));
                break;
            }
            source.sendFeedback(containerLine(container));
        }
        return 1;
    }

    private static int toggleShow(CommandContext<FabricClientCommandSource> context) {
        boolean showing = ContainerCacheManager.get().toggleShow();
        context.getSource().sendFeedback(Component.literal("容器缓存高亮已"
            + (showing ? "§a开启" : "§c关闭") + "§r（紫色方块）"));
        return 1;
    }

    /**
     * {@code get_all_items}：给每个缓存容器在触及范围内找一个「有点或者边」的落点，
     * 再看从玩家现在的位置走不走得到，走得到的汇总进 all_items.json。
     */
    private static int getAllItems(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null || source.getLevel() == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        // 1. 玩家必须站在路径点上或者边上（站在边上会在脚下新建一个起点路径点）
        Waypoint start = WaypointManager.get().playerStartWaypoint();
        if (start == null) {
            BlockPos here = PlayerUtils.currentBlockPos();
            source.sendError(Component.literal("你现在" + (here == null ? "" : "所在的 " + here.toShortString())
                + " 既不是路径点，也不在任何边上，先站到图上再执行"));
            return 0;
        }

        if (ContainerCacheManager.get().size() == 0) {
            source.sendFeedback(Component.literal("容器缓存是空的，先加点容器再执行"));
            return 1;
        }

        ContainerItemsExporter.Result result = ContainerItemsExporter.export(start);
        if (result.file() == null) {
            source.sendError(Component.literal("生成 all_items.json 失败，看看日志"));
            return 0;
        }

        source.sendFeedback(Component.literal("起点 #" + start.id() + "；缓存容器 " + result.containers()
            + " 个：找得到落点 " + result.withAccess() + " 个，走得到并写入 " + result.written() + " 个"));
        source.sendFeedback(Component.literal("overall：" + result.overallKinds() + " 种物品 / "
            + result.overallCount() + " 个"));
        source.sendFeedback(Component.literal("文件: " + result.file()));
        return 1;
    }

    /** {@code id [取消缓存] [复制物品] [主] [x y z]} */
    private static Component containerLine(CachedContainer container) {
        MutableComponent line = Component.literal(" " + container.id() + " ").withStyle(ChatFormatting.GRAY);

        line.append(actionButton("取消缓存", ROOT + "del_id " + container.id(),
            "点击把删除命令填到聊天栏（" + ROOT + "del_id <id>）"));
        line.append(Component.literal(" "));
        line.append(Component.literal("[复制物品]").setStyle(Style.EMPTY
            .withColor(ChatFormatting.YELLOW)
            .withClickEvent(new ClickEvent.CopyToClipboard(container.itemsJson()))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击复制 items 的 JSON（"
                + container.itemsSummary() + "）")))));

        line.append(Component.literal(" "));
        line.append(dimensionTag(container.dimension()));
        line.append(Component.literal(" "));
        line.append(coordText(container.pos()));
        return line;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 依次套一串整数参数，最里面那个 builder 交给 deepest 处理。 */
    private static ArgumentBuilder<FabricClientCommandSource, ?> intChain(
            String[] names, int index,
            Consumer<RequiredArgumentBuilder<FabricClientCommandSource, Integer>> deepest) {
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> builder =
            ClientCommands.argument(names[index], IntegerArgumentType.integer())
                .suggests(suggestionsFor(names[index]));
        if (index + 1 < names.length) {
            builder.then(intChain(names, index + 1, deepest));
        } else {
            deepest.accept(builder);
        }
        return builder;
    }

    /** 坐标参数按名字（x1/y1/z1/...）挂上「一次 Tab 补齐坐标」的补全。 */
    private static SuggestionProvider<FabricClientCommandSource> suggestionsFor(String name) {
        return switch (name.charAt(0)) {
            case 'x' -> LookSuggestions::x;
            case 'y' -> LookSuggestions::y;
            default -> LookSuggestions::z;
        };
    }

    /** {@code [dim]} 的补全见 {@link DimensionSuggestions}。 */

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
    }

    /**
     * 读 {@code x1 y1 z1 x2 y2 z2} 这个长方体里的所有位置。
     *
     * @return 位置列表；范围超过 {@link #MAX_BATCH} 返回 null
     */
    @Nullable
    private static List<BlockPos> readBox(CommandContext<FabricClientCommandSource> context) {
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        long total = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (total > MAX_BATCH) return null;

        List<BlockPos> positions = new ArrayList<>((int) total);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions;
    }

    /** 可选的 [dim]：没写就用当前维度。 */
    private static String dimensionArg(CommandContext<FabricClientCommandSource> context) {
        try {
            String dim = StringArgumentType.getString(context, "dim");
            return dim == null || dim.isBlank() ? DimensionUtils.current() : dim.trim();
        } catch (IllegalArgumentException e) {
            return DimensionUtils.current();
        }
    }

    private static Component dimensionTag(String dimension) {
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

    private static Component coordText(BlockPos pos) {
        String text = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("[" + text + "]")
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(text))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击复制坐标"))));
    }

    private static Component actionButton(String label, String command, String hint) {
        return Component.literal("[" + label + "]")
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hint))));
    }
}
