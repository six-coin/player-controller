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
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.recipe.CraftPlan;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc recipe ...} 的执行体：打开工作站的工作台 / 切石机，按计划摆好材料，
 * 然后 QuickMove 成品（原版 QuickMove 是从最后一格往前填，所以成品会进快捷栏靠后的格子）。
 *
 * <p>材料只从主背包 27 格（不含快捷栏）里拿；点完看本地状态决定下一步
 * （「一个一个往格子里放 1 个」那种会在一 tick 里连点几下，数量见配置 {@code actions.items_per_tick}）。
 *
 * <p>要合的次数超过一格能叠的上限时，{@link CraftPlan#rounds()} 会是好几轮：
 * 摆一轮 → 合完（原版一次 QuickMove 会把这一轮能合的都合掉）→ 再摆下一轮。
 *
 * <p><b>成品格每轮只点一次</b>（见 {@link #craft}），点完之后不去看成品格，
 * 而是每 tick 看物品栏里成品多了没有（最多 {@link #RESULT_WAIT_TICKS} tick）：
 * 成品格是服务端算的，点下去到客户端看到结果中间有延迟，而且客户端这一刻的格子数据
 * 有可能还是旧的（一 tick 连点很多下的时候服务端会把它的状态同步回来）——
 * 只看成品格会冤枉地报「成品格是空的」（其实已经合好了）。
 * 等成品格的那一段（{@link #tickReady}）也一样：等不到就回去重新摆一遍材料，而不是直接失败。
 */
public class CraftAction extends Action {

    /** 界面打开之后等几 tick 再动，保证槽位数据同步完了。 */
    private static final int SETTLE_TICKS = 2;

    /**
     * 材料摆好之后，等服务器把成品格算出来最多等几 tick（20 tick = 1 秒）。
     *
     * <p>等不到就回去重新摆一遍材料（见 {@link #retryPlace}）。
     */
    private static final int READY_WAIT_TICKS = 20;

    /**
     * 点了一次成品格之后，等物品栏里多出成品最多等几 tick（20 tick = 1 秒）。
     *
     * <p>合成是服务端做的，结果要过一会儿才同步回来，所以不能点完就立刻判断。
     */
    private static final int RESULT_WAIT_TICKS = 20;

    /** 等不到成品格时最多回头重摆几次。 */
    private static final int MAX_READY_RETRIES = 3;

    /**
     * 一格「够了」或者刚放完一批之后，还要连着几 tick 数量都不变才继续。
     *
     * <p>一 tick 连发一大串点击时，服务端会把它实际接受到的数量同步回来 —— 客户端那一刻的数字
     * 可能偏高（多放材料）也可能偏低（少放）。等它稳下来再算下一批，两头都不会错。
     */
    private static final int PLACE_SETTLE_TICKS = 2;

    private static final int MAX_OPEN_WAIT_TICKS = 60;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    private enum Phase {
        /** 等界面打开。 */
        OPENING,
        /** 等槽位同步。 */
        SETTLE,
        /** 摆这一轮的材料。 */
        PLACE,
        /** 等服务器把成品格算出来（切石机还要先选配方）。 */
        READY,
        /** 点了一次成品格，等物品栏里真的多出成品。 */
        WAIT_CRAFTED,
        /** 全部弄完。 */
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
    /** 点了几次成品格（每轮一次，所以正常情况下就是轮数）。 */
    private int clicks;
    /** 第几轮（0 开始）。 */
    private int round;
    private int recipeIndex = -1;

    /** 等成品格的计时 / 重试次数。 */
    private int readyTicks;
    private int readyRetries;
    /** 切石机等配方列表的计时。 */
    private int recipeWaitTicks;

    /** 点了成品格之后等结果的计时。 */
    private int waitTicks;
    /** 点成品格之前物品栏里有多少个成品（用来判断这一轮到底合出来没有）。 */
    private int craftedBefore;

    /** 上一批点击是往哪一格的、放完那一批之后这格有多少个（用来发现服务端把数量同步回去了）。 */
    private int lastCellIndex = -1;
    private int lastCellHave = -1;

    /** 这一格的数量连着几 tick 没变了（摆材料时用来等服务器同步回来）。 */
    private int placeSettleTicks;

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
            case READY -> tickReady(mc, player);
            case WAIT_CRAFTED -> tickWaitCrafted();
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

        output().info(name() + "完成：" + id(plan.target()) + " x" + plan.actualCount()
            + (plan.actualCount() == plan.count() ? "" : "（要 " + plan.count() + " 个，向上取整）")
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

    /**
     * 摆这一轮的材料：每 tick 把能放的一批放进去。
     *
     * <p><b>每批之前先等这一格的数量稳定下来</b>（连着 {@link #PLACE_SETTLE_TICKS} tick 没变才继续）：
     * 一 tick 连发一大串点击时，服务端会把它**实际**接受到的数量同步回来、覆盖掉客户端的本地预测，
     * 客户端这一刻看到的数字可能偏高。不等它稳下来就接着放，会把材料白白多塞进格子里
     * （格子能叠到 64，多塞的它收得下），这一轮又用不掉 —— 后面的轮次就会「找不到足够的材料」。
     */
    private void tickPlace(MinecraftClient mc, ClientPlayerEntity player) {
        int amount = plan.amountForRound(round);

        if (amount <= 0) {
            // 理论上不会有（rounds() 是按 crafts 算的），保底避免死循环
            fail("第 " + (round + 1) + " 轮每格要放 0 个（计划有误）");
            return;
        }

        if (cellIndex >= plan.cells().size()) {
            readyTicks = 0;
            lastCellIndex = -1;
            ChatUtils.debug("第 " + (round + 1) + "/" + plan.rounds() + " 轮材料摆好了（每格 "
                + amount + " 个），等服务器算出成品格");
            phase = Phase.READY;
            return;
        }

        CraftPlan.Cell cell = plan.cells().get(cellIndex);
        int slotId = gridSlotId(cell.gridSlot());
        int have = slotStack(slotId).getCount();
        int need = amount - have;
        ItemStack cursor = handler.getCursorStack();

        // 1) 先等这一格稳定（上一批点击的收尾 / 服务端同步回来的数字）
        if (cellIndex == lastCellIndex) {
            if (have != lastCellHave) {
                // 数字还在变：这一 tick 什么都不做，下一 tick 再看。
                // 上一批放完是 N 个、现在只剩 M 个 → 服务端只接受了那一批里的一部分，
                // 把它的状态同步回来覆盖了客户端的预测（一 tick 连发一大串点击时会出现）。
                // 没有丢东西：按实际差值接着补就行。
                if (lastCellHave >= 0 && have < lastCellHave) {
                    ChatUtils.debug("格子 " + cell.gridSlot() + " 的数量被服务端同步回 " + have
                        + " 个（上一批放到 " + lastCellHave + " 个，一部分点击没被接受），按差值接着补");
                }
                lastCellHave = have;
                placeSettleTicks = 0;
                return;
            }
            if (++placeSettleTicks < PLACE_SETTLE_TICKS) return;
        }

        // 2) 手上有东西
        if (!cursor.isEmpty()) {
            if (need <= 0 || !cursor.isOf(cell.item())) {
                returnCursor(player);
                return;
            }
            if (cursor.getCount() <= need) {
                click(mc, player, slotId, 0, SlotActionType.PICKUP);   // 整叠放进去
                lastCellIndex = cellIndex;
                lastCellHave = have + cursor.getCount();
                placeSettleTicks = 0;
                return;
            }
            // 手上比要的多：右键一次放 1 个，一个 tick 里连点几下（不然 41 个要 41 tick）。
            // 连点几下由配置 actions.items_per_tick 决定（客户端点击是本地预测、同步生效的；
            // 连点完下一 tick 会按「稳定下来的」格子里有多少个接着算，不会多放）。
            int batch = Math.min(Math.min(need, PlayerControllerConfig.getItemsPerTick()), cursor.getCount());
            if (batch <= 0) return;
            for (int i = 0; i < batch; i++) {
                clickQuiet(mc, player, slotId, 1, SlotActionType.PICKUP);
            }
            ChatUtils.debug("一次 tick 里往格子 " + cell.gridSlot() + " 放了 " + batch
                + " 个（这一格 " + (have + batch) + "/" + amount + "）");
            lastCellIndex = cellIndex;
            lastCellHave = have + batch;
            placeSettleTicks = 0;
            return;
        }

        // 3) 这一格够了 → 下一格
        if (need <= 0) {
            cellIndex++;
            lastCellIndex = -1;
            placeSettleTicks = 0;
            return;
        }

        int source = findSource(cell.item(), need);
        if (source < 0) {
            fail("找不到足够的 " + id(cell.item()) + "（这一轮每格要 " + amount
                + " 个，还差 " + need + " 个）；" + describeWhereIs(cell.item()));
            return;
        }
        click(mc, player, source, 0, SlotActionType.PICKUP);
    }

    /**
     * 从哪儿拿材料。
     *
     * <p>**主背包 27 格和快捷栏 9 格都看**：正常情况材料都摆在主背包里，但有两处会把材料弄到快捷栏去，
     * 只扫主背包就会误报「找不到足够的材料」：
     * <ul>
     *   <li>合成界面关掉的时候，格子里剩下的材料会被退回物品栏，而原版的
     *       {@code PlayerInventory.addStack} 是**先从快捷栏**找位置的；</li>
     *   <li>上一步合成出来的中间产物（QuickMove）本来就落在快捷栏里。</li>
     * </ul>
     * 只拿这一格要的那种材料，所以不会把别的东西抓进来；成品本身不会成为自己的材料，
     * 但保险起见还是排除掉 {@link CraftPlan#target()}。
     */
    private int findSource(Item item, int need) {
        if (item == plan.target()) return -1;

        int fallback = -1;
        int exact = -1;
        int exactCount = -1;
        for (Slot slot : inventorySlots()) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !stack.isOf(item)) continue;
            if (stack.getCount() <= need) {
                // 正好够（或不够）的一叠优先：一次左键就整叠放进去，不用右键点很多下
                if (stack.getCount() > exactCount) {
                    exact = slot.id;
                    exactCount = stack.getCount();
                }
                continue;
            }
            if (fallback < 0) fallback = slot.id;
        }
        return exact >= 0 ? exact : fallback;
    }

    /** 找材料时报一句「这东西到底在哪儿」，方便判断是计划本身短了还是被塞在别处了。 */
    private String describeWhereIs(Item item) {
        int main = 0;
        int hotbar = 0;
        for (Slot slot : inventorySlots()) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !stack.isOf(item)) continue;
            if (slot.getIndex() < 9) hotbar += stack.getCount();
            else main += stack.getCount();
        }

        int grid = 0;
        if (plan.station() == CraftPlan.Station.CRAFTING_TABLE) {
            for (Slot slot : ((CraftingScreenHandler) handler).getInputSlots()) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && stack.isOf(item)) grid += stack.getCount();
            }
        } else {
            ItemStack stack = slotStack(StonecutterScreenHandler.INPUT_ID);
            if (!stack.isEmpty() && stack.isOf(item)) grid += stack.getCount();
        }

        ItemStack cursor = handler.getCursorStack();
        int onCursor = !cursor.isEmpty() && cursor.isOf(item) ? cursor.getCount() : 0;

        return "现在物品栏里这种材料：主背包 " + main + " 个、快捷栏 " + hotbar + " 个、"
            + "合成格 " + grid + " 个、光标 " + onCursor + " 个（一共 "
            + (main + hotbar + grid + onCursor) + " 个）";
    }

    /** 界面里属于玩家物品栏的格子：快捷栏 0~8 + 主背包 9~35。 */
    private List<Slot> inventorySlots() {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() < 36) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * 等服务器把成品格算出来。
     *
     * <p>工作台的成品格是服务端算的，材料摆完那一刻客户端不一定看得到；切石机还要先选配方。
     * 最多等 {@link #READY_WAIT_TICKS} tick。
     *
     * <p>等不到（常见原因：一 tick 连发一大串点击，服务端其实只接受了一部分，材料并没摆全）
     * 就回去重新摆一遍（{@link #retryPlace}），而不是直接失败。
     */
    private void tickReady(MinecraftClient mc, ClientPlayerEntity player) {
        if (plan.station() == CraftPlan.Station.STONECUTTER && recipeIndex < 0) {
            selectStonecutterRecipe(mc, player);
            return;
        }

        ItemStack result = slotStack(resultSlotId());
        if (!result.isEmpty()) {
            if (!result.isOf(plan.target())) {
                fail("成品格是 " + id(result.getItem()) + "，不是 " + id(plan.target()));
                return;
            }
            craft(mc, player);
            return;
        }

        if (++readyTicks <= READY_WAIT_TICKS) return;

        // 等不到成品格：最常见的原因是「一 tick 连发一大串点击，服务端其实只接受了一部分，材料没摆全」，
        // 先回去重新摆一遍（重摆会按格子里实际有多少个补齐）。
        if (readyRetries < MAX_READY_RETRIES) {
            retryPlace("材料摆好了，但等了 " + READY_WAIT_TICKS + " tick 服务器还没算出成品格");
            return;
        }

        // 重摆过好几轮还是看不到成品格：直接点一次试试（服务端那边可能其实是好的，
        // 只是客户端这一刻的格子数据旧了）。合没合成，交给「物品栏里多没多出成品」去判。
        ChatUtils.debug("一直看不到成品格，但材料确实摆好了 → 直接点一次成品格试试");
        craft(mc, player);
    }

    /**
     * 点一次成品格。
     *
     * <p><b>每轮只需要点一次</b>：原版 shift 点击成品格是「把这一轮能合的都合掉」
     * （189 个橡木栅栏就是点一次出来的）。
     */
    private void craft(MinecraftClient mc, ClientPlayerEntity player) {
        craftedBefore = countCrafted();
        click(mc, player, resultSlotId(), 0, SlotActionType.QUICK_MOVE);
        clicks++;
        waitTicks = 0;
        phase = Phase.WAIT_CRAFTED;
        ChatUtils.debug("第 " + (round + 1) + "/" + plan.rounds() + " 轮：点了一次成品格，"
            + "等物品栏里多出成品（最多 " + RESULT_WAIT_TICKS + " tick）");
    }

    /**
     * 点完成品格之后，每 tick 看物品栏里成品多了没有。
     *
     * <p>刻意不看成品格：合成是服务端做的，而且客户端这一刻的格子数据可能还是旧的
     * （服务端会把它的状态同步回来），看成品格会冤枉地报「成品格是空的」。
     * 成品 QuickMove 进的是快捷栏（快捷栏满了会往主背包塞），所以两处一起数。
     */
    private void tickWaitCrafted() {
        int now = countCrafted();
        if (now > craftedBefore) {
            ChatUtils.debug("第 " + (round + 1) + "/" + plan.rounds() + " 轮合成完成：物品栏里多了 "
                + (now - craftedBefore) + " 个 " + id(plan.target()));
            endRound();
            return;
        }

        if (++waitTicks > RESULT_WAIT_TICKS) {
            fail("点完成品格之后物品栏里没多出成品（第 " + (round + 1) + "/" + plan.rounds()
                + " 轮，等了 " + RESULT_WAIT_TICKS + " tick）；材料可能没摆对，或者物品栏满了");
        }
    }

    /** 这一轮结束：还有轮次就接着摆，没有就收工。 */
    private void endRound() {
        round++;
        cellIndex = 0;
        lastCellIndex = -1;
        recipeIndex = -1;   // 切石机换了输入之后要重新选一次配方
        readyTicks = 0;
        readyRetries = 0;
        recipeWaitTicks = 0;

        if (round >= plan.rounds()) {
            phase = Phase.DONE;
            return;
        }
        ChatUtils.debug("接着摆第 " + (round + 1) + "/" + plan.rounds() + " 轮材料");
        phase = Phase.PLACE;
    }

    /** 回去重新摆这一轮的材料；重试次数用完就失败。 */
    private void retryPlace(String why) {
        if (++readyRetries > MAX_READY_RETRIES) {
            fail(why + "（重摆了 " + MAX_READY_RETRIES + " 次还是一样，材料可能没摆对）");
            return;
        }
        ChatUtils.debug(why + "，回去重新摆一遍材料（第 " + readyRetries + "/"
            + MAX_READY_RETRIES + " 次）");
        cellIndex = 0;
        lastCellIndex = -1;
        readyTicks = 0;
        recipeIndex = -1;
        recipeWaitTicks = 0;
        phase = Phase.PLACE;
    }

    /** 物品栏（快捷栏 + 主背包）里现在有多少个成品。 */
    private int countCrafted() {
        int total = 0;
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.isOf(plan.target())) total += stack.getCount();
        }
        return total;
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
            if (++recipeWaitTicks > READY_WAIT_TICKS * 2) {
                recipeWaitTicks = 0;
                retryPlace("切石机一直没列出可选配方（输入格的石头可能没摆进去）");
            }
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
                    recipeWaitTicks = 0;
                    return;   // 还留在 READY，接着等成品格
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

    /** 跟 {@link #click} 一样，但**不逐次打日志**（一次 tick 连点好几下时用）。 */
    private void clickQuiet(MinecraftClient mc, ClientPlayerEntity player, int slotId, int button,
                            SlotActionType type) {
        mc.interactionManager.clickSlot(handler.syncId, slotId, button, type, player);
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
