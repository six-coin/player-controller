package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
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
import java.util.function.Consumer;

/**
 * {@code /pc container get ...} —— 打开容器，按 item_list 把东西拿进主背包。
 *
 * <pre>
 *   /pc container get from_target {"minecraft:cobblestone": 65, "minecraft:grass_block": 13}
 *   /pc container get from_id 3 {"minecraft:cobblestone": 65}
 *   /pc container get from_position 1 2 3 {"minecraft:cobblestone": 65}
 *   /pc container special_get from_position 1 2 3 only_item {"minecraft:cobblestone": 65}
 *   /pc container special_get from_id 3 only_one_shulker {"minecraft:cobblestone": 65}
 * </pre>
 *
 * <p>{@code from_target} 用的是准星指着的方块；{@code from_id} 用的是容器缓存里的某个编号；
 * {@code from_position} 用的是坐标。三个都要求目标在触及范围内、而且是容器，其余流程完全一样。
 *
 * <p>两个特殊模式（{@link ContainerGetAction.Mode}）写在 item_list **前面**：item_list 是贪婪参数，
 * 写在它后面的字面量只会被它吃进去。{@code only_item} 只拿物品形态的（潜影盒整个无视），
 * {@code only_one_shulker} 只找一个「里面装着 item_list 需要的东西」的潜影盒，放进快捷栏第三格
 * 且**不输出** item_list。
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
                .then(getNode("from_target", null, false))
                .then(getNode("from_position", "position", false))
                .then(getNode("from_id", "id", false)))
            .then(ClientCommandManager.literal("special_get")
                .then(getNode("from_target", null, true))
                .then(getNode("from_position", "position", true))
                .then(getNode("from_id", "id", true)))
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
     * 把一个父节点下面挂上各种模式字面量。
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
        parent.then(ClientCommandManager.literal("everything_include_hotbar")
            .executes(context -> runner.apply(context, ContainerPutAction.Mode.EVERYTHING_INCLUDE_HOTBAR)));
    }

    // ------------------------------------------------------------------

    /**
     * {@code get / special_get from_target|from_position|from_id} 三条命令，结构一样：
     * 参数（没有 / x y z / id）→ 具体挂什么在最后那层节点上（见 {@link #fromNode}）。
     *
     * <p>{@code special_get} 把模式字面量放在坐标**后面**、item_list **前面**，因为 item_list 是贪婪的
     * （贪到行尾），写在它后面的字面量只会被它吃进去：
     * <pre>
     *   /pc container special_get from_position &lt;x&gt; &lt;y&gt; &lt;z&gt; only_item &lt;item_list&gt;
     * </pre>
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> getNode(String name, @Nullable String arg,
                                                                            boolean special) {
        if (special) {
            return fromNode(name, arg, leaf -> {
                leaf.then(modeNode("only_item", ContainerGetAction.Mode.ONLY_ITEM));
                leaf.then(modeNode("only_one_shulker", ContainerGetAction.Mode.ONLY_ONE_SHULKER));
            });
        }
        return fromNode(name, arg, leaf -> leaf.then(
            ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                .executes(context -> runGet(context, ContainerGetAction.Mode.NORMAL))));
    }

    /** {@code only_item <item_list>} / {@code only_one_shulker <item_list>}。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> modeNode(String modeName,
                                                                             ContainerGetAction.Mode mode) {
        return ClientCommandManager.literal(modeName).then(
            ClientCommandManager.argument("item_list", StringArgumentType.greedyString())
                .executes(context -> runGet(context, mode)));
    }

    /**
     * 建出 {@code from_target} / {@code from_position x y z} / {@code from_id id}，
     * 最后那层节点交给 {@code attach} 挂东西（item_list 或者模式字面量）。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> fromNode(
        String name, @Nullable String arg,
        Consumer<ArgumentBuilder<FabricClientCommandSource, ?>> attach) {

        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal(name);
        if (arg == null) {
            attach.accept(node);
            return node;
        }
        if (arg.equals("id")) {
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> id =
                ClientCommandManager.argument("id", IntegerArgumentType.integer());
            attach.accept(id);
            return node.then(id);
        }

        // 注意顺序：必须先把东西挂到 z 上，再把 z 挂到 y 上。
        // brigadier 的 then(builder) 会**立刻 build 出一个 CommandNode**，之后再往那个 builder 上加子节点是没用的。
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z =
            ClientCommandManager.argument("z", IntegerArgumentType.integer()).suggests(LookSuggestions::z);
        attach.accept(z);

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> y =
            ClientCommandManager.argument("y", IntegerArgumentType.integer()).suggests(LookSuggestions::y).then(z);
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> x =
            ClientCommandManager.argument("x", IntegerArgumentType.integer()).suggests(LookSuggestions::x).then(y);
        return node.then(x);
    }

    /** get 的统一入口：按命令名找出容器位置，再交给动作。 */
    private static int runGet(CommandContext<FabricClientCommandSource> context, ContainerGetAction.Mode mode) {
        FabricClientCommandSource source = context.getSource();
        if (!hasPlayer(source)) return 0;

        ItemList itemList = readItemList(source, context);
        if (itemList == null) return 0;

        // 哪个 from_*：**按名字找**，别按下标 —— 命令树上前面还有 pc / container / get 这些节点
        String root = targetName(context);
        BlockPos pos;
        if (root.equals("from_id")) {
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
            pos = cached.pos();
        } else if (root.equals("from_position")) {
            pos = new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
        } else {
            pos = PlayerUtils.lookedAtBlock();
            if (pos == null) {
                source.sendError(Text.literal("你没有看向任何方块"));
                return 0;
            }
        }

        return startGet(source, pos, itemList, mode);
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
        source.sendFeedback(Text.literal("已提交任务：" + putDescription(mode) + " 放进 "
            + pos.toShortString() + "（结束后输出 all_cleared）"));
        return 1;
    }

    /** put 的提示文字：说清楚这一次会动哪几格。 */
    private static String putDescription(ContainerPutAction.Mode mode) {
        return switch (mode) {
            case ALL_ITEMS -> "把主背包 27 格（不含快捷栏）里的物品（潜影盒不算）";
            case ALL_SHULKER_BOXES -> "把主背包 27 格（不含快捷栏）里的潜影盒";
            case EVERYTHING -> "把主背包 27 格（不含快捷栏）里的东西（物品 + 潜影盒）";
            case EVERYTHING_INCLUDE_HOTBAR -> "把整个物品栏（27 格主背包 + 快捷栏 9 格）里的东西";
            case WANTED -> "把物品栏里 item_list 还要的东西";
        };
    }

    // ------------------------------------------------------------------

    /** 在解析出来的节点里找 {@code from_target} / {@code from_position} / {@code from_id}。 */
    private static String targetName(CommandContext<FabricClientCommandSource> context) {
        for (ParsedCommandNode<FabricClientCommandSource> node : context.getNodes()) {
            String name = node.getNode().getName();
            if (name.equals("from_target") || name.equals("from_position") || name.equals("from_id")) {
                return name;
            }
        }
        return "from_target";
    }

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

    /** {@code get from_*} 共用的部分：校验触及范围和容器，然后提交任务。 */
    private static int startGet(FabricClientCommandSource source, BlockPos pos, ItemList itemList,
                                ContainerGetAction.Mode mode) {
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

        ActionManager.get().submit(new ContainerGetAction(pos, itemList, false, mode));
        source.sendFeedback(Text.literal("已提交任务：从 " + pos.toShortString() + " 取 " + itemList.describe()
            + "（模式 " + modeName(mode) + "）"));
        return 1;
    }

    private static String modeName(ContainerGetAction.Mode mode) {
        return switch (mode) {
            case NORMAL -> "normal";
            case ONLY_ITEM -> "only_item";
            case ONLY_ONE_SHULKER -> "only_one_shulker";
        };
    }
}
