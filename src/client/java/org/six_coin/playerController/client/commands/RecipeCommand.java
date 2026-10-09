package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.CraftAction;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.recipe.CraftPlan;
import org.six_coin.playerController.client.recipe.RecipePlanner;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * {@code /pc recipe crafting_table <item> <count>} / {@code /pc recipe stonecutter <item> <count>}。
 *
 * <p>在**工作站**的工作台 / 切石机上做东西。执行前会依次检查：
 * <ol>
 *   <li>你本人站在工作站的站立点上，工作站设了对应方块、而且那里真的是那个方块；</li>
 *   <li>快捷栏第 3~9 格有足够空位（成品会被 QuickMove 到快捷栏靠后的格子）；</li>
 *   <li>主背包 27 格（不含快捷栏）里有这个配方需要的材料，而且能一次摆进格子里
 *       （每格要放「合成次数」个，不能超过那个物品的堆叠上限）；</li>
 *   <li>数量是 1~64，而且是单次产量的整数倍。</li>
 * </ol>
 *
 * <p>规划细节见 {@link RecipePlanner}，执行细节见 {@link CraftAction}。
 */
public final class RecipeCommand {

    private RecipeCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("recipe")
            .then(ClientCommandManager.literal("crafting_table")
                .then(ClientCommandManager.argument("item", ItemIdArgumentType.itemId())
                    .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(context -> run(context, false)))))
            .then(ClientCommandManager.literal("stonecutter")
                .then(ClientCommandManager.argument("item", ItemIdArgumentType.itemId())
                    .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(context -> run(context, true)))));
    }

    private static int run(CommandContext<FabricClientCommandSource> context, boolean stonecutter) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        Item item = ItemIdArgumentType.getItem(context, "item");
        int count = IntegerArgumentType.getInteger(context, "count");
        StationPart part = stonecutter ? StationPart.STONECUTTER : StationPart.CRAFTING_TABLE;
        String shape = stonecutter ? "切石机" : "工作台";

        // 1. 必须站在站立点上，工作站要设了对应的方块、而且那里真的是那个方块
        StationPos stand = StationManager.get().single(StationPart.STAND_POINT);
        if (stand == null) {
            source.sendError(Text.literal("还没有设置工作站站立点：先站到工作站中间，/pc station stand_point set_here"));
            return 0;
        }
        if (!stand.dimension().equals(DimensionUtils.current())) {
            source.sendError(Text.literal("站立点在 " + stand.describe() + "，你现在在 "
                + DimensionUtils.display(DimensionUtils.current()) + "，先过去"));
            return 0;
        }
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null || !here.equals(stand.pos())) {
            source.sendError(Text.literal("必须站在站立点 " + stand.coordString() + " 才能合成（你现在在 "
                + (here == null ? "?" : here.toShortString()) + "）"));
            return 0;
        }

        StationPos stationPos = StationManager.get().single(part);
        if (stationPos == null) {
            source.sendError(Text.literal("工作站还没有设置" + part.display() + "（/pc station "
                + part.id() + " set_target）"));
            return 0;
        }
        if (!stationPos.dimension().equals(stand.dimension())
            || !PlayerUtils.isWithinReachFrom(stand.pos(), stationPos.pos())) {
            source.sendError(Text.literal(part.display() + " " + stationPos.coordString()
                + " 不在站立点的触及范围内"));
            return 0;
        }
        BlockState state = mc.world.getBlockState(stationPos.pos());
        boolean rightBlock = stonecutter
            ? state.getBlock() instanceof StonecutterBlock
            : state.getBlock() instanceof CraftingTableBlock;
        if (!rightBlock) {
            source.sendError(Text.literal(part.display() + " " + stationPos.coordString() + " 那里是 "
                + ContainerTypes.idOf(state) + "，不是" + shape));
            return 0;
        }

        // 2. 快捷栏第 3~9 格要有足够空位（成品 QuickMove 到快捷栏靠后的格子）
        int neededSlots = (count + item.getMaxCount() - 1) / item.getMaxCount();
        int freeSlots = freeHotbarSlots(player);
        if (freeSlots < neededSlots) {
            source.sendError(Text.literal("快捷栏第 3~9 格只剩 " + freeSlots + " 个空位，装 "
                + count + " 个 " + id(item) + " 需要 " + neededSlots + " 格"));
            return 0;
        }

        // 3. 配方规划（材料够不够、能不能一次摆完、数量对不对）
        RecipePlanner.Result result = stonecutter
            ? RecipePlanner.planStonecutting(item, count)
            : RecipePlanner.planCrafting(item, count);
        if (!result.ok()) {
            source.sendError(Text.literal("不能这么做：" + result.error()));
            return 0;
        }

        CraftPlan plan = result.plan();
        source.sendFeedback(Text.literal("已提交任务：" + plan.describe()));
        ActionManager.get().submit(new CraftAction(plan, stationPos.pos()));
        return 1;
    }

    /** 快捷栏第 3~9 格（背包下标 2~8）空着几格。 */
    private static int freeHotbarSlots(ClientPlayerEntity player) {
        int free = 0;
        for (int i = 2; i <= 8; i++) {
            if (player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private static String id(Item item) {
        return net.minecraft.registry.Registries.ITEM.getId(item).toString();
    }
}
