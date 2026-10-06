package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * 命令入口。
 *
 * <p>同时注册 {@code /pc} 和 {@code /playercontroller}。
 */
public final class PcCommand {

    private PcCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(build("pc"));
            dispatcher.register(build("playercontroller"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
        return ClientCommandManager.literal(name)
            .executes(context -> {
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("§8[§bPC§8]§r §7Player Controller 命令："));
                source.sendFeedback(Text.literal("§7  /" + name + " move <x|y|z> <blocks> §8- §7沿轴移动（会先对齐方块中心）"));
                source.sendFeedback(Text.literal("§7  /" + name + " move to <x y z|name> §8- §7沿路径点图走过去"));
                source.sendFeedback(Text.literal("§7  /" + name + " move cancel §8- §7取消所有移动任务"));
                source.sendFeedback(Text.literal("§7  /" + name + " container <x> <y> <z> <item_list> §8- §7从容器取物品"));
                source.sendFeedback(Text.literal("§7  /" + name + " waypoints ... §8- §7路径点管理（add_waypoint / add_side / list / show / edit / optimize ...）"));
                source.sendFeedback(Text.literal("§7  /" + name + " waypoints spawn ... §8- §7出生点（add_here / add / list / set）"));
                source.sendFeedback(Text.literal("§7  /" + name + " config ... §8- §7配置（debug / world / actions move_speed）"));
                source.sendFeedback(Text.literal("§7  item_list 例：§fstone=64,dirt=32,oak_log"));
                return 1;
            })
            .then(MoveCommand.build())
            .then(ContainerCommand.build())
            .then(WaypointsCommand.build())
            .then(ConfigCommand.build());
    }
}
