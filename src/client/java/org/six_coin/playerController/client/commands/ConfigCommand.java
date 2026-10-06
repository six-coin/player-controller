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
 * </pre>
 */
public final class ConfigCommand {

    private ConfigCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("config")
            .executes(ConfigCommand::showAll)
            .then(ClientCommandManager.literal("debug")
                .executes(context -> setDebug(context, !PlayerControllerConfig.isDebug()))
                .then(ClientCommandManager.literal("true")
                    .executes(context -> setDebug(context, true)))
                .then(ClientCommandManager.literal("false")
                    .executes(context -> setDebug(context, false))))
            .then(ClientCommandManager.literal("world")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(
                        "当前世界配置: world_" + PlayerControllerConfig.getWorld()
                            + "（" + PlayerControllerConfig.configDir().resolve("world_" + PlayerControllerConfig.getWorld()) + "）"));
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
                    })))
            .then(ClientCommandManager.literal("actions")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal("actions:"));
                    context.getSource().sendFeedback(Text.literal("  move_speed = "
                        + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
                    return 1;
                })
                .then(ClientCommandManager.literal("move_speed")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal("move_speed = "
                            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
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
                        }))));
    }

    private static int showAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7当前配置："));
        source.sendFeedback(Text.literal("  debug = " + PlayerControllerConfig.isDebug()));
        source.sendFeedback(Text.literal("  world = " + PlayerControllerConfig.getWorld()));
        source.sendFeedback(Text.literal("  actions.move_speed = "
            + PlayerControllerConfig.getMoveSpeed() + " 格/tick"));
        source.sendFeedback(Text.literal("  文件: " + PlayerControllerConfig.configFile()));
        return 1;
    }

    private static int setDebug(CommandContext<FabricClientCommandSource> context, boolean value) {
        PlayerControllerConfig.setDebug(value);
        context.getSource().sendFeedback(
            Text.literal("调试信息已" + (value ? "§a开启" : "§c关闭")));
        return 1;
    }
}
