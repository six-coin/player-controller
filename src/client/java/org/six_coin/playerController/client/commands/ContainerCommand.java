package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import org.six_coin.playerController.client.action.ContainerAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ItemListParser;
import org.six_coin.playerController.client.util.ItemRequest;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.List;

/**
 * {@code /pc container [position_x] [position_y] [position_z] [item_list]}
 *
 * <p>坐标必须落在触及范围内，否则直接拒绝执行。
 * item_list 形如 {@code stone=64,dirt=32}（详见 {@link ItemListParser}）。
 */
public final class ContainerCommand {

    private ContainerCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("container")
            .then(ClientCommandManager.argument("position_x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("position_y", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("position_z", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                            .executes(ContainerCommand::execute)))));
    }

    private static int execute(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        if (source.getPlayer() == null || source.getWorld() == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "position_x");
        int y = IntegerArgumentType.getInteger(context, "position_y");
        int z = IntegerArgumentType.getInteger(context, "position_z");
        BlockPos pos = new BlockPos(x, y, z);

        // 1. 解析物品列表
        List<ItemRequest> requests;
        try {
            requests = ItemListParser.parse(StringArgumentType.getString(context, "item_list"));
        } catch (ItemListParser.ParseException e) {
            source.sendError(Text.literal("物品列表格式错误: " + e.getMessage()));
            return 0;
        }

        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < requests.size(); i++) {
            if (i > 0) summary.append(", ");
            summary.append(requests.get(i).displayName());
        }
        ChatUtils.debug("解析物品列表: " + summary);

        MinecraftClient mc = MinecraftClient.getInstance();

        // 2. 坐标必须在触及范围内，否则拒绝执行
        if (!PlayerUtils.isWithinReach(pos)) {
            String message = String.format("容器 %s 超出触及范围（距离 %.2f，触及范围 %.2f），拒绝执行",
                pos.toShortString(), PlayerUtils.eyeDistanceTo(pos), PlayerUtils.reach());
            source.sendError(Text.literal(message));
            ChatUtils.debug(message);
            return 0;
        }

        // 3. 该位置必须真的是一个容器
        if (mc.world.getBlockState(pos).isAir()) {
            source.sendError(Text.literal(pos.toShortString() + " 是空气，不是容器"));
            return 0;
        }
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            source.sendError(Text.literal(pos.toShortString() + " 不是容器（没有物品栏）"));
            return 0;
        }

        ChatUtils.debug(String.format("容器 %s 在触及范围内（距离 %.2f / %.2f）",
            pos.toShortString(), PlayerUtils.eyeDistanceTo(pos), PlayerUtils.reach()));

        // 4. 交给动作执行
        ActionManager.get().submit(new ContainerAction(pos, requests));
        source.sendFeedback(Text.literal("已提交任务：从 " + pos.toShortString() + " 取 " + summary));
        return 1;
    }
}
