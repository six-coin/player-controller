package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.mine.MineFilter;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.mine.MineRegion;
import org.six_coin.playerController.client.mine.complex.MineComplexManager;
import org.six_coin.playerController.client.mine.complex.MineRegionInspectAction;
import org.six_coin.playerController.client.mine.complex.MineTaskRegion;
import org.six_coin.playerController.client.mine.station.MineStationCheckAction;
import org.six_coin.playerController.client.mine.complex.single.MineComplexSingleStageN;
import org.six_coin.playerController.client.mine.complex.single.SingleStageDetail;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.six_coin.playerController.client.commands.CommandStyle.fillButton;
import static org.six_coin.playerController.client.commands.CommandStyle.gray;
import static org.six_coin.playerController.client.commands.CommandStyle.runButton;

/**
 * {@code /pc mine ...} —— 挖掘。
 *
 * <pre>
 * /pc mine                                   看任务、过滤列表、范围
 * /pc mine cancel                            取消所有挖掘任务
 * /pc mine simple &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;dim&gt;
 *                                            在框里挖方块（用 mine/mode.json 选的那张表）
 *
 * /pc mine list                              看模式、两张列表、文件
 * /pc mine list mode [whitelist|blacklist]   换模式
 * /pc mine list whitelist list               列出白名单里的方块（名字 + id）
 * /pc mine list whitelist add &lt;block_id&gt;
 * /pc mine list whitelist del &lt;block_id&gt;
 * /pc mine list blacklist list|add|del …     黑名单同理
 *
 * /pc mine station ...                       挖掘站（见 MineStationCommand）
 *
 * /pc mine complex add_task &lt;task_name&gt;      建一个复杂挖掘任务（mine.json + mine/&lt;task&gt;/region.json）
 * /pc mine complex del_task &lt;task_name&gt;      删任务（整个 mine/&lt;task&gt;/ 目录一起删）
 * /pc mine task &lt;task_name&gt; a_set_region &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;dim&gt;
 *                                            写 region.json（范围原样记下，inspected = false）
 * /pc mine task &lt;task_name&gt; b_inspect_region  检查这个任务的挖掘区
 * /pc mine task &lt;task_name&gt; c_station_check   跑一遍 /pc mine station check，通过就写 checked: true
 *                                            （{@code /pc mine complex task <task_name> …} 是同一套）
 * </pre>
 *
 * <p>simple 的范围会记进 {@code world_<n>/mine.json}，重新进世界自动接回来
 * （见 {@link MineManager#resumeSaved()}）。复杂任务的清单也在这个文件里，
 * 每个任务的挖掘区单独放在 {@code world_<n>/mine/<task>/region.json}。
 *
 * <p>过滤器是三份文件（{@link MineFilterConfig}）：{@code mine/mode.json} 决定用哪张表，
 * {@code whitelist.json} / {@code blacklist.json} 是方块 id 数组。开任务时取一份快照，
 * 之后改列表不会影响正在跑的这一个任务。
 */
public final class MineCommand {

    private static final String ROOT = "/pc mine ";

