package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import org.six_coin.playerController.client.config.PlayerControllerConfig;

/** {@code /pc debug [true|false]} —— 热开关调试信息。 */
public final class DebugCommand {

    private DebugCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("debug")
            .executes(context -> apply(context, !PlayerControllerConfig.isDebug()))
            .then(ClientCommandManager.literal("true")
                .executes(context -> apply(context, true)))
            .then(ClientCommandManager.literal("false")
                .executes(context -> apply(context, false)));
    }

    private static int apply(CommandContext<FabricClientCommandSource> context, boolean value) {
        PlayerControllerConfig.setDebug(value);
        context.getSource().sendFeedback(
            Text.literal("调试信息已" + (value ? "§a开启" : "§c关闭")));
        if (value) {
            context.getSource().sendFeedback(Text.literal(
                "配置文件: " + PlayerControllerConfig.configFile()));
        }
        return 1;
    }
}
