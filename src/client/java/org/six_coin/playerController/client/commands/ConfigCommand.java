package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.six_coin.playerController.client.attack.AttackConfig;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.mending.MendingConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.mine.complex.MineComplexManager;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.waypoint.WaypointManager;

/**
 * {@code /pc config ...} —— 热改配置。
 *
 * <pre>
 * /pc config debug [true|false]
 * /pc config level [num]
 * /pc config actions move_speed [speed]
 * /pc config actions move_max_descent_speed_blocks_per_tick [speed]
 * /pc config actions reach [range]
 * /pc config actions move_anti_kick_delay_ticks [tick]
 * /pc config actions move_anti_kick_off_time_ticks [tick]
 * /pc config actions network_delay_ticks [1~40]
 * /pc config actions move_dimension_wait_ticks [tick]
 * /pc config actions mend_max_wait_exp_seconds [second]
 * /pc config actions mend_detect_interval_ticks [tick]
 * /pc config actions mine_max_blocks_per_tick [blocks]
 * /pc config actions mine_tool_switch_interval_ticks [tick]
 * /pc config actions mine_complex_tool_durability_check_interval_ticks [tick]
 * /pc config actions mine_max_vertical_speed_blocks_per_tick [speed]
 * /pc config actions mine_max_horizontal_speed_blocks_per_tick [speed]
 * </pre>
 */
public final class ConfigCommand {

