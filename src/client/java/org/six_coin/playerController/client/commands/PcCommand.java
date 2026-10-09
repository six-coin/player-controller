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
                source.sendFeedback(Text.literal("§7    §7也可以写 to_name <名字> / to_id <编号> / to_position <x> <y> <z> [dim]"));
                source.sendFeedback(Text.literal("§7    §7终点坐标在边上会先在那边建个路径点；起点在边上也会先建点，都不在就报错"));
                source.sendFeedback(Text.literal("§7  /" + name + " move cancel §8- §7取消所有移动任务"));
                source.sendFeedback(Text.literal("§7  /" + name + " container get from_target|from_position|from_id ... <item_list> §8- §7打开容器取物品（输出 item_list + all_cleared，并刷新缓存）"));
                source.sendFeedback(Text.literal("§7    §7两个特殊模式：/" + name + " container special_get from_* §fonly_item§7|§fonly_one_shulker§7 <item_list>"));
                source.sendFeedback(Text.literal("§7    §7only_item 只拿物品形态的（潜影盒无视）；only_one_shulker 只取一个装着所需东西的潜影盒放进快捷栏第三格"));
                source.sendFeedback(Text.literal("§7  /" + name + " container put to_target|to_id|to_position ... all_items|all_shulker_boxes|everything §8- §7把物品栏 27 格（不含快捷栏）里的东西放进容器（输出 all_cleared）"));
                source.sendFeedback(Text.literal("§7    §7everything_include_hotbar：连快捷栏 9 格一起放（清空整个物品栏用）"));
                source.sendFeedback(Text.literal("§7  /" + name + " container cache ... §8- §7容器缓存（add/add_position/add_target/add_batch、del/del_id/del_position/del_target/del_batch、optimize、list、show、get_all_items）"));
                source.sendFeedback(Text.literal("§7  /" + name + " station ... §8- §7工作站（各部分的 set/set_target/add/del/list、show 高亮、check 检查并导出报告）"));
                source.sendFeedback(Text.literal("§7  /" + name + " recipe crafting_table|stonecutter <item> <count> §8- §7在工作站上合成 / 切石（成品 QuickMove 进快捷栏）"));
                source.sendFeedback(Text.literal("§7  /" + name + " stock add_task|del_task <名字>、/" + name + " stock task <名字> start|part2 §8- §7备货（start = 第一部分全部 + 第二部分阶段1、2；part2 = 只跑第二部分阶段1、2）"));
                source.sendFeedback(Text.literal("§7  /" + name + " w ... §8- §7路径点 / 边 / 出生点（等价写法：/" + name + " waypoints ...）"));
                source.sendFeedback(Text.literal("§7    §7子命令：waypoint_add、waypoint_add_here、waypoint_name、waypoint_name_del、spawn_set_by_name、edge_add、del、optimize、list、show、edit"));
                source.sendFeedback(Text.literal("§7    §7清理：del_unreachable_list（列出从当前位置不可达的点和边）、del_unreachable_execute（先备份再删掉它们）"));
                source.sendFeedback(Text.literal("§7  /" + name + " config ... §8- §7配置（debug / world / actions move_speed、interaction_range）"));
                source.sendFeedback(Text.literal("§7  item_list 例：§f{\"minecraft:cobblestone\": 65, \"minecraft:grass_block\": 13}"));
                return 1;
            })
            .then(MoveCommand.build())
            .then(ContainerCommand.build())
            .then(StationCommand.build())
            .then(RecipeCommand.build())
            .then(StockCommand.build())
            // /pc w ... 和 /pc waypoints ... 是同一条命令的两种写法
            .then(WaypointsCommand.build("w"))
            .then(WaypointsCommand.build("waypoints"))
            .then(ConfigCommand.build());
    }
}
