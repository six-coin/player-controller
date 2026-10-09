package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.recipe.CraftPlan;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc recipe ...} 的执行体：打开工作站的工作台 / 切石机，按计划摆好材料，
 * 然后反复 QuickMove 成品（原版 QuickMove 是从最后一格往前填，所以成品会进快捷栏靠后的格子）。
 *
 * <p>材料只从主背包 27 格（不含快捷栏）里拿；每 tick 只点一下，点完看本地状态决定下一步。
 */
public class CraftAction extends Action {

    /** 界面打开之后等几 tick 再动，保证槽位数据同步完了。 */
    private static final int SETTLE_TICKS = 2;

    /** 每次 QuickMove 之后等几 tick，等服务器把新的成品格子同步回来。 */
    private static final int RESULT_WAIT_TICKS = 2;

    private static final int MAX_OPEN_WAIT_TICKS = 60;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    private enum Phase {
        OPENING,
        SETTLE,
        PLACE,
        WAIT_RESULT,
        CRAFT,
        DONE
    }

    private final CraftPlan plan;
    private final BlockPos stationPos;

    private ScreenHandler handler;
    private Phase phase = Phase.OPENING;
    private int openWaitTicks;
    private int settleTicks;
    private int totalTicks;
    private int cellIndex;
    private int crafts;
    private int recipeIndex = -1;

    /** 开始时快捷栏里有几个目标物品，收尾时对比一下。 */
    private int hotbarBefore;

    public CraftAction(CraftPlan plan, BlockPos stationPos) {
        this.plan = plan;
        this.stationPos = stationPos;
    }

    @Override
    public String name() {
        return plan.station() == CraftPlan.Station.CRAFTING_TABLE ? "工作台合成" : "切石机切石";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        ScreenSuppressor.acquire();

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("没有玩家或世界");
            return;
        }

        hotbarBefore = countInHotbar(player, plan.target());
        ChatUtils.debug("开始 " + plan.describe());
        ContainerOpener.open(mc, player, stationPos);
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

        if (handler != null && player.currentScreenHandler != handler) {
            fail("界面被关掉了");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(mc, player);
            case SETTLE -> tickSettle();
            case PLACE -> tickPlace(mc, player);
            case WAIT_RESULT -> tickWaitResult();
            case CRAFT -> tickCraft(mc, player);
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        closeScreen();
        ScreenSuppressor.release();

        if (failureReason() != null) {
            ChatUtils.error(name() + "失败：" + failureReason());
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            int now = countInHotbar(mc.player, plan.target());
            ChatUtils.info(name() + "完成：" + id(plan.target()) + " x" + plan.count()
                + "，快捷栏里从 " + hotbarBefore + " 个变成 " + now + " 个（如果数目不对，看 debug 日志）");
        }
    }

    // ------------------------------------------------------------------
    // 各阶段
    // ------------------------------------------------------------------

