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
                source.sendFeedback(Text.literal("§7  /" + name + " recipe crafting_table|stonecutter <item> <count> §8- §7在工作站上合成 / 切石（成品 QuickMove 进快捷栏）"));
                source.sendFeedback(Text.literal("§7  /" + name + " stock station ... §8- §7工作站配置（各部分的 set/set_target/add/del/list、show 高亮、check 检查并导出报告）"));
                source.sendFeedback(Text.literal("§7  /" + name + " stock add_task|del_task <名字>、/" + name + " stock task <名字> generate_materials_list|start_collect"));
                source.sendFeedback(Text.literal("§7    §7generate_materials_list 把 litematica 导出的 original.json 处理成 materials.json；start_collect 开始备货（预检查 → 取货 → 分盒重装 → 合成）"));
                source.sendFeedback(Text.literal("§7  /" + name + " materials ... §8- §7材料清单配置（terminal_items、blacklist、recipe_preferences、tag_preferences、ingredient_preferences、replace_materials）"));
                source.sendFeedback(Text.literal("§7  /" + name + " dump all_items|inventory|all_tags §8- §7把物品 / 物品栏 / minecraft 物品标签导成 json（config/player-controller/dump/）"));
                source.sendFeedback(Text.literal("§7  /" + name + " attack ... §8- §7攻击（一直监听，按 profile 挑该打谁）：toggle、profile、show_all_mobs"));
                source.sendFeedback(Text.literal("§7  /" + name + " mending ... §8- §7经验修补：start；station stand_point|ender_chest set|set_here|set_target|del"));
                source.sendFeedback(Text.literal("§7  /" + name + " mine ... §8- §7挖掘：cancel；simple <x1> <y1> <z1> <x2> <y2> <z2> <dim>（按 mine/ 下的白/黑名单挖框里的方块）；list mode|whitelist|blacklist list|add|del"));
                source.sendFeedback(Text.literal("§7  /" + name + " mine complex station ... §8- §7挖掘站：list、check（算落点 + 走过去开箱检查）、show、safe_point / food_provider / totem_provider / item_collector / tool_cache / slime_cache"));
                source.sendFeedback(Text.literal("§7  /" + name + " waypoints ... §8- §7路径点 / 边 / 出生点"));
                source.sendFeedback(Text.literal("§7    §7子命令：show、edit、list、optimize default|radical"));
                source.sendFeedback(Text.literal("§7    §7  add_waypoint here|target|position <x> <y> <z> <dim> [name]"));
                source.sendFeedback(Text.literal("§7    §7  name_waypoint here|target|id <id> [name]（不写名字就是删掉名字）"));
                source.sendFeedback(Text.literal("§7    §7  del_waypoint here|target|id <id>|position <x> <y> <z> <dim>|name <name>"));
                source.sendFeedback(Text.literal("§7    §7  add_edge <id1> <id2>、del_edge <负数id>、set_spawn <选择器>、del_spawn"));
                source.sendFeedback(Text.literal("§7  /" + name + " config ... §8- §7配置（debug / world / actions move_speed、max_descent_speed、interaction_range、attack_range、attack_wall_range、anti_kick_delay、anti_kick_off_time、network_delay_ticks、wait_ticks_after_dimension_teleport、mending_max_wait_exp_seconds、mending_detect_interval_ticks、mine_simple_range、mine_simple_wall_range、mine_simple_max_blocks_per_tick、mine_tool_switch_interval_ticks、mine_max_vertical_speed_blocks_per_tick、mine_max_horizontal_speed_blocks_per_tick）"));
                source.sendFeedback(Text.literal("§7  /" + name + " account [player_id] §8- §7换成名为 player_id 的离线账户：下线后重新加入刚才的世界（不带参数看当前账户）"));
                source.sendFeedback(Text.literal("§7  item_list 例：§f{\"minecraft:cobblestone\": 65, \"minecraft:grass_block\": 13}"));
                return 1;
            })
            .then(MoveCommand.build())
            .then(ContainerCommand.build())
            .then(RecipeCommand.build())
            .then(StockCommand.build())
            .then(MaterialsCommand.build())
            .then(DumpCommand.build())
            .then(AttackCommand.build())
            .then(MendingCommand.build())
            .then(MineCommand.build())
            .then(WaypointsCommand.build())
            .then(ConfigCommand.build())
            .then(AccountCommand.build());
    }
}
