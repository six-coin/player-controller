package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.stock.StockTask;

import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code /pc stock ...} —— 备货（含工作站配置 {@code /pc stock station ...}）。
 *
 * <pre>
 * /pc stock station ...              工作站：站位点、工作台、切石机、各容器（见 StationCommand）
 * /pc stock add_task &lt;名字&gt;         新建任务（建 stock/tasks/&lt;名字&gt;/ 文件夹 + 记到 stock/tasks.json）
 * /pc stock del_task &lt;名字&gt;         删除任务（删文件夹 + 删记录）
 * /pc stock task &lt;名字&gt; generate_materials_list
 *                                    把 original.json（litematica 导出的投影材料表）处理成
 *                                    materials.json（材料树，见 MaterialsCommand）
 * /pc stock task &lt;名字&gt; start_collect
 *                                    完整跑一遍：第一部分（检查 → 取货 → 分盒重装）+ 第二部分（预检查 → 取货 → 合成）
 * </pre>
 *
 * <p>station_data 不落盘（只在这一轮任务的内存里传），所以没有「只跑第二部分」这种单独入口。
 */
public final class StockCommand {

    private StockCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("stock")
            .executes(StockCommand::status)
            .then(StationCommand.build())
            .then(ClientCommands.literal("add_task")
                .then(ClientCommands.argument("task_name", StringArgumentType.word())
                    .executes(StockCommand::addTask)))
            .then(ClientCommands.literal("del_task")
                .then(ClientCommands.argument("task_name", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(StockManager.get().tasks(), builder))
                    .executes(StockCommand::delTask)))
            .then(ClientCommands.literal("task")
                .then(ClientCommands.argument("task_name", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(StockManager.get().tasks(), builder))
                    .then(ClientCommands.literal("generate_materials_list")
                        .executes(StockCommand::generateMaterialsList))
                    .then(ClientCommands.literal("start_collect")
                        .executes(StockCommand::startCollect))));
    }

    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        StockManager manager = StockManager.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7备货任务（world_" + manager.loadedWorld()
            + "）：" + manager.tasks().size() + " 个"));
        if (manager.tasks().isEmpty()) {
            source.sendFeedback(Component.literal("  §8（还没有任务，用 /pc stock add_task <名字> 新建）"));
        } else {
            for (String task : manager.tasks()) {
                source.sendFeedback(Component.literal("  " + task + " §8→ " + manager.currentTaskDir(task)));
            }
        }
        return 1;
    }

    private static int addTask(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        try {
            if (!StockManager.get().addTask(task)) {
                source.sendFeedback(Component.literal("已经有 " + task + " 这个任务了（文件夹 "
                    + StockManager.get().currentTaskDir(task) + "）"));
                return 1;
            }
        } catch (IOException e) {
            source.sendError(Component.literal("新建任务失败：" + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Component.literal("已新建任务 " + task + "，把 litematica 导出的 original.json 放到 "
            + StockManager.get().currentOriginalFile(task)
            + "，再跑 /pc stock task " + task + " generate_materials_list"));
        return 1;
    }

    private static int delTask(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        try {
            if (!StockManager.get().delTask(task)) {
                source.sendError(Component.literal("没有 " + task + " 这个任务"));
                return 0;
            }
        } catch (IOException e) {
            source.sendError(Component.literal("删除任务失败：" + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Component.literal("已删除任务 " + task + "（文件夹和记录都没了）"));
        return 1;
    }

    /** {@code /pc stock task <名字> generate_materials_list}：original.json -> materials.json。 */
    private static int generateMaterialsList(CommandContext<FabricClientCommandSource> context) {
        String task = StringArgumentType.getString(context, "task_name");
        if (!StockManager.get().has(task)) {
            context.getSource().sendError(Component.literal("没有 " + task + " 这个任务（/pc stock add_task " + task + "）"));
            return 0;
        }
        return MaterialsCommand.generate(context, task);
    }

    private static int startCollect(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        if (!StockManager.get().has(task)) {
            source.sendError(Component.literal("没有 " + task + " 这个任务（/pc stock add_task " + task + "）"));
            return 0;
        }
        if (source.getPlayer() == null || source.getLevel() == null) {
            source.sendError(Component.literal("没有玩家或世界"));
            return 0;
        }
        if (!Files.exists(StockManager.get().currentMaterialFile(task))) {
            source.sendError(Component.literal("还没有材料清单 "
                + StockManager.get().currentMaterialFile(task)
                + "（先跑 /pc stock task " + task + " generate_materials_list）"));
            return 0;
        }

        source.sendFeedback(Component.literal("开始备货任务 " + task
            + " —— 第一部分（预检查 → 取货 → 分盒重装）+ 第二部分（预检查 → 取货 → 合成）"));
        ActionManager.get().submit(new StockTask(task));
        return 1;
    }
}

