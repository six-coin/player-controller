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
                source.sendFeedback(Text.literal("§7  /" + name + " move face <blocks> §8- §7朝当前朝向移动（抬头/低头就是上下）"));
                source.sendFeedback(Text.literal("§7  /" + name + " move to <x y z|name> §8- §7沿路径点图走过去（走的都是已知边，不记录任何东西）"));
                source.sendFeedback(Text.literal("§7    §7也可以写 to_name <名字> / to_id <编号> / to_position <x> <y> <z>"));
                source.sendFeedback(Text.literal("§7    §7终点坐标在边上会先在那边建个路径点；起点在边上也会先建点，都不在就报错"));
                source.sendFeedback(Text.literal("§7  /" + name + " move cancel §8- §7取消所有移动任务"));
                source.sendFeedback(Text.literal("§7  /" + name + " container get from_target <item_list> §8- §7打开准星看到的容器，按 item_list 取物品（跑完输出剩下的 item_list）"));
                source.sendFeedback(Text.literal("§7  /" + name + " w ... §8- §7路径点 / 边 / 出生点（等价写法：/" + name + " waypoints ...）"));
                source.sendFeedback(Text.literal("§7    §7子命令：waypoint_add、waypoint_add_here、waypoint_name、waypoint_name_del、spawn_set_by_name、edge_add、del、optimize、list、show、edit"));
                source.sendFeedback(Text.literal("§7    §7清理：del_unreachable_list（列出从当前位置不可达的点和边）、del_unreachable_execute（先备份再删掉它们）"));
                source.sendFeedback(Text.literal("§7  /" + name + " config ... §8- §7配置（debug / world / actions move_speed、interaction_range）"));
                source.sendFeedback(Text.literal("§7  item_list 例：§f{\"minecraft:cobblestone\": 65, \"minecraft:grass_block\": 13}"));
                return 1;
            })
            .then(MoveCommand.build())
            .then(ContainerCommand.build())
            // /pc w ... 和 /pc waypoints ... 是同一条命令的两种写法
            .then(WaypointsCommand.build("w"))
            .then(WaypointsCommand.build("waypoints"))
            .then(ConfigCommand.build());
    }
}
