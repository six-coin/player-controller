package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.StockPart2Action;
import org.six_coin.playerController.client.action.StockStartAction;
import org.six_coin.playerController.client.stock.StockManager;

import java.io.IOException;

/**
 * {@code /pc stock ...} —— 备货。
 *
 * <pre>
 * /pc stock add_task &lt;名字&gt;         新建任务（建 stock/&lt;名字&gt;/ 文件夹 + 记到 stock.json）
 * /pc stock del_task &lt;名字&gt;         删除任务（删文件夹 + 删记录）
 * /pc stock task &lt;名字&gt; start        完整跑一遍：第一部分（检查 → 取货 → 分盒重装）+ 第二部分阶段1、阶段2
 * /pc stock task &lt;名字&gt; part2        只跑第二部分阶段1、阶段2（station_data 从 debug/station_data.json 读，
 *                                    方便反复调试，不用每次重跑第一部分）
 * </pre>
 */
public final class StockCommand {

    private StockCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("stock")
            .executes(StockCommand::status)
            .then(ClientCommandManager.literal("add_task")
                .then(ClientCommandManager.argument("task_name", StringArgumentType.word())
                    .executes(StockCommand::addTask)))
            .then(ClientCommandManager.literal("del_task")
                .then(ClientCommandManager.argument("task_name", StringArgumentType.word())
                    .suggests((context, builder) -> CommandSource.suggestMatching(StockManager.get().tasks(), builder))
                    .executes(StockCommand::delTask)))
            .then(ClientCommandManager.literal("task")
                .then(ClientCommandManager.argument("task_name", StringArgumentType.word())
                    .suggests((context, builder) -> CommandSource.suggestMatching(StockManager.get().tasks(), builder))
                    .then(ClientCommandManager.literal("start")
                        .executes(StockCommand::start))
                    .then(ClientCommandManager.literal("part2")
                        .executes(StockCommand::startPart2))));
    }

    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        StockManager manager = StockManager.get();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7备货任务（world_" + manager.loadedWorld()
            + "）：" + manager.tasks().size() + " 个"));
        if (manager.tasks().isEmpty()) {
            source.sendFeedback(Text.literal("  §8（还没有任务，用 /pc stock add_task <名字> 新建）"));
        } else {
            for (String task : manager.tasks()) {
                source.sendFeedback(Text.literal("  " + task + " §8→ " + manager.currentTaskDir(task)));
            }
        }
        return 1;
    }

    private static int addTask(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        try {
            if (!StockManager.get().addTask(task)) {
                source.sendFeedback(Text.literal("已经有 " + task + " 这个任务了（文件夹 "
                    + StockManager.get().currentTaskDir(task) + "）"));
                return 1;
            }
        } catch (IOException e) {
            source.sendError(Text.literal("新建任务失败：" + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Text.literal("已新建任务 " + task + "，把这个任务的材料清单放到 "
            + StockManager.get().currentMaterialFile(task)));
        return 1;
    }

    private static int delTask(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        try {
            if (!StockManager.get().delTask(task)) {
                source.sendError(Text.literal("没有 " + task + " 这个任务"));
                return 0;
            }
        } catch (IOException e) {
            source.sendError(Text.literal("删除任务失败：" + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Text.literal("已删除任务 " + task + "（文件夹和记录都没了）"));
        return 1;
    }

    private static int start(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        if (!StockManager.get().has(task)) {
            source.sendError(Text.literal("没有 " + task + " 这个任务（/pc stock add_task " + task + "）"));
            return 0;
        }
        if (source.getPlayer() == null || source.getWorld() == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        source.sendFeedback(Text.literal("开始备货任务 " + task
            + " —— 第一部分（阶段1 预检查 → 阶段2 取货 → 阶段3 分盒重装）+ 第二部分阶段1、阶段2"));
        ActionManager.get().submit(new StockStartAction(task));
        return 1;
    }

    /** 只跑第二部分阶段1、阶段2（station_data 从 debug/station_data.json 读）。 */
    private static int startPart2(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String task = StringArgumentType.getString(context, "task_name");

        if (!StockManager.get().has(task)) {
            source.sendError(Text.literal("没有 " + task + " 这个任务（/pc stock add_task " + task + "）"));
            return 0;
        }
        if (source.getPlayer() == null || source.getWorld() == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        source.sendFeedback(Text.literal("只跑第二部分 " + task
            + " —— 阶段1（容器汇总 + 处理材料清单）+ 阶段2（取货）；"
            + "station_data 用 debug/station_data.json 里那份"));
        ActionManager.get().submit(new StockPart2Action(task));
        return 1;
    }
}
