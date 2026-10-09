package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.ContainerGetAction;
import org.six_coin.playerController.client.action.ContainerPutAction;
import org.six_coin.playerController.client.container.CachedContainer;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.function.BiFunction;

/**
 * {@code /pc container get ...} —— 打开容器，按 item_list 把东西拿进主背包。
 *
 * <pre>
 *   /pc container get from_target {"minecraft:cobblestone": 65, "minecraft:grass_block": 13}
 *   /pc container get from_id 3 {"minecraft:cobblestone": 65}
 * </pre>
 *
 * <p>{@code from_target} 用的是准星指着的方块；{@code from_id} 用的是容器缓存里的某个编号。
 * 两个都要求目标在触及范围内、而且是容器，其余流程完全一样。
 *
 * <p>具体规则见 {@link ContainerGetAction}；命令跑完会把修改后的 item_list 原样输出到聊天栏，
 * 并且把这个容器在缓存里的物品列表刷新一遍。
 */
public final class ContainerCommand {

    private ContainerCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("container")
            .then(ClientCommandManager.literal("get")
                .then(ClientCommandManager.literal("from_target")
                    .then(ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                        .executes(ContainerCommand::getFromTarget)))
                .then(ClientCommandManager.literal("from_position")
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                        .suggests(LookSuggestions::x)
                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                            .suggests(LookSuggestions::y)
                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                .suggests(LookSuggestions::z)
                                .then(ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                                    .executes(ContainerCommand::getFromPosition))))))
                .then(ClientCommandManager.literal("from_id")
                    .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                            .executes(ContainerCommand::getFromId)))))
            .then(ClientCommandManager.literal("put")
                .then(putToTargetNode())
                .then(putToIdNode())
                .then(putToPositionNode()))
            .then(ContainerCacheCommand.build());
    }

    /** {@code put to_target <模式>}：准星指着的容器。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> putToTargetNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal("to_target");
        attachModes(node, (context, mode) -> putToTarget(context, mode));
        return node;
    }

    /** {@code put to_id <id> <模式>}：容器缓存编号。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> putToIdNode() {
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> id =
            ClientCommandManager.argument("id", IntegerArgumentType.integer());
        attachModes(id, (context, mode) -> putToId(context, mode));
        return ClientCommandManager.literal("to_id").then(id);
    }

    /** {@code put to_position <x> <y> <z> <模式>}：当前维度的坐标。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> putToPositionNode() {
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z =
            ClientCommandManager.argument("z", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        attachModes(z, (context, mode) -> putToPosition(context, mode));

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y =
            ClientCommandManager.argument("y", IntegerArgumentType.integer()).suggests(LookSuggestions::y).then(z);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x =
            ClientCommandManager.argument("x", IntegerArgumentType.integer()).suggests(LookSuggestions::x).then(y);

        return ClientCommandManager.literal("to_position").then(x);
    }

    /**
     * 把一个父节点下面挂上 all_items / all_shulker_boxes / everything 三个**兄弟**模式字面量。
     *
     * <p>注意别把它们串成父子（{@code all_items} 下面挂 {@code all_shulker_boxes}），
     * 那样命令根本走不通。
     */
    private static void attachModes(ArgumentBuilder<FabricClientCommandSource, ?> parent,
                                    BiFunction<CommandContext<FabricClientCommandSource>,
                                        ContainerPutAction.Mode, Integer> runner) {
        parent.then(ClientCommandManager.literal("all_items")
            .executes(context -> runner.apply(context, ContainerPutAction.Mode.ALL_ITEMS)));
        parent.then(ClientCommandManager.literal("all_shulker_boxes")
            .executes(context -> runner.apply(context, ContainerPutAction.Mode.ALL_SHULKER_BOXES)));
        parent.then(ClientCommandManager.literal("everything")
            .executes(context -> runner.apply(context, ContainerPutAction.Mode.EVERYTHING)));
    }

    // ------------------------------------------------------------------

    private static int getFromTarget(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        ItemList itemList = readItemList(source, context);
        if (itemList == null) return 0;

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return startGet(source, pos, itemList);
    }

    private static int getFromPosition(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        ItemList itemList = readItemList(source, context);
        if (itemList == null) return 0;

        BlockPos pos = new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
        return startGet(source, pos, itemList);
    }

    private static int getFromId(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        ItemList itemList = readItemList(source, context);
        if (itemList == null) return 0;

        int id = IntegerArgumentType.getInteger(context, "id");
        CachedContainer cached = ContainerCacheManager.get().byId(id);
        if (cached == null) {
            source.sendError(Text.literal("容器缓存里没有 #" + id));
            return 0;
        }

        String dimension = DimensionUtils.current();
        if (!cached.dimension().equals(dimension)) {
            source.sendError(Text.literal("缓存 #" + id + " 在 " + DimensionUtils.display(cached.dimension())
                + "，你现在在 " + DimensionUtils.display(dimension) + "，够不着"));
            return 0;
        }

        ChatUtils.debug("from_id #" + id + " → " + cached.type() + " " + cached.coordString());
        return startGet(source, cached.pos(), itemList);
    }

    // ------------------------------------------------------------------
    // put
    // ------------------------------------------------------------------

    private static int putToTarget(CommandContext<FabricClientCommandSource> context, ContainerPutAction.Mode mode) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Text.literal("你没有看向任何方块"));
            return 0;
        }
        return startPut(source, pos, mode);
    }

    private static int putToId(CommandContext<FabricClientCommandSource> context, ContainerPutAction.Mode mode) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        int id = IntegerArgumentType.getInteger(context, "id");
        CachedContainer cached = ContainerCacheManager.get().byId(id);
        if (cached == null) {
            source.sendError(Text.literal("容器缓存里没有 #" + id));
            return 0;
        }

        String dimension = DimensionUtils.current();
        if (!cached.dimension().equals(dimension)) {
            source.sendError(Text.literal("缓存 #" + id + " 在 " + DimensionUtils.display(cached.dimension())
                + "，你现在在 " + DimensionUtils.display(dimension) + "，够不着"));
            return 0;
        }

        ChatUtils.debug("put to_id #" + id + " → " + cached.type() + " " + cached.coordString());
        return startPut(source, cached.pos(), mode);
    }

    private static int putToPosition(CommandContext<FabricClientCommandSource> context, ContainerPutAction.Mode mode) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        BlockPos pos = new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
        return startPut(source, pos, mode);
    }

    /** {@code put} 共用的部分：校验触及范围和容器，然后提交任务。 */
    private static int startPut(FabricClientCommandSource source, BlockPos pos, ContainerPutAction.Mode mode) {
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

        ActionManager.get().submit(new ContainerPutAction(pos, mode));
        source.sendFeedback(Text.literal("已提交任务：把物品栏 27 格（不含快捷栏）里的 "
            + (mode == ContainerPutAction.Mode.ALL_ITEMS ? "物品（潜影盒不算）" : "潜影盒")
            + " 放进 " + pos.toShortString() + "（结束后输出 all_cleared）"));
        return 1;
    }

    // ------------------------------------------------------------------

    private static boolean hasPlayer(FabricClientCommandSource source) {
        if (source.getPlayer() != null && source.getWorld() != null) return true;
        source.sendError(Text.literal("没有玩家或世界"));
        return false;
    }

    /** 解析 item_list，出错就报错并返回 null。 */
    @Nullable
    private static ItemList readItemList(FabricClientCommandSource source,
                                         CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "item_list");
        try {
            ItemList itemList = ItemList.parse(raw);
            ChatUtils.debug("解析 item_list: " + itemList.describe());
            return itemList;
        } catch (ItemList.ParseException e) {
            source.sendError(Text.literal("item_list 解析失败: " + e.getMessage()));
            ChatUtils.debug("item_list 原文: " + raw);
            return null;
        }
    }

    /** {@code from_target} / {@code from_id} 共用的部分：校验触及范围和容器，然后提交任务。 */
    private static int startGet(FabricClientCommandSource source, BlockPos pos, ItemList itemList) {
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

        ActionManager.get().submit(new ContainerGetAction(pos, itemList));
        source.sendFeedback(Text.literal("已提交任务：从 " + pos.toShortString() + " 取 "
            + itemList.describe() + "（结束后会输出剩下的 item_list）"));
        return 1;
    }
}
