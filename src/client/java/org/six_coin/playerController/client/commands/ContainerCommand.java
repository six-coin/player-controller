package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.ContainerGetAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * {@code /pc container get from_target <item_list>}
 *
 * <p>打开玩家准星指着的那个方块（必须是容器），按 item_list 把东西拿进主背包。
 * item_list 是一段 JSON，键是物品 id、值是还要多少个：
 *
 * <pre>
 *   /pc container get from_target {"minecraft:cobblestone": 65, "minecraft:grass_block": 13}
 * </pre>
 *
 * <p>具体规则见 {@link ContainerGetAction}；命令跑完会把修改后的 item_list 原样输出到聊天栏。
 */
public final class ContainerCommand {

    private ContainerCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("container")
            .then(ClientCommandManager.literal("get")
                .then(ClientCommandManager.literal("from_target")
                    .then(ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                        .executes(ContainerCommand::getFromTarget))))
            .then(ContainerCacheCommand.build());
    }

    private static int getFromTarget(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        if (source.getPlayer() == null || source.getWorld() == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        // 1. 解析 item_list
        String raw = StringArgumentType.getString(context, "item_list");
        ItemList itemList;
        try {
            itemList = ItemList.parse(raw);
        } catch (ItemList.ParseException e) {
            source.sendError(Text.literal("item_list 解析失败: " + e.getMessage()));
            ChatUtils.debug("item_list 原文: " + raw);
            return 0;
        }
        ChatUtils.debug("解析 item_list: " + itemList.describe());

        // 2. 目标 = 准星看到的方块
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            source.sendError(Text.literal("方块 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）"));
            return 0;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            source.sendError(Text.literal("没有世界"));
            return 0;
        }
        if (mc.world.getBlockState(pos).isAir()) {
            source.sendError(Text.literal(pos.toShortString() + " 是空气，不是容器"));
            return 0;
        }
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            source.sendError(Text.literal(pos.toShortString() + " 不是容器（没有物品栏）"));
            return 0;
        }

        // 3. 交给动作执行
        ActionManager.get().submit(new ContainerGetAction(pos, itemList));
        source.sendFeedback(Text.literal("已提交任务：从 " + pos.toShortString() + " 取 "
            + itemList.describe() + "（结束后会输出剩下的 item_list）"));
        return 1;
    }
}