    private void tickOpening(MinecraftClient mc, ClientPlayerEntity player) {
        openWaitTicks++;
        ScreenHandler current = player.currentScreenHandler;

        if (current != null && current != player.playerScreenHandler
            && !ContainerCacheTracker.isCreativeInventory(mc) && isOurHandler(current)) {
            handler = current;
            settleTicks = 0;
            phase = Phase.SETTLE;
            ChatUtils.debug("界面已打开（" + current.getClass().getSimpleName() + "），等 "
                + SETTLE_TICKS + " tick 同步");
            return;
        }

        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("打开" + (plan.station() == CraftPlan.Station.CRAFTING_TABLE ? "工作台" : "切石机")
                + "超时（" + openWaitTicks + " tick）");
        }
    }

    private boolean isOurHandler(ScreenHandler current) {
        return plan.station() == CraftPlan.Station.CRAFTING_TABLE
            ? current instanceof CraftingScreenHandler
            : current instanceof StonecutterScreenHandler;
    }

    private void tickSettle() {
        settleTicks++;
        if (settleTicks < SETTLE_TICKS) return;
        phase = Phase.PLACE;
    }

    /** 摆材料：每 tick 一下点击。 */
    private void tickPlace(MinecraftClient mc, ClientPlayerEntity player) {
        if (cellIndex >= plan.cells().size()) {
            crafts = 0;
            settleTicks = 0;
            phase = Phase.WAIT_RESULT;
            return;
        }

        CraftPlan.Cell cell = plan.cells().get(cellIndex);
        int slotId = gridSlotId(cell.gridSlot());
        int have = slotStack(slotId).getCount();
        int need = cell.amount() - have;
        ItemStack cursor = handler.getCursorStack();

        // 手上有东西
        if (!cursor.isEmpty()) {
            if (need <= 0 || !cursor.isOf(cell.item())) {
                returnCursor(player);
                return;
            }
            if (cursor.getCount() <= need) {
                click(mc, player, slotId, 0, SlotActionType.PICKUP);   // 整叠放进去
            } else {
                click(mc, player, slotId, 1, SlotActionType.PICKUP);   // 右键放一个
            }
            return;
        }

        if (need <= 0) {
            cellIndex++;
            ChatUtils.debug("格子 " + cell.gridSlot() + " 摆好了（" + cell.amount() + " 个 "
                + id(cell.item()) + "）");
            return;
        }

        int source = findSource(cell.item(), need);
        if (source < 0) {
            fail("主背包里找不到足够的 " + id(cell.item()) + "（还差 " + need + " 个）");
            return;
        }
        click(mc, player, source, 0, SlotActionType.PICKUP);
    }

    private void tickWaitResult() {
        // 摆完之后成品格要等服务器算一遍；切石机还没选配方的话下一步会去选
        settleTicks++;
        if (settleTicks < RESULT_WAIT_TICKS) return;
        phase = Phase.CRAFT;
    }

    /** 反复 QuickMove 成品。 */
    private void tickCraft(MinecraftClient mc, ClientPlayerEntity player) {
        // 切石机：先把要切的配方选上
        if (plan.station() == CraftPlan.Station.STONECUTTER && recipeIndex < 0) {
            selectStonecutterRecipe(mc, player);
            return;
        }

        if (crafts >= plan.crafts()) {
            phase = Phase.DONE;
            return;
        }

        int resultSlot = resultSlotId();
        ItemStack result = slotStack(resultSlot);
        if (result.isEmpty()) {
            fail("成品格是空的（第 " + (crafts + 1) + "/" + plan.crafts() + " 次），材料可能没摆对");
            return;
        }
        if (!result.isOf(plan.target())) {
            fail("成品格是 " + id(result.getItem()) + "，不是 " + id(plan.target()));
            return;
        }

        click(mc, player, resultSlot, 0, SlotActionType.QUICK_MOVE);
        crafts++;
        settleTicks = 0;
        phase = Phase.WAIT_RESULT;
    }

    // ------------------------------------------------------------------
    // 切石机选配方
    // ------------------------------------------------------------------

    private void selectStonecutterRecipe(MinecraftClient mc, ClientPlayerEntity player) {
        if (mc.world == null) {
            fail("没有世界");
            return;
        }

        CuttingRecipeDisplay.Grouping<StonecuttingRecipe> grouping =
            ((StonecutterScreenHandler) handler).getAvailableRecipes();
        if (grouping == null || grouping.isEmpty()) {
            if (++settleTicks > 40) fail("切石机没列出可选配方（输入格里的东西对吗？）");
            return;
        }

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        int index = 0;
        for (CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe> entry : grouping.entries()) {
            for (ItemStack option : entry.recipe().optionDisplay().getStacks(context)) {
                if (!option.isEmpty() && option.isOf(plan.target())) {
                    recipeIndex = index;
                    mc.interactionManager.clickButton(handler.syncId, index);
                    ChatUtils.debug("切石机：选中第 " + index + " 个配方（共 "
                        + grouping.size() + " 个选项），等成品格");
                    settleTicks = 0;
                    phase = Phase.WAIT_RESULT;   // 等服务器把成品格算出来
                    return;
                }
            }
            index++;
        }

        fail("切石机的选项里没有能切出 " + id(plan.target()) + " 的");
    }

    // ------------------------------------------------------------------
    // 格子 / 点击
    // ------------------------------------------------------------------

    /** 计划里的格子序号 → 界面里的槽位号。 */
    private int gridSlotId(int gridSlot) {
        if (plan.station() == CraftPlan.Station.STONECUTTER) {
            return StonecutterScreenHandler.INPUT_ID;   // 切石机只有那唯一一格
        }
        List<Slot> inputs = ((CraftingScreenHandler) handler).getInputSlots();
        return inputs.get(Math.min(gridSlot, inputs.size() - 1)).id;
    }

    private int resultSlotId() {
        return plan.station() == CraftPlan.Station.CRAFTING_TABLE
            ? CraftingScreenHandler.RESULT_ID
            : StonecutterScreenHandler.OUTPUT_ID;
    }

    private ItemStack slotStack(int slotId) {
        return handler.slots.get(slotId).getStack();
    }

    private void click(MinecraftClient mc, ClientPlayerEntity player, int slotId, int button, SlotActionType type) {
        mc.interactionManager.clickSlot(handler.syncId, slotId, button, type, player);
    }

    /** 主背包（27 格，不含快捷栏）里装着这个物品、而且优先正好不超过 need 个的格子。 */
    private int findSource(Item item, int need) {
        int fallback = -1;
        for (Slot slot : mainSlots()) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !stack.isOf(item)) continue;
            if (stack.getCount() <= need) return slot.id;
            if (fallback < 0) fallback = slot.id;
        }
        return fallback;
    }

    /** 把手上的东西放回主背包（优先找放得下的格子）。 */
    private void returnCursor(ClientPlayerEntity player) {
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) return;

        int target = -1;
        int empty = -1;
        for (Slot slot : mainSlots()) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                if (empty < 0) empty = slot.id;
                continue;
            }
            if (stack.isOf(cursor.getItem())
                && stack.getMaxCount() - stack.getCount() >= cursor.getCount()) {
                target = slot.id;
                break;
            }
        }
        if (target < 0) target = empty;
        if (target < 0) {
            fail("主背包放不下手上剩下的 " + id(cursor.getItem()) + " x" + cursor.getCount());
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        click(mc, player, target, 0, SlotActionType.PICKUP);
    }

    /** 界面里属于玩家主背包（不含快捷栏）的格子。 */
    private List<Slot> mainSlots() {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory
                && slot.getIndex() >= 9 && slot.getIndex() < 36) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;
        mc.player.closeHandledScreen();
    }

    private static int countInHotbar(ClientPlayerEntity player, Item item) {
        int total = 0;
        for (int i = 36; i < 45; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
