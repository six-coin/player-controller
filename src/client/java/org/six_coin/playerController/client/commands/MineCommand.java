package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.mine.MineFilter;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.mine.MineRegion;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.concurrent.CompletableFuture;

import static org.six_coin.playerController.client.commands.CommandStyle.runButton;

/**
 * {@code /pc mine ...} —— 在指定区域里挖方块（只在区域里挖的 nuker）。
 *
 * <pre>
 * /pc mine                       看现在的任务、范围、状态
 * /pc mine cancel                取消所有挖掘任务
 * /pc mine simple &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;dim&gt; [list]
 *                                在 [x1,y1,z1] ~ [x2,y2,z2] 这个框里挖方块
 *                                list 写成 {"mode": "whitelist", "list": ["minecraft:dirt"]}；
 *                                不写 = {"mode": "blacklist", "list": []}，即所有方块都挖
 * </pre>
 *
 * <p>两个角谁大谁小都行。挖是一直挖到 {@code cancel} 为止的，区域挖空了也只是停着。
 */
public final class MineCommand {

    private static final String ROOT = "/pc mine ";

    private MineCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("mine")
            .executes(MineCommand::status)
            .then(ClientCommandManager.literal("cancel")
                .executes(MineCommand::cancel))
            .then(simple());
    }

    // ------------------------------------------------------------------
    // simple
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> simple() {
        return ClientCommandManager.literal("simple")
            .then(ClientCommandManager.argument("x1", IntegerArgumentType.integer())
                .suggests(LookSuggestions::x)
                .then(ClientCommandManager.argument("y1", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::y)
                    .then(ClientCommandManager.argument("z1", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::z)
                        .then(ClientCommandManager.argument("x2", IntegerArgumentType.integer())
                            .suggests((context, builder) -> suggestCorner(context, builder, Direction.Axis.X))
                            .then(ClientCommandManager.argument("y2", IntegerArgumentType.integer())
                                .suggests((context, builder) -> suggestCorner(context, builder, Direction.Axis.Y))
                                .then(ClientCommandManager.argument("z2", IntegerArgumentType.integer())
                                    .suggests((context, builder) -> suggestCorner(context, builder, Direction.Axis.Z))
                                    .then(ClientCommandManager.argument("dim", IdentifierArgumentType.identifier())
                                        .suggests(DimensionSuggestions::suggest)
                                        .executes(MineCommand::startDefault)
                                        .then(ClientCommandManager.argument("list",
                                                StringArgumentType.greedyString())
                                            .suggests(MineCommand::suggestList)
                                            .executes(MineCommand::startWithList)))))))));
    }

    /** 第二个角：一个分量一个分量地补，用准星看到的方块（没看到就用脚下这一格）。 */
    private static CompletableFuture<Suggestions> suggestCorner(CommandContext<FabricClientCommandSource> context,
                                                                SuggestionsBuilder builder, Direction.Axis axis) {
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) pos = PlayerUtils.currentBlockPos();
        if (pos != null) {
            builder.suggest(Integer.toString(axis.choose(pos.getX(), pos.getY(), pos.getZ())));
        }
        return builder.buildFuture();
    }

    /** {@code list} 参数：给两段现成的模板。 */
    private static CompletableFuture<Suggestions> suggestList(CommandContext<FabricClientCommandSource> context,
                                                              SuggestionsBuilder builder) {
        builder.suggest("{\"mode\": \"whitelist\", \"list\": [\"minecraft:dirt\"]}");
        builder.suggest("{\"mode\": \"blacklist\", \"list\": []}");
        return builder.buildFuture();
    }

    private static int startDefault(CommandContext<FabricClientCommandSource> context) {
        return start(context, MineFilter.ALL);
    }

    private static int startWithList(CommandContext<FabricClientCommandSource> context) {
        MineFilter filter;
        try {
            filter = MineFilter.parse(StringArgumentType.getString(context, "list"));
        } catch (IllegalArgumentException e) {
            context.getSource().sendError(Text.literal("list 不对：" + e.getMessage()));
            return 0;
        }
        return start(context, filter);
    }

    private static int start(CommandContext<FabricClientCommandSource> context, MineFilter filter) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null || source.getWorld() == null) {
            source.sendError(Text.literal("没有玩家或世界"));
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

        String dimension = readDimension(context);
        if (dimension == null) {
            source.sendError(Text.literal("没有写维度"));
            return 0;
        }

        MineRegion region = MineRegion.of(dimension, first, second, filter);
        boolean here = region.dimension().equals(DimensionUtils.current());
        MineManager.get().start(region);

        source.sendFeedback(Text.literal("已开始挖掘：simple " + region.describe()
            + "，一共 " + region.volume() + " 格"));
        source.sendFeedback(Text.literal("§7  看得见的 " + PlayerControllerConfig.getMineSimpleRange()
            + " 格内、隔墙的 " + PlayerControllerConfig.getMineSimpleWallRange()
            + " 格内；每 tick 最多 " + PlayerControllerConfig.getMineSimpleMaxBlocksPerTick()
            + " 个方块；取消：§f" + ROOT + "cancel"));
        if (!here) {
            source.sendFeedback(Text.literal("§7  现在不在这个维度（现在是 "
                + DimensionUtils.display(DimensionUtils.current()) + "），进去之后才会开始挖"));
        }
        return 1;
    }

    /**
     * 维度 id。
     *
     * <p>参数类型用的是原版的 {@code IdentifierArgumentType} 而不是 {@code StringArgumentType.word()}：
     * word 只吃 {@code [a-zA-Z0-9_]}，会在 {@code minecraft:overworld} 的冒号那里断掉。
     * {@code IdentifierArgumentType.getIdentifier(...)} 只收服务端命令源，这里直接 {@code getArgument} 拿。
     */
    private static String readDimension(CommandContext<FabricClientCommandSource> context) {
        Identifier dim = context.getArgument("dim", Identifier.class);
        return dim == null ? null : dim.toString();
    }

    // ------------------------------------------------------------------
    // 总览 / 取消
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MineManager manager = MineManager.get();
        MineRegion region = manager.region();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7挖掘（一直挖到 /pc mine cancel 为止）："));
        if (region == null) {
            source.sendFeedback(Text.literal("  当前没有任务"));
        } else {
            source.sendFeedback(Text.literal("  任务：simple " + region.describe()));
            source.sendFeedback(Text.literal("  一共 " + region.volume() + " 格"));
            source.sendFeedback(Text.literal("  状态：" + manager.state().text()
                + (manager.target() == null ? "" : "（目标 " + manager.target().toShortString()
                    + (manager.targetTool() == null ? "，什么工具都行" : "，用" + manager.targetTool().label())
                    + "）")));
            if (manager.lastTickCount() > 0) {
                source.sendFeedback(Text.literal("  上一 tick 挖了 " + manager.lastTickCount() + " 个"));
            }

            MutableText line = Text.literal("  ");
            line.append(runButton("[取消]", ROOT + "cancel", "直接取消所有挖掘任务"));
            source.sendFeedback(line);
        }

        source.sendFeedback(Text.literal("  范围：看得见的 " + PlayerControllerConfig.getMineSimpleRange()
            + " 格 / 隔墙的 " + PlayerControllerConfig.getMineSimpleWallRange() + " 格"));
        source.sendFeedback(Text.literal("  每 tick 最多：" + PlayerControllerConfig.getMineSimpleMaxBlocksPerTick()
            + " 个方块"));
        source.sendFeedback(Text.literal("§7  §f" + ROOT
            + "simple <x1> <y1> <z1> <x2> <y2> <z2> <dim> [list]§7 - 在框里挖方块"));
        source.sendFeedback(Text.literal("§7  §f" + ROOT + "cancel§7 - 取消所有挖掘任务"));
        source.sendFeedback(Text.literal("§7  list 例：§f{\"mode\": \"whitelist\", \"list\": "
            + "[\"minecraft:dirt\"]}§7；不写 = 所有方块都挖"));
        return 1;
    }

    private static int cancel(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (MineManager.get().cancel()) {
            source.sendFeedback(Text.literal("已取消所有挖掘任务"));
        } else {
            source.sendFeedback(Text.literal("当前没有挖掘任务"));
        }
        return 1;
    }
}