    private ConfigCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("config")
            .executes(ConfigCommand::showAll)
            .then(debug())
            .then(level())
            .then(actions("actions"));
    }

    // ------------------------------------------------------------------
    // debug / level
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> debug() {
        return ClientCommands.literal("debug")
            .executes(context -> setDebug(context, !PlayerControllerConfig.isDebug()))
            .then(ClientCommands.literal("true")
                .executes(context -> setDebug(context, true)))
            .then(ClientCommands.literal("false")
                .executes(context -> setDebug(context, false)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> level() {
        return ClientCommands.literal("world")
            .executes(context -> {
                context.getSource().sendFeedback(Component.literal(
                    "当前世界配置: world_" + PlayerControllerConfig.getWorld()
                        + "（" + PlayerControllerConfig.configDir()
                        .resolve("world_" + PlayerControllerConfig.getWorld()) + "）"));
                return 1;
            })
            .then(ClientCommands.argument("num", IntegerArgumentType.integer(1))
                .executes(context -> {
                    int num = IntegerArgumentType.getInteger(context, "num");
                    if (num == PlayerControllerConfig.getWorld()) {
                        context.getSource().sendFeedback(Component.literal("已经是 world_" + num + " 了"));
                        return 1;
                    }
                    WaypointManager.get().switchWorld(num);
                    ContainerCacheManager.get().switchWorld(num);
                    StationManager.get().switchWorld(num);
                    StockManager.get().switchWorld(num);
                    AttackConfig.get().switchWorld(num);
                    MendingConfig.get().switchWorld(num);
                    MineStationManager.get().switchWorld(num);
                    MineComplexManager.get().switchWorld(num);
                    MineManager.get().resumeSaved();
                    context.getSource().sendFeedback(Component.literal("已切换到世界配置 world_" + num));
                    return 1;
                }));
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /** {@code actions} 这一层。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> actions(String name) {
        return ClientCommands.literal(name)
            .executes(ConfigCommand::showActions)
            .then(ClientCommands.literal("move_speed")
                .executes(context -> {
                    showMoveSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("speed",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_MOVE_SPEED,
                            PlayerControllerConfig.MAX_MOVE_SPEED))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMoveSpeed(speed);
                        context.getSource().sendFeedback(Component.literal(
                            "移动速度已设为 " + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("move_max_descent_speed_blocks_per_tick")
                .executes(context -> {
                    showMaxDescentSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("speed",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_MOVE_MAX_DESCENT_SPEED_BLOCKS_PER_TICK,
                            PlayerControllerConfig.MAX_MOVE_MAX_DESCENT_SPEED_BLOCKS_PER_TICK))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMoveMaxDescentSpeedBlocksPerTick(speed);
                        context.getSource().sendFeedback(Component.literal(
                            "下降速度上限已设为 " + PlayerControllerConfig.getMoveMaxDescentSpeedBlocksPerTick() + " 格/tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("reach")
                .executes(context -> {
                    showReach(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("range",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_REACH,
                            PlayerControllerConfig.MAX_REACH))
                    .executes(context -> {
                        double range = DoubleArgumentType.getDouble(context, "range");
                        PlayerControllerConfig.setReach(range);
                        context.getSource().sendFeedback(Component.literal(
                            "触及 / 攻击距离已设为 " + PlayerControllerConfig.getReach() + " 格"));
                        return 1;
                    })))
            .then(ClientCommands.literal("move_anti_kick_delay_ticks")
                .executes(context -> {
                    showAntiKickDelay(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_MOVE_ANTI_KICK_DELAY_TICKS,
                            PlayerControllerConfig.MAX_MOVE_ANTI_KICK_DELAY_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMoveAntiKickDelayTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "防踢间隔已设为 " + PlayerControllerConfig.getMoveAntiKickDelayTicks() + " tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("move_anti_kick_off_time_ticks")
                .executes(context -> {
                    showAntiKickOffTime(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_MOVE_ANTI_KICK_OFF_TIME_TICKS,
                            PlayerControllerConfig.MAX_MOVE_ANTI_KICK_OFF_TIME_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMoveAntiKickOffTimeTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "防踢补包时间已设为 " + PlayerControllerConfig.getMoveAntiKickOffTimeTicks() + " tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("network_delay_ticks")
                .executes(context -> {
                    showNetworkDelayTicks(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_NETWORK_DELAY_TICKS,
                            PlayerControllerConfig.MAX_NETWORK_DELAY_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setNetworkDelayTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "等服务器同步的 tick 数已设为 " + PlayerControllerConfig.getNetworkDelayTicks()));
                        return 1;
                    })))
            .then(ClientCommands.literal("move_dimension_wait_ticks")
                .executes(context -> {
                    showWaitAfterTeleport(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MOVE_DIMENSION_WAIT_TICKS,
                            PlayerControllerConfig.MAX_MOVE_DIMENSION_WAIT_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMoveDimensionWaitTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "换维度后的等待已设为 " + PlayerControllerConfig.getMoveDimensionWaitTicks()
                                + " tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("mend_max_wait_exp_seconds")
                .executes(context -> {
                    showMendMaxWait(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("seconds",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MEND_MAX_WAIT_EXP_SECONDS,
                            PlayerControllerConfig.MAX_MEND_MAX_WAIT_EXP_SECONDS))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "seconds");
                        PlayerControllerConfig.setMendMaxWaitExpSeconds(seconds);
                        context.getSource().sendFeedback(Component.literal(
                            "mend 等经验的超时已设为 "
                                + PlayerControllerConfig.getMendMaxWaitExpSeconds() + " 秒"));
                        return 1;
                    })))
            .then(ClientCommands.literal("mend_detect_interval_ticks")
                .executes(context -> {
                    showMendDetectInterval(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MEND_DETECT_INTERVAL_TICKS,
                            PlayerControllerConfig.MAX_MEND_DETECT_INTERVAL_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMendDetectIntervalTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "mend 的检测间隔已设为 "
                                + PlayerControllerConfig.getMendDetectIntervalTicks() + " tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("mine_max_blocks_per_tick")
                .executes(context -> {
                    showMineMaxBlocks(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("blocks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MINE_MAX_BLOCKS_PER_TICK,
                            PlayerControllerConfig.MAX_MINE_MAX_BLOCKS_PER_TICK))
                    .executes(context -> {
                        int blocks = IntegerArgumentType.getInteger(context, "blocks");
                        PlayerControllerConfig.setMineMaxBlocksPerTick(blocks);
                        context.getSource().sendFeedback(Component.literal(
                            "挖掘每 tick 最多挖的方块数已设为 "
                                + PlayerControllerConfig.getMineMaxBlocksPerTick()));
                        return 1;
                    })))
            .then(ClientCommands.literal("mine_tool_switch_interval_ticks")
                .executes(context -> {
                    showMineToolSwitchInterval(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MINE_TOOL_SWITCH_INTERVAL_TICKS,
                            PlayerControllerConfig.MAX_MINE_TOOL_SWITCH_INTERVAL_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMineToolSwitchIntervalTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "挖掘换工具后的等待已设为 "
                                + PlayerControllerConfig.getMineToolSwitchIntervalTicks() + " tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("mine_complex_tool_durability_check_interval_ticks")
                .executes(context -> {
                    showMineComplexToolDurabilityCheck(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MINE_COMPLEX_TOOL_DURABILITY_CHECK_INTERVAL_TICKS,
                            PlayerControllerConfig.MAX_MINE_COMPLEX_TOOL_DURABILITY_CHECK_INTERVAL_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMineComplexToolDurabilityCheckIntervalTicks(ticks);
                        context.getSource().sendFeedback(Component.literal(
                            "复杂挖掘的工具耐久检查间隔已设为 "
                                + PlayerControllerConfig.getMineComplexToolDurabilityCheckIntervalTicks()
                                + " tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("mine_max_vertical_speed_blocks_per_tick")
                .executes(context -> {
                    showMineMaxVerticalSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("speed",
                        DoubleArgumentType.doubleArg(
                            PlayerControllerConfig.MIN_MINE_MAX_VERTICAL_SPEED_PER_TICK,
                            PlayerControllerConfig.MAX_MINE_MAX_VERTICAL_SPEED_PER_TICK))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMineMaxVerticalSpeedPerTick(speed);
                        context.getSource().sendFeedback(Component.literal(
                            "挖掘的纵向速度上限已设为 "
                                + PlayerControllerConfig.getMineMaxVerticalSpeedPerTick() + " 格/tick"));
                        return 1;
                    })))
            .then(ClientCommands.literal("mine_max_horizontal_speed_blocks_per_tick")
                .executes(context -> {
                    showMineMaxHorizontalSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommands.argument("speed",
                        DoubleArgumentType.doubleArg(
                            PlayerControllerConfig.MIN_MINE_MAX_HORIZONTAL_SPEED_PER_TICK,
                            PlayerControllerConfig.MAX_MINE_MAX_HORIZONTAL_SPEED_PER_TICK))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMineMaxHorizontalSpeedPerTick(speed);
                        context.getSource().sendFeedback(Component.literal(
                            "挖掘的水平速度上限已设为 "
                                + PlayerControllerConfig.getMineMaxHorizontalSpeedPerTick()
                                + " 格/tick（x、z 两个方向分别判）"));
                        return 1;
                    })));
    }

    private static int showAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7当前配置："));
        source.sendFeedback(Component.literal("  debug = " + PlayerControllerConfig.isDebug()));
        source.sendFeedback(Component.literal("  world = " + PlayerControllerConfig.getWorld()));
        source.sendFeedback(Component.literal("  actions.move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
        source.sendFeedback(Component.literal("  actions.move_max_descent_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMoveMaxDescentSpeedBlocksPerTick()
            + " 格/tick（下降时取 min(move_speed, 这个)）"));
        source.sendFeedback(Component.literal("  actions.reach = "
            + PlayerControllerConfig.getReach() + " 格（开容器 / 挖方块 / 打怪共用）"));
        source.sendFeedback(Component.literal("  actions.move_anti_kick_delay_ticks = "
            + PlayerControllerConfig.getMoveAntiKickDelayTicks() + " tick"));
        source.sendFeedback(Component.literal("  actions.move_anti_kick_off_time_ticks = "
            + PlayerControllerConfig.getMoveAntiKickOffTimeTicks() + " tick"));
        source.sendFeedback(Component.literal("  actions.network_delay_ticks = "
            + PlayerControllerConfig.getNetworkDelayTicks() + " tick（等服务器同步：开界面之后、连点之后）"));
        source.sendFeedback(Component.literal("  actions.move_dimension_wait_ticks = "
            + PlayerControllerConfig.getMoveDimensionWaitTicks() + " tick"));
        source.sendFeedback(Component.literal("  actions.mend_max_wait_exp_seconds = "
            + PlayerControllerConfig.getMendMaxWaitExpSeconds() + " 秒（没有进展就退出检测）"));
        source.sendFeedback(Component.literal("  actions.mend_detect_interval_ticks = "
            + PlayerControllerConfig.getMendDetectIntervalTicks() + " tick（多久检测一次）"));
        source.sendFeedback(Component.literal("  actions.mine_max_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxBlocksPerTick() + " 个（挖掘：每 tick 最多）"));
        source.sendFeedback(Component.literal("  actions.mine_tool_switch_interval_ticks = "
            + PlayerControllerConfig.getMineToolSwitchIntervalTicks() + " tick（挖掘：换完工具等多久）"));
        source.sendFeedback(Component.literal(
            "  actions.mine_complex_tool_durability_check_interval_ticks = "
                + PlayerControllerConfig.getMineComplexToolDurabilityCheckIntervalTicks()
                + " tick（复杂挖掘：多久查一次手上工具的耐久）"));
        source.sendFeedback(Component.literal("  actions.mine_max_vertical_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxVerticalSpeedPerTick()
            + " 格/tick（挖掘：纵向速度不超过这个才动手）"));
        source.sendFeedback(Component.literal("  actions.mine_max_horizontal_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxHorizontalSpeedPerTick()
            + " 格/tick（挖掘：x、z 两个方向的水平速度分别不超过这个才动手）"));
        source.sendFeedback(Component.literal("  文件: " + PlayerControllerConfig.configFile()));
        return 1;
    }

    private static int showActions(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("actions:"));
        showMoveSpeed(source);
        showMaxDescentSpeed(source);
        showReach(source);
        showAntiKickDelay(source);
        showAntiKickOffTime(source);
        showNetworkDelayTicks(source);
        showWaitAfterTeleport(source);
        showMendMaxWait(source);
        showMendDetectInterval(source);
        showMineMaxBlocks(source);
        showMineToolSwitchInterval(source);
        showMineComplexToolDurabilityCheck(source);
        showMineMaxVerticalSpeed(source);
        showMineMaxHorizontalSpeed(source);
        return 1;
    }

    private static void showMoveSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
    }

    private static void showMaxDescentSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  move_max_descent_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMoveMaxDescentSpeedBlocksPerTick()
            + " 格/tick（下降时取 min(move_speed, 这个)；下降太快会被原版算伤害）"));
    }

    private static void showReach(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  reach = "
            + PlayerControllerConfig.getReach()
            + " 格（开容器 / 挖方块 / 打怪 / 算落点共用，不区分看不看得见）"));
    }

    private static void showAntiKickDelay(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  move_anti_kick_delay_ticks = "
            + PlayerControllerConfig.getMoveAntiKickDelayTicks() + " tick（防踢：多久补一次「往下掉」）"));
    }

    private static void showAntiKickOffTime(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  move_anti_kick_off_time_ticks = "
            + PlayerControllerConfig.getMoveAntiKickOffTimeTicks() + " tick（防踢：补包持续几 tick）"));
    }

    private static void showNetworkDelayTicks(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  network_delay_ticks = "
            + PlayerControllerConfig.getNetworkDelayTicks()
            + "（等服务器把数据同步回来的 tick 数：容器界面刚打开、一 tick 连点之后都先等这么多 tick，"
            + PlayerControllerConfig.MIN_NETWORK_DELAY_TICKS + "~"
            + PlayerControllerConfig.MAX_NETWORK_DELAY_TICKS + "；网络不好就调大）"));
    }

    private static void showWaitAfterTeleport(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  move_dimension_wait_ticks = "
            + PlayerControllerConfig.getMoveDimensionWaitTicks()
            + " tick（换维度之后先等这么久再移动，免得被服务端拉回去）"));
    }

    private static void showMendMaxWait(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  mend_max_wait_exp_seconds = "
            + PlayerControllerConfig.getMendMaxWaitExpSeconds()
            + " 秒（mend：连续这么久没有任何东西被修好就退出检测，"
            + PlayerControllerConfig.MIN_MEND_MAX_WAIT_EXP_SECONDS + "~"
            + PlayerControllerConfig.MAX_MEND_MAX_WAIT_EXP_SECONDS + "）"));
    }

    private static void showMendDetectInterval(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  mend_detect_interval_ticks = "
            + PlayerControllerConfig.getMendDetectIntervalTicks()
            + " tick（mend：多久检测一次，20 tick = 1 秒）"));
    }

    private static void showMineMaxBlocks(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  mine_max_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxBlocksPerTick()
            + " 个（挖掘：一个 tick 最多挖几个方块）"));
    }

    private static void showMineToolSwitchInterval(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  mine_tool_switch_interval_ticks = "
            + PlayerControllerConfig.getMineToolSwitchIntervalTicks()
            + " tick（挖掘：换完工具之后空这么多 tick 再动手，0 = 不等）"));
    }

    private static void showMineComplexToolDurabilityCheck(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(
            "  mine_complex_tool_durability_check_interval_ticks = "
                + PlayerControllerConfig.getMineComplexToolDurabilityCheckIntervalTicks()
                + " tick（复杂挖掘：多久查一次手上工具的耐久，剩不到 100 就换备用工具）"));
    }

    private static void showMineMaxVerticalSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  mine_max_vertical_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxVerticalSpeedPerTick()
            + " 格/tick（挖掘：纵向速度不超过它才动手，0 = 要求正好 0）"));
    }

    private static void showMineMaxHorizontalSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("  mine_max_horizontal_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxHorizontalSpeedPerTick()
            + " 格/tick（挖掘：x、z 两个方向的水平速度分别不超过它才动手，0 = 要求正好 0）"));
    }

    private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean value) {
        PlayerControllerConfig.setDebug(value);
        context.getSource().sendFeedback(
            Component.literal("调试信息已" + (value ? "§a开启" : "§c关闭")));
        return 1;
    }
}
