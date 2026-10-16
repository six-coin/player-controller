package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.waypoint.WaypointManager;

/**
 * {@code /pc config ...} —— 热改配置。
 *
 * <pre>
 * /pc config debug [true|false]
 * /pc config world [num]
 * /pc config actions move_speed [speed]
 * /pc config actions max_descent_speed [speed]
 * /pc config actions interaction_range [range]
 * /pc config actions anti_kick_delay [tick]
 * /pc config actions anti_kick_off_time [tick]
 * /pc config actions items_per_tick [1~64]
 * /pc config actions wait_ticks_after_dimension_teleport [tick]
 * </pre>
 */
public final class ConfigCommand {

    private ConfigCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("config")
            .executes(ConfigCommand::showAll)
            .then(debug())
            .then(world())
            .then(actions("actions"));
    }

    // ------------------------------------------------------------------
    // debug / world
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> debug() {
        return ClientCommandManager.literal("debug")
            .executes(context -> setDebug(context, !PlayerControllerConfig.isDebug()))
            .then(ClientCommandManager.literal("true")
                .executes(context -> setDebug(context, true)))
            .then(ClientCommandManager.literal("false")
                .executes(context -> setDebug(context, false)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> world() {
        return ClientCommandManager.literal("world")
            .executes(context -> {
                context.getSource().sendFeedback(Text.literal(
                    "当前世界配置: world_" + PlayerControllerConfig.getWorld()
                        + "（" + PlayerControllerConfig.configDir()
                        .resolve("world_" + PlayerControllerConfig.getWorld()) + "）"));
                return 1;
            })
            .then(ClientCommandManager.argument("num", IntegerArgumentType.integer(1))
                .executes(context -> {
                    int num = IntegerArgumentType.getInteger(context, "num");
                    if (num == PlayerControllerConfig.getWorld()) {
                        context.getSource().sendFeedback(Text.literal("已经是 world_" + num + " 了"));
                        return 1;
                    }
                    WaypointManager.get().switchWorld(num);
                    ContainerCacheManager.get().switchWorld(num);
                    StationManager.get().switchWorld(num);
                    StockManager.get().switchWorld(num);
                    context.getSource().sendFeedback(Text.literal("已切换到世界配置 world_" + num));
                    return 1;
                }));
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /** {@code actions} 这一层。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> actions(String name) {
        return ClientCommandManager.literal(name)
            .executes(ConfigCommand::showActions)
            .then(ClientCommandManager.literal("move_speed")
                .executes(context -> {
                    showMoveSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("speed",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_MOVE_SPEED,
                            PlayerControllerConfig.MAX_MOVE_SPEED))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMoveSpeed(speed);
                        context.getSource().sendFeedback(Text.literal(
                            "移动速度已设为 " + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("max_descent_speed")
                .executes(context -> {
                    showMaxDescentSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("speed",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_MAX_DESCENT_SPEED,
                            PlayerControllerConfig.MAX_MAX_DESCENT_SPEED))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMaxDescentSpeed(speed);
                        context.getSource().sendFeedback(Text.literal(
                            "下降速度上限已设为 " + PlayerControllerConfig.getMaxDescentSpeed() + " 格/tick"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("interaction_range")
                .executes(context -> {
                    showInteractionRange(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("range",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_INTERACTION_RANGE,
                            PlayerControllerConfig.MAX_INTERACTION_RANGE))
                    .executes(context -> {
                        double range = DoubleArgumentType.getDouble(context, "range");
                        PlayerControllerConfig.setInteractionRange(range);
                        context.getSource().sendFeedback(Text.literal(
                            "触及距离已设为 " + PlayerControllerConfig.getInteractionRange() + " 格"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("anti_kick_delay")
                .executes(context -> {
                    showAntiKickDelay(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("ticks",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_ANTI_KICK_DELAY,
                            PlayerControllerConfig.MAX_ANTI_KICK_DELAY))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setAntiKickDelay(ticks);
                        context.getSource().sendFeedback(Text.literal(
                            "防踢间隔已设为 " + PlayerControllerConfig.getAntiKickDelay() + " tick"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("anti_kick_off_time")
                .executes(context -> {
                    showAntiKickOffTime(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("ticks",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_ANTI_KICK_OFF_TIME,
                            PlayerControllerConfig.MAX_ANTI_KICK_OFF_TIME))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setAntiKickOffTime(ticks);
                        context.getSource().sendFeedback(Text.literal(
                            "防踢补包时间已设为 " + PlayerControllerConfig.getAntiKickOffTime() + " tick"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("items_per_tick")
                .executes(context -> {
                    showItemsPerTick(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("count",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_ITEMS_PER_TICK,
                            PlayerControllerConfig.MAX_ITEMS_PER_TICK))
                    .executes(context -> {
                        int count = IntegerArgumentType.getInteger(context, "count");
                        PlayerControllerConfig.setItemsPerTick(count);
                        context.getSource().sendFeedback(Text.literal(
                            "一个 tick 里最多点 " + PlayerControllerConfig.getItemsPerTick() + " 下"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("wait_ticks_after_dimension_teleport")
                .executes(context -> {
                    showWaitAfterTeleport(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_WAIT_TICKS_AFTER_DIMENSION_TELEPORT,
                            PlayerControllerConfig.MAX_WAIT_TICKS_AFTER_DIMENSION_TELEPORT))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setWaitTicksAfterDimensionTeleport(ticks);
                        context.getSource().sendFeedback(Text.literal(
                            "换维度后的等待已设为 " + PlayerControllerConfig.getWaitTicksAfterDimensionTeleport()
                                + " tick"));
                        return 1;
                    })));
    }

    private static int showAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7当前配置："));
        source.sendFeedback(Text.literal("  debug = " + PlayerControllerConfig.isDebug()));
        source.sendFeedback(Text.literal("  world = " + PlayerControllerConfig.getWorld()));
        source.sendFeedback(Text.literal("  actions.move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
        source.sendFeedback(Text.literal("  actions.max_descent_speed = "
            + PlayerControllerConfig.getMaxDescentSpeed() + " 格/tick（下降时取 min(move_speed, 这个)）"));
        source.sendFeedback(Text.literal("  actions.interaction_range = "
            + PlayerControllerConfig.getInteractionRange() + " 格"));
        source.sendFeedback(Text.literal("  actions.anti_kick_delay = "
            + PlayerControllerConfig.getAntiKickDelay() + " tick"));
        source.sendFeedback(Text.literal("  actions.anti_kick_off_time = "
            + PlayerControllerConfig.getAntiKickOffTime() + " tick"));
        source.sendFeedback(Text.literal("  actions.items_per_tick = "
            + PlayerControllerConfig.getItemsPerTick()));
        source.sendFeedback(Text.literal("  actions.wait_ticks_after_dimension_teleport = "
            + PlayerControllerConfig.getWaitTicksAfterDimensionTeleport() + " tick"));
        source.sendFeedback(Text.literal("  文件: " + PlayerControllerConfig.configFile()));
        return 1;
    }

    private static int showActions(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("actions:"));
        showMoveSpeed(source);
        showMaxDescentSpeed(source);
        showInteractionRange(source);
        showAntiKickDelay(source);
        showAntiKickOffTime(source);
        showItemsPerTick(source);
        showWaitAfterTeleport(source);
        return 1;
    }

    private static void showMoveSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
    }

    private static void showMaxDescentSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  max_descent_speed = "
            + PlayerControllerConfig.getMaxDescentSpeed()
            + " 格/tick（下降时取 min(move_speed, 这个)；下降太快会被原版算伤害）"));
    }

    private static void showInteractionRange(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  interaction_range = "
            + PlayerControllerConfig.getInteractionRange() + " 格"));
    }

    private static void showAntiKickDelay(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  anti_kick_delay = "
            + PlayerControllerConfig.getAntiKickDelay() + " tick（防踢：多久补一次「往下掉」）"));
    }

    private static void showAntiKickOffTime(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  anti_kick_off_time = "
            + PlayerControllerConfig.getAntiKickOffTime() + " tick（防踢：补包持续几 tick）"));
    }

    private static void showItemsPerTick(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  items_per_tick = "
            + PlayerControllerConfig.getItemsPerTick()
            + "（一个 tick 里最多点几下：搬容器、往背包格放东西这种连点批次的上限，"
            + PlayerControllerConfig.MIN_ITEMS_PER_TICK + "~"
            + PlayerControllerConfig.MAX_ITEMS_PER_TICK + "）"));
    }

    private static void showWaitAfterTeleport(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  wait_ticks_after_dimension_teleport = "
            + PlayerControllerConfig.getWaitTicksAfterDimensionTeleport()
            + " tick（换维度之后先等这么久再移动，免得被服务端拉回去）"));
    }

    private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean value) {
        PlayerControllerConfig.setDebug(value);
        context.getSource().sendFeedback(
            Text.literal("调试信息已" + (value ? "§a开启" : "§c关闭")));
        return 1;
    }
}
