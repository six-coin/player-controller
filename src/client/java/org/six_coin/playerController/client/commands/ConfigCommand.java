package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import org.six_coin.playerController.client.attack.AttackConfig;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.mending.MendingConfig;
import org.six_coin.playerController.client.mine.station.MineStationManager;
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
 * /pc config actions attack_range [range]
 * /pc config actions attack_wall_range [range]
 * /pc config actions anti_kick_delay [tick]
 * /pc config actions anti_kick_off_time [tick]
 * /pc config actions network_delay_ticks [1~40]
 * /pc config actions wait_ticks_after_dimension_teleport [tick]
 * /pc config actions mending_max_wait_exp_seconds [second]
 * /pc config actions mending_detect_interval_ticks [tick]
 * /pc config actions mine_simple_range [range]
 * /pc config actions mine_simple_wall_range [range]
 * /pc config actions mine_simple_max_blocks_per_tick [blocks]
 * /pc config actions mine_tool_switch_interval_ticks [tick]
 * /pc config actions mine_max_vertical_speed_blocks_per_tick [speed]
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
                    AttackConfig.get().switchWorld(num);
                    MendingConfig.get().switchWorld(num);
                    MineStationManager.get().switchWorld(num);
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
            .then(ClientCommandManager.literal("attack_range")
                .executes(context -> {
                    showAttackRange(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("range",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_ATTACK_RANGE,
                            PlayerControllerConfig.MAX_ATTACK_RANGE))
                    .executes(context -> {
                        double range = DoubleArgumentType.getDouble(context, "range");
                        PlayerControllerConfig.setAttackRange(range);
                        context.getSource().sendFeedback(Text.literal(
                            "攻击范围已设为 " + PlayerControllerConfig.getAttackRange() + " 格"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("attack_wall_range")
                .executes(context -> {
                    showAttackWallRange(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("range",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_ATTACK_WALL_RANGE,
                            PlayerControllerConfig.MAX_ATTACK_WALL_RANGE))
                    .executes(context -> {
                        double range = DoubleArgumentType.getDouble(context, "range");
                        PlayerControllerConfig.setAttackWallRange(range);
                        context.getSource().sendFeedback(Text.literal(
                            "穿墙攻击范围已设为 " + PlayerControllerConfig.getAttackWallRange() + " 格"));
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
            .then(ClientCommandManager.literal("network_delay_ticks")
                .executes(context -> {
                    showNetworkDelayTicks(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("ticks",
                        IntegerArgumentType.integer(PlayerControllerConfig.MIN_NETWORK_DELAY_TICKS,
                            PlayerControllerConfig.MAX_NETWORK_DELAY_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setNetworkDelayTicks(ticks);
                        context.getSource().sendFeedback(Text.literal(
                            "等服务器同步的 tick 数已设为 " + PlayerControllerConfig.getNetworkDelayTicks()));
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
                    })))
            .then(ClientCommandManager.literal("mending_max_wait_exp_seconds")
                .executes(context -> {
                    showMendingMaxWait(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("seconds",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MENDING_MAX_WAIT_EXP_SECONDS,
                            PlayerControllerConfig.MAX_MENDING_MAX_WAIT_EXP_SECONDS))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "seconds");
                        PlayerControllerConfig.setMendingMaxWaitExpSeconds(seconds);
                        context.getSource().sendFeedback(Text.literal(
                            "mending 等经验的超时已设为 "
                                + PlayerControllerConfig.getMendingMaxWaitExpSeconds() + " 秒"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("mending_detect_interval_ticks")
                .executes(context -> {
                    showMendingDetectInterval(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MENDING_DETECT_INTERVAL_TICKS,
                            PlayerControllerConfig.MAX_MENDING_DETECT_INTERVAL_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMendingDetectIntervalTicks(ticks);
                        context.getSource().sendFeedback(Text.literal(
                            "mending 的检测间隔已设为 "
                                + PlayerControllerConfig.getMendingDetectIntervalTicks() + " tick"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("mine_simple_range")
                .executes(context -> {
                    showMineSimpleRange(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("range",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_MINE_SIMPLE_RANGE,
                            PlayerControllerConfig.MAX_MINE_SIMPLE_RANGE))
                    .executes(context -> {
                        double range = DoubleArgumentType.getDouble(context, "range");
                        PlayerControllerConfig.setMineSimpleRange(range);
                        context.getSource().sendFeedback(Text.literal(
                            "挖掘（看得见的）范围已设为 " + PlayerControllerConfig.getMineSimpleRange() + " 格"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("mine_simple_wall_range")
                .executes(context -> {
                    showMineSimpleWallRange(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("range",
                        DoubleArgumentType.doubleArg(PlayerControllerConfig.MIN_MINE_SIMPLE_WALL_RANGE,
                            PlayerControllerConfig.MAX_MINE_SIMPLE_WALL_RANGE))
                    .executes(context -> {
                        double range = DoubleArgumentType.getDouble(context, "range");
                        PlayerControllerConfig.setMineSimpleWallRange(range);
                        context.getSource().sendFeedback(Text.literal(
                            "挖掘（隔墙的）范围已设为 " + PlayerControllerConfig.getMineSimpleWallRange() + " 格"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("mine_simple_max_blocks_per_tick")
                .executes(context -> {
                    showMineSimpleMaxBlocks(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("blocks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MINE_SIMPLE_MAX_BLOCKS_PER_TICK,
                            PlayerControllerConfig.MAX_MINE_SIMPLE_MAX_BLOCKS_PER_TICK))
                    .executes(context -> {
                        int blocks = IntegerArgumentType.getInteger(context, "blocks");
                        PlayerControllerConfig.setMineSimpleMaxBlocksPerTick(blocks);
                        context.getSource().sendFeedback(Text.literal(
                            "挖掘每 tick 最多挖的方块数已设为 "
                                + PlayerControllerConfig.getMineSimpleMaxBlocksPerTick()));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("mine_tool_switch_interval_ticks")
                .executes(context -> {
                    showMineToolSwitchInterval(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("ticks",
                        IntegerArgumentType.integer(
                            PlayerControllerConfig.MIN_MINE_TOOL_SWITCH_INTERVAL_TICKS,
                            PlayerControllerConfig.MAX_MINE_TOOL_SWITCH_INTERVAL_TICKS))
                    .executes(context -> {
                        int ticks = IntegerArgumentType.getInteger(context, "ticks");
                        PlayerControllerConfig.setMineToolSwitchIntervalTicks(ticks);
                        context.getSource().sendFeedback(Text.literal(
                            "挖掘换工具后的等待已设为 "
                                + PlayerControllerConfig.getMineToolSwitchIntervalTicks() + " tick"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("mine_max_vertical_speed_blocks_per_tick")
                .executes(context -> {
                    showMineMaxVerticalSpeed(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.argument("speed",
                        DoubleArgumentType.doubleArg(
                            PlayerControllerConfig.MIN_MINE_MAX_VERTICAL_SPEED_PER_TICK,
                            PlayerControllerConfig.MAX_MINE_MAX_VERTICAL_SPEED_PER_TICK))
                    .executes(context -> {
                        double speed = DoubleArgumentType.getDouble(context, "speed");
                        PlayerControllerConfig.setMineMaxVerticalSpeedPerTick(speed);
                        context.getSource().sendFeedback(Text.literal(
                            "挖掘的纵向速度上限已设为 "
                                + PlayerControllerConfig.getMineMaxVerticalSpeedPerTick() + " 格/tick"));
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
        source.sendFeedback(Text.literal("  actions.attack_range = "
            + PlayerControllerConfig.getAttackRange() + " 格"));
        source.sendFeedback(Text.literal("  actions.attack_wall_range = "
            + PlayerControllerConfig.getAttackWallRange() + " 格（看不见目标时也打的距离）"));
        source.sendFeedback(Text.literal("  actions.anti_kick_delay = "
            + PlayerControllerConfig.getAntiKickDelay() + " tick"));
        source.sendFeedback(Text.literal("  actions.anti_kick_off_time = "
            + PlayerControllerConfig.getAntiKickOffTime() + " tick"));
        source.sendFeedback(Text.literal("  actions.network_delay_ticks = "
            + PlayerControllerConfig.getNetworkDelayTicks() + " tick（等服务器同步：开界面之后、连点之后）"));
        source.sendFeedback(Text.literal("  actions.wait_ticks_after_dimension_teleport = "
            + PlayerControllerConfig.getWaitTicksAfterDimensionTeleport() + " tick"));
        source.sendFeedback(Text.literal("  actions.mending_max_wait_exp_seconds = "
            + PlayerControllerConfig.getMendingMaxWaitExpSeconds() + " 秒（没有进展就退出检测）"));
        source.sendFeedback(Text.literal("  actions.mending_detect_interval_ticks = "
            + PlayerControllerConfig.getMendingDetectIntervalTicks() + " tick（多久检测一次）"));
        source.sendFeedback(Text.literal("  actions.mine_simple_range = "
            + PlayerControllerConfig.getMineSimpleRange() + " 格（挖掘：看得见的）"));
        source.sendFeedback(Text.literal("  actions.mine_simple_wall_range = "
            + PlayerControllerConfig.getMineSimpleWallRange() + " 格（挖掘：隔墙的）"));
        source.sendFeedback(Text.literal("  actions.mine_simple_max_blocks_per_tick = "
            + PlayerControllerConfig.getMineSimpleMaxBlocksPerTick() + " 个（挖掘：每 tick 最多）"));
        source.sendFeedback(Text.literal("  actions.mine_tool_switch_interval_ticks = "
            + PlayerControllerConfig.getMineToolSwitchIntervalTicks() + " tick（挖掘：换完工具等多久）"));
        source.sendFeedback(Text.literal("  actions.mine_max_vertical_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxVerticalSpeedPerTick()
            + " 格/tick（挖掘：纵向速度要小于这个才动手）"));
        source.sendFeedback(Text.literal("  文件: " + PlayerControllerConfig.configFile()));
        return 1;
    }

    private static int showActions(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("actions:"));
        showMoveSpeed(source);
        showMaxDescentSpeed(source);
        showInteractionRange(source);
        showAttackRange(source);
        showAttackWallRange(source);
        showAntiKickDelay(source);
        showAntiKickOffTime(source);
        showNetworkDelayTicks(source);
        showWaitAfterTeleport(source);
        showMendingMaxWait(source);
        showMendingDetectInterval(source);
        showMineSimpleRange(source);
        showMineSimpleWallRange(source);
        showMineSimpleMaxBlocks(source);
        showMineToolSwitchInterval(source);
        showMineMaxVerticalSpeed(source);
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

    private static void showAttackRange(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  attack_range = "
            + PlayerControllerConfig.getAttackRange() + " 格（/pc attack 的攻击范围）"));
    }

    private static void showAttackWallRange(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  attack_wall_range = "
            + PlayerControllerConfig.getAttackWallRange()
            + " 格（看不见目标时（隔着方块）还够得着的距离）"));
    }

    private static void showAntiKickDelay(FabricClientCommandSource source) {        source.sendFeedback(Text.literal("  anti_kick_delay = "
            + PlayerControllerConfig.getAntiKickDelay() + " tick（防踢：多久补一次「往下掉」）"));
    }

    private static void showAntiKickOffTime(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  anti_kick_off_time = "
            + PlayerControllerConfig.getAntiKickOffTime() + " tick（防踢：补包持续几 tick）"));
    }

    private static void showNetworkDelayTicks(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  network_delay_ticks = "
            + PlayerControllerConfig.getNetworkDelayTicks()
            + "（等服务器把数据同步回来的 tick 数：容器界面刚打开、一 tick 连点之后都先等这么多 tick，"
            + PlayerControllerConfig.MIN_NETWORK_DELAY_TICKS + "~"
            + PlayerControllerConfig.MAX_NETWORK_DELAY_TICKS + "；网络不好就调大）"));
    }

    private static void showWaitAfterTeleport(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  wait_ticks_after_dimension_teleport = "
            + PlayerControllerConfig.getWaitTicksAfterDimensionTeleport()
            + " tick（换维度之后先等这么久再移动，免得被服务端拉回去）"));
    }

    private static void showMendingMaxWait(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mending_max_wait_exp_seconds = "
            + PlayerControllerConfig.getMendingMaxWaitExpSeconds()
            + " 秒（mending：连续这么久没有任何东西被修好就退出检测，"
            + PlayerControllerConfig.MIN_MENDING_MAX_WAIT_EXP_SECONDS + "~"
            + PlayerControllerConfig.MAX_MENDING_MAX_WAIT_EXP_SECONDS + "）"));
    }

    private static void showMendingDetectInterval(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mending_detect_interval_ticks = "
            + PlayerControllerConfig.getMendingDetectIntervalTicks()
            + " tick（mending：多久检测一次，20 tick = 1 秒）"));
    }

    private static void showMineSimpleRange(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mine_simple_range = "
            + PlayerControllerConfig.getMineSimpleRange()
            + " 格（挖掘：看得见的方块能挖多远）"));
    }

    private static void showMineSimpleWallRange(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mine_simple_wall_range = "
            + PlayerControllerConfig.getMineSimpleWallRange()
            + " 格（挖掘：被方块挡住的时候还能挖多远）"));
    }

    private static void showMineSimpleMaxBlocks(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mine_simple_max_blocks_per_tick = "
            + PlayerControllerConfig.getMineSimpleMaxBlocksPerTick()
            + " 个（挖掘：一个 tick 最多挖几个方块）"));
    }

    private static void showMineToolSwitchInterval(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mine_tool_switch_interval_ticks = "
            + PlayerControllerConfig.getMineToolSwitchIntervalTicks()
            + " tick（挖掘：换完工具之后空这么多 tick 再动手，0 = 不等）"));
    }

    private static void showMineMaxVerticalSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  mine_max_vertical_speed_blocks_per_tick = "
            + PlayerControllerConfig.getMineMaxVerticalSpeedPerTick()
            + " 格/tick（挖掘：纵向速度小于它才动手，0 = 要求正好 0）"));
    }

    private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean value) {
        PlayerControllerConfig.setDebug(value);
        context.getSource().sendFeedback(
            Text.literal("调试信息已" + (value ? "§a开启" : "§c关闭")));
        return 1;
    }
}
