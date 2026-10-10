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
 *
 * <p>要合的次数超过一格能叠的上限时，{@link CraftPlan#rounds()} 会是好几轮：
 * 摆一轮 → 合完（原版一次 QuickMove 会把这一轮能合的都合掉）→ 再摆下一轮。
 */
public class CraftAction extends Action {

    /** 界面打开之后等几 tick 再动，保证槽位数据同步完了。 */
    private static final int SETTLE_TICKS = 2;

    /** 每次 QuickMove 之后等几 tick，等服务器把新的成品 / 输入格子同步回来。 */
    private static final int RESULT_WAIT_TICKS = 4;

    private static final int MAX_OPEN_WAIT_TICKS = 60;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    /** 一轮里最多点几次成品格（正常一次就够，材料没被清空时才补点）。 */
    private static final int MAX_ROUND_CLICKS = 3;

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
    /** 点了几次成品格（原版一次 shift 点击会把能合成的都合掉，所以通常只有 1 次）。 */
    private int clicks;
    /** 这一轮点了几次成品格。 */
    private int roundClicks;
    /** 第几轮（0 开始）。 */
    private int round;
    /** 已经摆出去合的次数。 */
    private int placedCrafts;
    private int recipeIndex = -1;

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

        ChatUtils.info(name() + "完成：" + id(plan.target()) + " x" + plan.count()
            + "（点了 " + clicks + " 次成品格，摆了 " + (round + (phase == Phase.DONE ? 0 : 1)) + " 轮）");
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

    /** 摆这一轮的材料：每 tick 一下点击。 */
    private void tickPlace(MinecraftClient mc, ClientPlayerEntity player) {
        int amount = plan.amountForRound(round);

        if (cellIndex >= plan.cells().size()) {
            placedCrafts += amount;
            clicks = 0;
            roundClicks = 0;
            recipeIndex = -1;   // 切石机换了输入之后要重新选一次配方
            settleTicks = 0;
            phase = Phase.WAIT_RESULT;
            ChatUtils.debug("第 " + (round + 1) + "/" + plan.rounds() + " 轮材料摆好了（每格 "
                + amount + " 个）");
            return;
        }

        CraftPlan.Cell cell = plan.cells().get(cellIndex);
        int slotId = gridSlotId(cell.gridSlot());
        int have = slotStack(slotId).getCount();
        int need = amount - have;
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
            return;
        }

        int source = findSource(cell.item(), need);
        if (source < 0) {
            fail("主背包里找不到足够的 " + id(cell.item()) + "（这一轮每格要 " + amount
                + " 个，还差 " + need + " 个）");
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

    /**
     * QuickMove 成品。
     *
     * <p>注意：原版 shift+点击成品格是「一次把这一轮能合成的都合成掉」（工作台就是这样），
     * 所以绝大多数情况点一次就够了。这里用「材料还在不在格子里」来判断还需不需要再点。
     */
    private void tickCraft(MinecraftClient mc, ClientPlayerEntity player) {
        // 切石机：先把要切的配方选上
        if (plan.station() == CraftPlan.Station.STONECUTTER && recipeIndex < 0) {
            selectStonecutterRecipe(mc, player);
            return;
        }

        // 格子里没材料了 → 这一轮完事
        if (!hasMaterials()) {
            endRound();
            return;
        }

        if (roundClicks >= MAX_ROUND_CLICKS) {
            ChatUtils.debug("这一轮点了 " + roundClicks + " 次成品，格子里还剩材料，先收工（理论上应该正好取完）");
            endRound();
            return;
        }

        int resultSlot = resultSlotId();
        ItemStack result = slotStack(resultSlot);
        if (result.isEmpty()) {
            fail("成品格是空的（第 " + (round + 1) + "/" + plan.rounds() + " 轮，已点 "
                + roundClicks + " 次），材料可能没摆对");
            return;
        }
        if (!result.isOf(plan.target())) {
            fail("成品格是 " + id(result.getItem()) + "，不是 " + id(plan.target()));
            return;
        }

        click(mc, player, resultSlot, 0, SlotActionType.QUICK_MOVE);
        clicks++;
        roundClicks++;
        settleTicks = 0;
        phase = Phase.WAIT_RESULT;
    }

    /** 这一轮结束：还有轮次就接着摆，没有就收工。 */
    private void endRound() {
        round++;
        cellIndex = 0;
        if (round >= plan.rounds()) {
            phase = Phase.DONE;
            return;
        }
        settleTicks = 0;
        phase = Phase.PLACE;
        ChatUtils.debug("接着摆第 " + (round + 1) + "/" + plan.rounds() + " 轮材料");
    }

    /** 格子里还有没有材料：工作台看 9 个输入格，切石机看那唯一一格。 */
    private boolean hasMaterials() {
        if (plan.station() == CraftPlan.Station.STONECUTTER) {
            return !slotStack(StonecutterScreenHandler.INPUT_ID).isEmpty();
        }
        for (Slot slot : ((CraftingScreenHandler) handler).getInputSlots()) {
            if (!slot.getStack().isEmpty()) return true;
        }
        return false;
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

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