    private MineCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("mine")
            .executes(MineCommand::status)
            .then(ClientCommands.literal("cancel")
                .executes(MineCommand::cancel))
            .then(simple())
            .then(lists())
            .then(MineStationCommand.build())
            .then(taskCommand())
            .then(ClientCommands.literal("debug_chunk_miner")
                .then(ClientCommands.argument("detail", StringArgumentType.greedyString())
                    .executes(MineCommand::debugChunkMiner)))
            .then(ClientCommands.literal("complex")
                .executes(MineCommand::complexStatus)
                .then(addTask())
                .then(delTask())
                .then(taskCommand()));
    }

    // ------------------------------------------------------------------
    // simple
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> simple() {
        // brigadier 的 then(builder) 会立刻 build 出节点，所以要从里往外挂
        RequiredArgumentBuilder<FabricClientCommandSource, Identifier> dim =
            ClientCommands.argument("dim", IdentifierArgument.id())
                .suggests(DimensionSuggestions::suggest)
                .executes(MineCommand::start);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z2 =
            ClientCommands.argument("z2", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        z2.then(dim);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y2 =
            ClientCommands.argument("y2", IntegerArgumentType.integer()).suggests(LookSuggestions::y);
        y2.then(z2);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x2 =
            ClientCommands.argument("x2", IntegerArgumentType.integer()).suggests(LookSuggestions::x);
        x2.then(y2);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z1 =
            ClientCommands.argument("z1", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        z1.then(x2);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y1 =
            ClientCommands.argument("y1", IntegerArgumentType.integer()).suggests(LookSuggestions::y);
        y1.then(z1);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x1 =
            ClientCommands.argument("x1", IntegerArgumentType.integer()).suggests(LookSuggestions::x);
        x1.then(y1);

        return ClientCommands.literal("simple").then(x1);
    }

    private static int start(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null || source.getLevel() == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        BlockPos first = new BlockPos(
            IntegerArgumentType.getInteger(context, "x1"),
            IntegerArgumentType.getInteger(context, "y1"),
            IntegerArgumentType.getInteger(context, "z1"));
        BlockPos second = new BlockPos(
            IntegerArgumentType.getInteger(context, "x2"),
            IntegerArgumentType.getInteger(context, "y2"),
            IntegerArgumentType.getInteger(context, "z2"));

        Identifier dimensionId = context.getArgument("dim", Identifier.class);
        if (dimensionId == null) {
            source.sendError(Component.literal("没有写维度"));
            return 0;
        }
        String dimension = dimensionId.toString();

        MineFilter filter = MineFilterConfig.get().filter();
        MineRegion region = MineRegion.of(dimension, first, second, filter);
        boolean here = region.dimension().equals(DimensionUtils.current());
        MineManager.get().start(region);

        source.sendFeedback(Component.literal("已开始挖掘：simple " + region.describe()
            + "，一共 " + region.volume() + " 格"));
        source.sendFeedback(Component.literal("§7  触及 " + PlayerControllerConfig.getReach()
            + " 格内；每 tick 最多 " + PlayerControllerConfig.getMineMaxBlocksPerTick()
            + " 个方块；取消：§f" + ROOT + "cancel"));
        if (filter.mode() == MineFilter.Mode.WHITELIST && filter.blocks().isEmpty()) {
            source.sendFeedback(Component.literal("§e  白名单现在是空的，什么都不会挖"
                + "（/pc mine list whitelist add <block_id>）"));
        }
        if (!here) {
            source.sendFeedback(Component.literal("§7  现在不在这个维度（现在是 "
                + DimensionUtils.display(DimensionUtils.current()) + "），进去之后才会开始挖"));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // list（过滤器）
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> lists() {
        return ClientCommands.literal("list")
            .executes(MineCommand::listStatus)
            .then(ClientCommands.literal("mode")
                .executes(MineCommand::showMode)
                .then(ClientCommands.literal("whitelist")
                    .executes(context -> setMode(context, MineFilter.Mode.WHITELIST)))
                .then(ClientCommands.literal("blacklist")
                    .executes(context -> setMode(context, MineFilter.Mode.BLACKLIST))))
            .then(blockList(MineFilter.Mode.WHITELIST))
            .then(blockList(MineFilter.Mode.BLACKLIST));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> blockList(MineFilter.Mode which) {
        return ClientCommands.literal(which.id())
            .executes(context -> showList(context, which))
            .then(ClientCommands.literal("list")
                .executes(context -> showList(context, which)))
            .then(ClientCommands.literal("add")
                .then(ClientCommands.argument("block_id", IdentifierArgument.id())
                    .suggests(MineCommand::suggestBlocks)
                    .executes(context -> addBlock(context, which))))
            .then(ClientCommands.literal("del")
                .then(ClientCommands.argument("block_id", IdentifierArgument.id())
                    .suggests(MineCommand::suggestBlocks)
                    .executes(context -> delBlock(context, which))));
    }

    private static CompletableFuture<Suggestions> suggestBlocks(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);
    }

    private static int listStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MineFilterConfig config = MineFilterConfig.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7挖掘方块列表（" + MineFilterConfig.dir() + "）："));
        source.sendFeedback(Component.literal("  当前模式：" + config.mode().label()
            + "（生效的是 " + config.mode().id() + ".json）")
            .append(fillButton(" [换成" + other(config.mode()).label() + "]",
                ROOT + "list mode " + other(config.mode()).id(),
                "把「换模式」的命令填进聊天栏，回车生效")));
        source.sendFeedback(Component.literal("  白名单：").append(listSummary(MineFilter.Mode.WHITELIST)));
        source.sendFeedback(Component.literal("  黑名单：").append(listSummary(MineFilter.Mode.BLACKLIST)));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "list mode whitelist|blacklist§7 - 换模式"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "list whitelist|blacklist list§7 - 列出方块（名字 + id）"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "list whitelist|blacklist add|del <block_id>§7 - 加 / 删方块"));
        source.sendFeedback(Component.literal("§7  文件: mode.json / whitelist.json / blacklist.json（直接改也行，重进世界时重读）"));
        return 1;
    }

    private static int showMode(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MineFilterConfig config = MineFilterConfig.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7挖掘过滤器模式：" + config.mode().label()
            + "（" + config.mode().id() + "）"));
        source.sendFeedback(Component.literal("  文件：" + MineFilterConfig.modeFile()));
        source.sendFeedback(Component.literal("  现在生效的表："
            + (config.mode() == MineFilter.Mode.WHITELIST ? "白名单" : "黑名单")
            + "（" + config.blocks(config.mode()).size() + " 种）"));
        return 1;
    }

    private static int setMode(CommandContext<FabricClientCommandSource> context, MineFilter.Mode mode) {
        FabricClientCommandSource source = context.getSource();
        MineFilter.Mode before = MineFilterConfig.get().mode();
        MineFilterConfig.get().setMode(mode);

        source.sendFeedback(Component.literal("挖掘过滤器模式：" + before.label() + " -> " + mode.label()
            + "（文件 " + MineFilterConfig.modeFile() + "）"));
        if (mode == MineFilter.Mode.WHITELIST && MineFilterConfig.get().blocks(mode).isEmpty()) {
            source.sendFeedback(Component.literal("§e  白名单是空的，现在 /pc mine simple 什么都不会挖"
                + "（/pc mine list whitelist add <block_id>）"));
        }
        return 1;
    }

    private static int showList(CommandContext<FabricClientCommandSource> context, MineFilter.Mode which) {
        FabricClientCommandSource source = context.getSource();
        MineFilterConfig config = MineFilterConfig.get();
        List<Block> blocks = new ArrayList<>(config.blocks(which));

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7" + which.label() + "（" + blocks.size()
            + " 种，文件 " + fileFor(which) + "）："));
        if (blocks.isEmpty()) {
            source.sendFeedback(Component.literal("  §8（空）"));
        } else {
            for (Block block : blocks) {
                MutableComponent line = Component.literal(" ");
                line.append(fillButton("[删除]", ROOT + "list " + which.id() + " del "
                        + BuiltInRegistries.BLOCK.getKey(block), "把「从" + which.label() + "删掉这个方块」填进聊天栏"));
                line.append(Component.literal(" ").append(blockLabel(block)));
                source.sendFeedback(line);
            }
        }
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "list " + which.id()
            + " add <block_id>§7 - 加一个方块；§f" + ROOT + "list " + which.id() + " del <block_id>§7 - 删一个"));
        return 1;
    }

    private static int addBlock(CommandContext<FabricClientCommandSource> context, MineFilter.Mode which) {
        FabricClientCommandSource source = context.getSource();
        Block block = readBlock(context);
        if (block == null) {
            source.sendError(Component.literal("没有这个方块：" + context.getArgument("block_id", Identifier.class)));
            return 0;
        }

        if (!MineFilterConfig.get().add(which, block)) {
            source.sendFeedback(Component.literal(which.label() + "里本来就有 ").append(blockLabel(block)));
            return 1;
        }
        source.sendFeedback(Component.literal("已加入" + which.label() + "：").append(blockLabel(block))
            .append(Component.literal("（现在 " + MineFilterConfig.get().blocks(which).size() + " 种，文件 "
                + fileFor(which) + "）")));
        return 1;
    }

    private static int delBlock(CommandContext<FabricClientCommandSource> context, MineFilter.Mode which) {
        FabricClientCommandSource source = context.getSource();
        Block block = readBlock(context);
        if (block == null) {
            source.sendError(Component.literal("没有这个方块：" + context.getArgument("block_id", Identifier.class)));
            return 0;
        }

        if (!MineFilterConfig.get().remove(which, block)) {
            source.sendError(Component.literal(which.label() + "里本来就没有 ").append(blockLabel(block)));
            return 0;
        }
        source.sendFeedback(Component.literal("已从" + which.label() + "删掉 ").append(blockLabel(block))
            .append(Component.literal("（现在 " + MineFilterConfig.get().blocks(which).size() + " 种）")));
        return 1;
    }

    private static Block readBlock(CommandContext<FabricClientCommandSource> context) {
        Identifier id = context.getArgument("block_id", Identifier.class);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    /** 「石头 (minecraft:stone)」：名字 + 灰色可复制的 id。 */
    private static MutableComponent blockLabel(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return Component.literal(block.getName().getString() + " ")
            .append(gray("(" + id + ")", id.toString()));
    }

    private static MutableComponent listSummary(MineFilter.Mode which) {
        MineFilterConfig config = MineFilterConfig.get();
        int count = config.blocks(which).size();
        MutableComponent text = Component.literal(which.label() + " " + count + " 种"
            + (count == 0 ? "（空）" : ""));
        if (config.mode() == which) text.append(Component.literal(" §a（生效中）"));
        return text;
    }

    private static Path fileFor(MineFilter.Mode which) {
        return which == MineFilter.Mode.WHITELIST
            ? MineFilterConfig.whitelistFile()
            : MineFilterConfig.blacklistFile();
    }

    private static MineFilter.Mode other(MineFilter.Mode mode) {
        return mode == MineFilter.Mode.WHITELIST ? MineFilter.Mode.BLACKLIST : MineFilter.Mode.WHITELIST;
    }

    // ------------------------------------------------------------------
    // 总览 / 取消 / complex
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MineManager manager = MineManager.get();
        MineRegion region = manager.region();
        MineFilterConfig config = MineFilterConfig.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7挖掘（一直挖到 /pc mine cancel 为止）："));
        if (region == null) {
            source.sendFeedback(Component.literal("  当前没有任务"));
        } else {
            source.sendFeedback(Component.literal("  任务：simple " + region.describe()));
            source.sendFeedback(Component.literal("  一共 " + region.volume() + " 格"));
            source.sendFeedback(Component.literal("  状态：" + manager.state().text()
                + (manager.target() == null ? "" : "（目标 " + manager.target().toShortString()
                    + (manager.targetTool() == null ? "，什么工具都行" : "，用" + manager.targetTool().label())
                    + "）")));
            if (manager.lastTickCount() > 0) {
                source.sendFeedback(Component.literal("  上一 tick 挖了 " + manager.lastTickCount() + " 个"));
            }

            MutableComponent line = Component.literal("  ");
            line.append(runButton("[取消]", ROOT + "cancel", "直接取消所有挖掘任务"));
            source.sendFeedback(line);
        }

        source.sendFeedback(Component.literal("  过滤器：模式 " + config.mode().label()
            + "，白名单 " + config.blocks(MineFilter.Mode.WHITELIST).size() + " 种，黑名单 "
            + config.blocks(MineFilter.Mode.BLACKLIST).size() + " 种（/pc mine list）"));
        source.sendFeedback(Component.literal("  范围：触及 " + PlayerControllerConfig.getReach()
            + " 格；每 tick 最多 "
            + PlayerControllerConfig.getMineMaxBlocksPerTick() + " 个方块；换工具后等 "
            + PlayerControllerConfig.getMineToolSwitchIntervalTicks() + " tick"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "simple <x1> <y1> <z1> <x2> <y2> <z2> <dim>§7 - 在框里挖方块"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "cancel§7 - 取消所有挖掘任务"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "list ...§7 - 白名单 / 黑名单（mode、add、del、list）"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "station ...§7 - 挖掘站（list / check / show / 各处坐标）"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "complex add_task|del_task <task_name>§7 - 建 / 删复杂挖掘任务"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "task <task_name> a_set_region <x1> <y1> <z1> <x2> <y2> <z2> <dim>§7 - 写挖掘区"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "task <task_name> b_inspect_region§7 - 检查挖掘区（路径点 / 安全点 / 区块里的方块）"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "task <task_name> c_station_check§7 - 跑挖掘站检查，通过就写 checked: true"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "debug_chunk_miner <detail>§7 - §c【临时调试】§7挖掉下面那个子区块（detail 是必填的 JSON）"));
        source.sendFeedback(Component.literal("§7    §8例：§f{\"item_collector_list\":[\"minecraft:coal\"],"
            + "\"north\":\"cobblestone\",\"south\":\"cobblestone\",\"west\":\"auto\",\"east\":\"auto\","
            + "\"bottom\":\"auto\",\"levels\":8}"));
        source.sendFeedback(Component.literal("§7    §8五个方向只有 cobblestone / auto 两种；levels = 挖几层（1~8，实际挖 2×levels 格）"));
        source.sendFeedback(Component.literal("§7  范围记在 mine.json 里，重新进世界会接着挖（/pc mine cancel 才会清掉）"));
        return 1;
    }

    private static int cancel(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (MineManager.get().cancel()) {
            source.sendFeedback(Component.literal("已取消所有挖掘任务"));
        } else {
            source.sendFeedback(Component.literal("当前没有挖掘任务"));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // b_inspect_region
    // ------------------------------------------------------------------

    /**
     * {@code /pc mine task <task_name> b_inspect_region}：检查这个任务的挖掘区。
     *
     * <p>任务名是必填的（写在 {@code task} 那一层）。
     */
    private static int inspectRegion(CommandContext<FabricClientCommandSource> context, String name) {
        FabricClientCommandSource source = context.getSource();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        MineComplexManager manager = MineComplexManager.get();

        if (!manager.has(name)) {
            source.sendError(Component.literal("没有这个挖掘任务：" + name
                + "（先 " + ROOT + "complex add_task " + name + "）"));
            return 0;
        }

        MineTaskRegion region = manager.region(name);
        if (region == null) {
            source.sendError(Component.literal("挖掘任务 " + name + " 还没设区域（"
                + ROOT + "task " + name + " a_set_region …）"));
            return 0;
        }

        ActionManager.get().submit(new MineRegionInspectAction(name, region));
        source.sendFeedback(Component.literal("开始检查挖掘区 §f" + name + "§r：" + region.describe()));
        source.sendFeedback(Component.literal("§7  先查路径点（入口 + 安全点 + 区域里不能有路），"
            + "再逐个区块扫 y " + region.min().getY() + " ~ " + region.max().getY()
            + "（没加载的区块会等着）"));
        source.sendFeedback(Component.literal("§e提醒：开始挖之前先做伪和平（把刷怪压住），"
            + "否则会经常被怪物打断 —— 效率降低、图腾消耗变多、风险提高。"));
        return 1;
    }

    /**
     * {@code /pc mine task <task_name> c_station_check}：跑一遍 {@code /pc mine station check}，
     * 通过的话把 {@code "checked": true} 写进这个任务的 {@code region.json}。
     *
     * <p>前置要求：{@code region.json} 里 {@code inspected} 必须是 {@code true}
     * （先跑 {@code b_inspect_region}）。
     */
    private static int stationCheck(CommandContext<FabricClientCommandSource> context, String name) {
        FabricClientCommandSource source = context.getSource();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        MineComplexManager manager = MineComplexManager.get();
        if (!manager.has(name)) {
            source.sendError(Component.literal("没有这个挖掘任务：" + name
                + "（先 " + ROOT + "complex add_task " + name + "）"));
            return 0;
        }

        MineTaskRegion region = manager.region(name);
        if (region == null) {
            source.sendError(Component.literal("挖掘任务 " + name + " 还没设区域（"
                + ROOT + "task " + name + " a_set_region …）"));
            return 0;
        }

        if (!region.inspected()) {
            source.sendError(Component.literal("任务 " + name + " 的 region.json 里 inspected 还不是 true"
                + " —— 先跑 " + ROOT + "task " + name + " b_inspect_region"));
            return 0;
        }

        ActionManager.get().submit(new MineStationCheckAction(name));
        source.sendFeedback(Component.literal("开始检查挖掘站（任务 §f" + name + "§r）：先给每个容器算落点，"
            + "再按路径图一个接一个走过去开箱（不显示界面，会走很久）"));
        source.sendFeedback(Component.literal("§7  通过的话会把 checked: true 写进 "
            + manager.currentRegionFile(name) + "；没通过就不写"));
        return 1;
    }

    // ------------------------------------------------------------------
    // debug_chunk_miner
    // ------------------------------------------------------------------

    /**
     * {@code /pc mine debug_chunk_miner <detail>}：挖掉当前子区块正下方那个子区块。
     *
     * <p><b>临时的调试功能</b>（以后会删）：需求里的单段挖掘，实现在
     * {@link MineComplexSingleStageN}（{@code mine/complex/single/} 下）。
     *
     * @param detail 必填的 JSON，格式见 {@link SingleStageDetail}：
     *               <pre>
     * {"item_collector_list": ["minecraft:coal"],
     *  "north": "cobblestone", "south": "cobblestone",
     *  "west": "auto", "east": "auto", "bottom": "auto",
     *  "levels": 8}
     *               </pre>
     */
    private static int debugChunkMiner(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }

        SingleStageDetail detail;
        try {
            detail = SingleStageDetail.parse(StringArgumentType.getString(context, "detail"));
        } catch (IllegalArgumentException e) {
            source.sendError(Component.literal("detail 解析失败：" + e.getMessage()));
            return 0;
        }

        List<String> unknown = new ArrayList<>();
        for (String id : detail.itemCollectorList()) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier == null || BuiltInRegistries.ITEM.getOptional(identifier).isEmpty()) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            source.sendError(Component.literal("没有这些物品：" + String.join("、", unknown)));
            return 0;
        }

        ActionManager.get().submit(new MineComplexSingleStageN(detail));
        source.sendFeedback(Component.literal("已提交单段挖掘：挖掉下面那个子区块（"
            + detail.describe() + "）"));
        return 1;
    }

    private static int complexStatus(CommandContext<FabricClientCommandSource> context) {
        showTasks(context.getSource());
        return 1;
    }

    // ------------------------------------------------------------------
    // complex：任务清单
    // ------------------------------------------------------------------

    private static void showTasks(FabricClientCommandSource source) {
        MineComplexManager manager = MineComplexManager.get();
        List<String> tasks = manager.tasks();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7复杂挖掘任务（" + tasks.size() + " 个）："
            + (tasks.isEmpty() ? "§8（空）" : String.join("、", tasks))));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "complex add_task|del_task <task_name>§7 - 建 / 删任务"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "task <task_name> a_set_region <x1> <y1> <z1> <x2> <y2> <z2> <dim>§7 - 写挖掘区"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "task <task_name> b_inspect_region§7 - 检查挖掘区"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT
            + "task <task_name> c_station_check§7 - 跑挖掘站检查，通过就写 checked: true"));
        source.sendFeedback(Component.literal("§7    §8（`complex task <task_name> ...` 是同一套，两种写法都行）"));
        source.sendFeedback(Component.literal("  文件: " + manager.currentFile()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> addTask() {
        return ClientCommands.literal("add_task")
            .then(ClientCommands.argument("task_name", StringArgumentType.word())
                .executes(MineCommand::addTask));
    }

    private static int addTask(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "task_name");

        MineComplexManager manager = MineComplexManager.get();
        String error = manager.addTask(name);
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }

        source.sendFeedback(Component.literal("已创建挖掘任务 §f" + name + "§r（现在一共 "
            + manager.taskCount() + " 个）"));
        source.sendFeedback(Component.literal("  目录: " + manager.currentTaskDir(name)
            + "（region.json 现在是空的 {}）"));
        source.sendFeedback(Component.literal("§7  下一步：§f" + ROOT + "task " + name
            + " a_set_region <x1> <y1> <z1> <x2> <y2> <z2> <dim>"));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> delTask() {
        return ClientCommands.literal("del_task")
            .then(ClientCommands.argument("task_name", StringArgumentType.word())
                .suggests(MineCommand::suggestTasks)
                .executes(MineCommand::delTask));
    }

    private static int delTask(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "task_name");

        MineComplexManager manager = MineComplexManager.get();
        Path dir = manager.currentTaskDir(name);
        String error = manager.delTask(name);
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }

        source.sendFeedback(Component.literal("已删除挖掘任务 §f" + name + "§r（目录整个删掉了："
            + dir + "），现在还剩 " + manager.taskCount() + " 个"));
        return 1;
    }

    /**
     * {@code /pc mine complex task <task_name> ...}：不带子命令看这个任务的区域，
     * 带 {@code a_set_region} 就重写 region.json。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> taskCommand() {
        // brigadier 的 then(builder) 会立刻 build 出节点，所以要从里往外挂
        RequiredArgumentBuilder<FabricClientCommandSource, Identifier> dim =
            ClientCommands.argument("dim", IdentifierArgument.id())
                .suggests(DimensionSuggestions::suggest)
                .executes(MineCommand::setRegion);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z2 =
            ClientCommands.argument("z2", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        z2.then(dim);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y2 =
            ClientCommands.argument("y2", IntegerArgumentType.integer()).suggests(LookSuggestions::y);
        y2.then(z2);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x2 =
            ClientCommands.argument("x2", IntegerArgumentType.integer()).suggests(LookSuggestions::x);
        x2.then(y2);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z1 =
            ClientCommands.argument("z1", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        z1.then(x2);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y1 =
            ClientCommands.argument("y1", IntegerArgumentType.integer()).suggests(LookSuggestions::y);
        y1.then(z1);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x1 =
            ClientCommands.argument("x1", IntegerArgumentType.integer()).suggests(LookSuggestions::x);
        x1.then(y1);

        return ClientCommands.literal("task")
            .then(ClientCommands.argument("task_name", StringArgumentType.word())
                .suggests(MineCommand::suggestTasks)
                .executes(MineCommand::taskStatus)
                .then(ClientCommands.literal("a_set_region").then(x1))
                .then(ClientCommands.literal("b_inspect_region")
                    .executes(context -> inspectRegion(context,
                        StringArgumentType.getString(context, "task_name"))))
                .then(ClientCommands.literal("c_station_check")
                    .executes(context -> stationCheck(context,
                        StringArgumentType.getString(context, "task_name")))));
    }

    /** 补全 mine.json 里现有的任务名。 */
    private static CompletableFuture<Suggestions> suggestTasks(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(MineComplexManager.get().tasks(), builder);
    }

    private static int taskStatus(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "task_name");

        MineComplexManager manager = MineComplexManager.get();
        if (!manager.has(name)) {
            source.sendError(Component.literal("没有这个挖掘任务：" + name
                + "（先 " + ROOT + "complex add_task " + name + "）"));
            return 0;
        }

        MineTaskRegion region = manager.region(name);
        if (region == null) {
            source.sendFeedback(Component.literal("挖掘任务 " + name + " 还没设区域（"
                + ROOT + "task " + name + " a_set_region …）"));
            source.sendFeedback(Component.literal("  文件: " + manager.currentRegionFile(name)));
            return 1;
        }

        source.sendFeedback(Component.literal("挖掘任务 " + name + "：" + region.describe()));
        source.sendFeedback(Component.literal("  范围: " + region.min().toShortString() + " ~ "
            + region.max().toShortString() + "（" + region.volume() + " 格，"
            + region.chunkCount() + " 个区块）"));
        source.sendFeedback(Component.literal("  入口（x1, y2+1, z1）: "
            + region.headPos().toShortString()));
        source.sendFeedback(Component.literal("  inspected: " + region.inspected()
            + "，checked: " + region.checked()));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "task " + name
            + " b_inspect_region§7 - 检查这个区域"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "task " + name
            + " c_station_check§7 - 跑挖掘站检查（要先 inspected: true）"));
        source.sendFeedback(Component.literal("  文件: " + manager.currentRegionFile(name)));
        return 1;
    }

    private static int setRegion(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "task_name");

        MineComplexManager manager = MineComplexManager.get();
        if (!manager.has(name)) {
            source.sendError(Component.literal("没有这个挖掘任务：" + name
                + "（先 " + ROOT + "complex add_task " + name + "）"));
            return 0;
        }

        Identifier dimension = context.getArgument("dim", Identifier.class);
        if (dimension == null) {
            source.sendError(Component.literal("没有写维度"));
            return 0;
        }

        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");

        String dim = dimension.toString();
        int maxY = DimensionUtils.buildMaxY(dim);

        // 不再由机器校准：输入必须自己就满足规则，不满足就原样报错，不做任何「帮你排好序」的事
        List<String> problems = MineTaskRegion.validate(dim, x1, y1, z1, x2, y2, z2, maxY);
        if (!problems.isEmpty()) {
            source.sendError(Component.literal("区域不合法（" + problems.size() + " 条）："));
            for (String problem : problems) source.sendError(Component.literal("  · " + problem));
            source.sendFeedback(Component.literal("§7  规则：x1<x2、y1<y2、z1<z2；y1 是偶数；(y2+1) 是 16 的倍数；"
                + "y2-y1 >= " + MineTaskRegion.MIN_Y_SPAN + "；y2+" + MineTaskRegion.HEAD_ROOM
                + " <= 建筑高度限制（" + DimensionUtils.display(dim) + " 是 " + maxY + "）；"
                + "x1、z1 是 16 的倍数，x2+1、z2+1 是 16 的倍数"));
            return 0;
        }

        MineTaskRegion region = MineTaskRegion.of(dim, x1, y1, z1, x2, y2, z2);

        Path file = manager.currentRegionFile(name);
        try {
            region.save(file);
        } catch (IOException e) {
            source.sendError(Component.literal("写 region.json 失败：" + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Component.literal("已写入任务 §f" + name + "§r 的挖掘区：" + region.describe()));
        source.sendFeedback(Component.literal("  范围: " + region.min().toShortString() + " ~ "
            + region.max().toShortString() + "（" + region.volume() + " 格，"
            + region.chunkCount() + " 个区块）"));
        source.sendFeedback(Component.literal("  入口（x1, y2+1, z1）: " + region.headPos().toShortString()));
        source.sendFeedback(Component.literal("§7  下一步：§f" + ROOT + "task " + name
            + " b_inspect_region§7；文件：" + file));        return 1;
    }
}
