package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.waypoint.WaypointManager;

/**
 * {@code /pc config ...} —— 热改配置。
 *
 * <pre>
 * /pc config debug [true|false]
 * /pc config world [num]
 * /pc config actions move_speed [speed]
 * /pc config actions interaction_range [range]
 * </pre>
 *
 * <p>第二层的 {@code actions} 也可以写成 {@code action}。
 */
public final class ConfigCommand {

    private ConfigCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("config")
            .executes(ConfigCommand::showAll)
            .then(debug())
            .then(world())
            .then(actions("actions"))
            .then(actions("action"));
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
                    context.getSource().sendFeedback(Text.literal("已切换到世界配置 world_" + num));
                    return 1;
                }));
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /** {@code actions}/{@code action} 这一层，两套名字都建一份，互不共享。 */
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
                    })));
    }

    private static int showAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7当前配置："));
        source.sendFeedback(Text.literal("  debug = " + PlayerControllerConfig.isDebug()));
        source.sendFeedback(Text.literal("  world = " + PlayerControllerConfig.getWorld()));
        source.sendFeedback(Text.literal("  actions.move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
        source.sendFeedback(Text.literal("  actions.interaction_range = "
            + PlayerControllerConfig.getInteractionRange() + " 格"));
        source.sendFeedback(Text.literal("  文件: " + PlayerControllerConfig.configFile()));
        return 1;
    }

    private static int showActions(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("actions:"));
        showMoveSpeed(source);
        showInteractionRange(source);
        return 1;
    }

    private static void showMoveSpeed(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
    }

    private static void showInteractionRange(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("  interaction_range = "
            + PlayerControllerConfig.getInteractionRange() + " 格"));
    }

    private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean value) {
        PlayerControllerConfig.setDebug(value);
        context.getSource().sendFeedback(
            Text.literal("调试信息已" + (value ? "§a开启" : "§c关闭")));
        return 1;
    }
}
