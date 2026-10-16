package org.six_coin.playerController.client.recipe;

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
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc recipe ...} 的执行体：打开工作站的工作台 / 切石机，把这一轮的材料摆进格子，
 * 然后 QuickMove 成品（原版 QuickMove 是从最后一格往前填，所以成品会进快捷栏靠后的格子）。
 *
 * <p><b>摆材料跟往容器里搬东西是一回事</b>：格子里的数量点击是本地预测、立即生效的，
 * 所以一个 tick 里就能把这一轮的格子全摆好（点击上限见配置 {@code actions.items_per_tick}），
 * 不用一格一格等。摆完等 {@link #VERIFY_TICKS} tick 让服务端把数量同步回来，再**逐格核对**
 * 「计划的格子有对的材料、别的输入格是空的」，不对就再摆一遍（连点被服务端少接受的情况在这里发现）。
 *
 * <p><b>唯一不能照搬的是成品格</b>：它不是客户端算的，是服务端按配方算出来推给客户端的。
 * 所以「材料齐了没有」不能靠看成品格（那一刻客户端看到的可能还是旧的），而是靠核对输入格；
 * 「合出几个」也只能等服务端把物品栏的增量推回来（{@link #tickWaitCrafted}）。
 *
 * <p>材料只从主背包 27 格和快捷栏 9 格里拿（见 {@link #findSource}），
 * 手上多出来的材料一律放回主背包 27 格。
 *
 * <p>要合的次数超过一格能叠的上限时，{@link CraftPlan#rounds()} 会是好几轮：
 * 摆一轮 → 合完（原版一次 QuickMove 会把这一轮能合的都合掉）→ 再摆下一轮。
 */
public class CraftAction extends Action {

    /** 界面打开之后等几 tick 再动，保证槽位数据同步完了。 */
    private static final int SETTLE_TICKS = 2;

    /**
     * 一轮材料摆完（本地预测看着是对的）之后，先等几 tick 再核对格子。
     *
     * <p>一 tick 连发一大串点击时，服务端可能只接受了其中一部分、并把它那份数量推回来 ——
     * 等它推完再核对，才不会把「客户端以为摆好了」当成真的摆好了。
     */
    private static final int VERIFY_TICKS = 2;

    /** 材料摆好之后，等服务器把成品格算出来最多等几 tick（20 tick = 1 秒）。 */
    private static final int READY_WAIT_TICKS = 20;

    /**
     * 点了一次成品格之后，等物品栏里多出成品最多等几 tick（20 tick = 1 秒）。
     *
     * <p>合成是服务端做的，结果要过一会儿才同步回来，所以不能点完就立刻判断。
     */
    private static final int RESULT_WAIT_TICKS = 20;

    /** 一轮里最多摆几遍（每遍之后都核对一次）。 */
    private static final int MAX_PLACE_PASSES = 3;

    /** 等不到成品格时最多回头重摆几次。 */
    private static final int MAX_READY_RETRIES = 3;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    private enum Phase {
        /** 等界面打开。 */
        OPENING,
        /** 等槽位同步。 */
        SETTLE,
        /** 把这一轮的输入格摆成计划的样子。 */
        PLACE,
        /** 摆完等几 tick，然后核对格子。 */
        VERIFY,
        /** 等服务器把成品格算出来（切石机还要先选配方）。 */
        READY,
        /** 点了一次成品格，等物品栏里真的多出成品。 */
        WAIT_CRAFTED,
        /** 全部弄完。 */
        DONE
    }

    private final CraftPlan plan;

    private final ContainerSession session;
    private Phase phase = Phase.OPENING;
    private int settleTicks;
    private int totalTicks;

    /** 摆到第几个输入格了（每轮从头开始）。 */
    private int gridIndex;

    /** 这一 tick 还能点几下（每 tick 开头按配置充满）。 */
    private int clicksLeft;
    /** 一共点了几下（日志用）。 */
    private int clicks;
    /** 点了几次成品格（每轮一次，所以正常情况下就是轮数）。 */
    private int craftClicks;

    /** 第几轮（0 开始）。 */
    private int round;
    /** 这一轮摆了几遍（每遍之后核对一次）。 */
    private int placePasses;
    private int verifyTicks;

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

    public CraftAction(CraftPlan plan, BlockPos stationPos) {
        this.plan = plan;
        // 只认自己那一种界面（工作台 / 切石机）；等几 tick 的事下面自己管（SETTLE）
        this.session = new ContainerSession(stationPos,
            plan.station() == CraftPlan.Station.CRAFTING_TABLE
                ? CraftingScreenHandler.class : StonecutterScreenHandler.class);
    }

    @Override
    public String name() {
        return plan.station() == CraftPlan.Station.CRAFTING_TABLE ? "工作台合成" : "切石机切石";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("没有玩家或世界");
            return;
        }

        ChatUtils.debug("开始 " + plan.describe());
        session.open(mc, player);
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

        if (session.isOpen() && !session.stillOpen(mc)) {
            fail("界面被关掉了");
            return;
        }

        // 点击是本地预测、立即生效的，一 tick 里可以连点好几下（上限见配置）；这一步点满了就下一 tick 接着来
        clicksLeft = PlayerControllerConfig.getItemsPerTick();

        switch (phase) {
            case OPENING -> tickOpening(mc);
            case SETTLE -> tickSettle();
            case PLACE -> tickPlace();
            case VERIFY -> tickVerify();
            case READY -> tickReady(mc);
            case WAIT_CRAFTED -> tickWaitCrafted();
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        session.close(MinecraftClient.getInstance());

        if (failureReason() != null) {
            return;   // 原因由 Action#cleanup 报一行
        }

        output().info(name() + "完成：" + id(plan.target()) + " x" + plan.actualCount()
            + (plan.actualCount() == plan.count() ? "" : "（要 " + plan.count() + " 个，向上取整）")
            + "（点了 " + craftClicks + " 次成品格，摆了 " + (round + (phase == Phase.DONE ? 0 : 1)) + " 轮）");
    }

    // ------------------------------------------------------------------
    // 各阶段
    // ------------------------------------------------------------------

    private void tickOpening(MinecraftClient mc) {
        switch (session.awaitOpen(mc)) {
            case READY -> {
                settleTicks = 0;
                phase = Phase.SETTLE;
            }
            case TIMEOUT -> fail("打开" + (plan.station() == CraftPlan.Station.CRAFTING_TABLE ? "工作台" : "切石机")
                + "超时");
            case WAITING -> {
            }
        }
    }

    private void tickSettle() {
        settleTicks++;
        if (settleTicks < SETTLE_TICKS) return;
        gridIndex = 0;
        phase = Phase.PLACE;
    }

    /**
     * 摆这一轮的材料：一格一格来，该空的弄空、该放的按数量放够，手上多的放回物品栏。
     *
     * <p>一个 tick 里能干完就干完（点击是本地预测、立即生效的）—— 摆完不急着下结论，
     * 先等 {@link #VERIFY_TICKS} tick 让服务端把格子的数量同步回来，再核对一遍（见 {@link #tickVerify}）。
     */
    private void tickPlace() {
        int amount = plan.amountForRound(round);
        if (amount <= 0) {
            fail("第 " + (round + 1) + " 轮每格要放 0 个（计划有误）");
            return;
        }

        List<Slot> grid = gridSlots();
        while (clicksLeft > 0 && gridIndex < grid.size() && !isFinished()) {
            int clicksBefore = clicksLeft;
            int indexBefore = gridIndex;

            if (fixCell(grid, gridIndex, amount)) gridIndex++;

            // 这一圈既没点出去、也没往前走：本地预测还没生效，下一 tick 再看
            if (clicksLeft == clicksBefore && gridIndex == indexBefore) break;
        }
        if (isFinished()) return;
        if (gridIndex < grid.size()) return;   // 这一 tick 的点击用完了，剩下的下一 tick 接着摆

        // 手上不能留着东西（会挡住下一格拿材料）
        if (!session.handler().getCursorStack().isEmpty()) {
            returnCursor();
            return;
        }

        placePasses++;
        verifyTicks = VERIFY_TICKS;
        phase = Phase.VERIFY;
        ChatUtils.debug("第 %d/%d 轮材料摆好了（每格 %d 个，这一轮摆了 %d 遍、点了 %d 下），等服务器同步格子的数量",
            round + 1, plan.rounds(), amount, placePasses, clicks);
    }

    /**
     * 把第 {@code index} 个输入格弄成计划要的样子。
     *
     * @return true = 本地预测看这一格已经对了（该空的空着、该放的放够数量了）
     */
    private boolean fixCell(List<Slot> grid, int index, int amount) {
        Slot slot = grid.get(index);
        Item want = wantedItemAt(grid, index);
        ItemStack have = slot.getStack();
        ItemStack cursor = session.handler().getCursorStack();

        // 手上有东西：这一格还要的话就接着往里放，不然先放回物品栏
        if (!cursor.isEmpty()) {
            if (want != null && cursor.isOf(want)
                && (have.isEmpty() || have.isOf(want)) && have.getCount() < amount) {
                placeFromCursor(slot, amount - have.getCount());
            } else {
                returnCursor();
            }
            return false;
        }

        // 这一格该空着
        if (want == null) {
            if (have.isEmpty()) return true;
            click(slot.id, 0, SlotActionType.PICKUP);   // 拿走，下一圈放回物品栏
            return false;
        }

        // 东西不对 / 放多了：先清掉，下一圈按计划重摆
        if (!have.isEmpty() && (!have.isOf(want) || have.getCount() > amount)) {
            click(slot.id, 0, SlotActionType.PICKUP);
            return false;
        }

        if (have.getCount() == amount) return true;

        // 从物品栏拿这一格还差的材料（整叠拿起来，多的下一圈放回去）
        int need = amount - have.getCount();
        int source = findSource(want, need);
        if (source < 0) {
            fail("找不到足够的 " + id(want) + "（这一轮每格要 " + amount + " 个，还差 " + need
                + " 个）；" + describeWhereIs(want));
            return false;
        }
        click(source, 0, SlotActionType.PICKUP);
        return false;
    }

    /** 手上拿着的材料往这一格放：够放多少放多少（正好够就一下左键，不然一格一格右键放）。 */
    private void placeFromCursor(Slot slot, int need) {
        ItemStack cursor = session.handler().getCursorStack();
        int place = Math.min(need, cursor.getCount());
        if (place <= 0) return;

        if (place == cursor.getCount()) {
            click(slot.id, 0, SlotActionType.PICKUP);
            return;
        }
        for (int i = 0; i < place && clicksLeft > 0; i++) {
            click(slot.id, 1, SlotActionType.PICKUP);
        }
    }

    /**
     * 核对格子：计划的格子有对的材料（数量够这一轮用）、别的输入格是空的。
     *
     * <p>不对就再摆一遍（{@link #fixCell} 会把差的补上、多的清掉），摆了几遍还是不对就报错停下 ——
     * 这样「服务端少接受了几下」在这里就被发现并补上，不用等到成品格一直不出来才回头重摆。
     */
    private void tickVerify() {
        if (--verifyTicks > 0) return;

        String problem = gridProblem();
        if (problem == null) {
            readyTicks = 0;
            recipeIndex = -1;   // 切石机换了输入之后要重新选一次配方
            recipeWaitTicks = 0;
            phase = Phase.READY;
            ChatUtils.debug("第 %d/%d 轮格子核对通过，等服务器算成品格", round + 1, plan.rounds());
            return;
        }

        if (placePasses >= MAX_PLACE_PASSES) {
            fail("格子摆不对：" + problem + "（摆了 " + placePasses + " 遍）");
            return;
        }

        ChatUtils.debug("格子还没摆对（%s），再摆一遍（第 %d 遍）", problem, placePasses + 1);
        gridIndex = 0;
        phase = Phase.PLACE;
    }

    /** 格子有没有跟计划对不上（null = 对得上）。只看输入格，成品格是服务端算的。 */
    @Nullable
    private String gridProblem() {
        List<Slot> grid = gridSlots();
        int amount = plan.amountForRound(round);
        boolean[] planned = new boolean[grid.size()];

        for (CraftPlan.Cell cell : plan.cells()) {
            int index = gridIndexOf(cell, grid);
            planned[index] = true;

            ItemStack stack = grid.get(index).getStack();
            if (stack.isEmpty()) {
                return "第 " + index + " 格应该是 " + id(cell.item()) + "，但里面是空的";
            }
            if (!stack.isOf(cell.item())) {
                return "第 " + index + " 格应该是 " + id(cell.item()) + "，但里面是 "
                    + InventoryUtils.describe(stack);
            }
            if (stack.getCount() < amount) {
                return "第 " + index + " 格的 " + id(cell.item()) + " 只有 " + stack.getCount()
                    + " 个（这一轮要 " + amount + " 个）";
            }
        }

        for (int i = 0; i < grid.size(); i++) {
            if (planned[i]) continue;
            ItemStack stack = grid.get(i).getStack();
            if (!stack.isEmpty()) {
                return "第 " + i + " 格应该空着，但里面有 " + InventoryUtils.describe(stack);
            }
        }
        return null;
    }

    /**
     * 等服务器把成品格算出来。
     *
     * <p>工作台的成品格是服务端算的，材料摆完那一刻客户端不一定看得到；切石机还要先选配方。
     * 最多等 {@link #READY_WAIT_TICKS} tick；等不到就回去重新摆一遍（重摆会按格子里实际有多少个补齐）。
     */
    private void tickReady(MinecraftClient mc) {
        if (plan.station() == CraftPlan.Station.STONECUTTER && recipeIndex < 0) {
            selectStonecutterRecipe(mc);
            return;
        }

        ItemStack result = slotStack(resultSlotId());
        if (!result.isEmpty()) {
            if (!result.isOf(plan.target())) {
                String problem = gridProblem();
                fail("成品格是 " + id(result.getItem()) + "，不是 " + id(plan.target())
                    + (problem == null ? "（格子看着是对的）" : "（" + problem + "）"));
                return;
            }
            craft();
            return;
        }

        if (++readyTicks <= READY_WAIT_TICKS) return;

        if (readyRetries < MAX_READY_RETRIES) {
            retryPlace("材料摆好了，但等了 " + READY_WAIT_TICKS + " tick 服务器还没算出成品格");
            return;
        }

        // 重摆过好几轮还是看不到成品格：直接点一次试试（服务端那边可能其实是好的，
        // 只是客户端这一刻的格子数据旧了）。合没合成，交给「物品栏里多没多出成品」去判。
        ChatUtils.debug("一直看不到成品格，但格子确实摆好了 → 直接点一次成品格试试");
        craft();
    }

    /**
     * 点一次成品格。
     *
     * <p><b>每轮只需要点一次</b>：原版 shift 点击成品格是「把这一轮能合的都合掉」
     * （189 个橡木栅栏就是点一次出来的）。
     */
    private void craft() {
        craftedBefore = countCrafted();
        if (!click(resultSlotId(), 0, SlotActionType.QUICK_MOVE)) return;   // 这一 tick 点满了，下一 tick 再点

        craftClicks++;
        waitTicks = 0;
        phase = Phase.WAIT_CRAFTED;
        ChatUtils.debug("第 %d/%d 轮：点了一次成品格，等物品栏里多出成品（最多 %d tick）",
            round + 1, plan.rounds(), RESULT_WAIT_TICKS);
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
            ChatUtils.debug("第 %d/%d 轮合成完成：物品栏里多了 %d 个 %s",
                round + 1, plan.rounds(), now - craftedBefore, id(plan.target()));
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
        gridIndex = 0;
        placePasses = 0;
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
            String problem = gridProblem();
            fail(why + "（重摆了 " + MAX_READY_RETRIES + " 次还是一样"
                + (problem == null ? "" : "；" + problem) + "）");
            return;
        }
        ChatUtils.debug(why + "，回去重新摆一遍材料（第 " + readyRetries + "/"
            + MAX_READY_RETRIES + " 次）");
        gridIndex = 0;
        placePasses = 0;
        readyTicks = 0;
        recipeIndex = -1;
        recipeWaitTicks = 0;
        phase = Phase.PLACE;
    }

    // ------------------------------------------------------------------
    // 材料从哪儿拿 / 放回去
    // ------------------------------------------------------------------

    /**
     * 从哪儿拿这一格要的材料。
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
     *
     * <p>正好够（或不够）的一叠优先：整叠一下就放进格子里，不用右键点很多下。
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
        for (Slot slot : gridSlots()) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.isOf(item)) grid += stack.getCount();
        }

        ItemStack cursor = session.handler().getCursorStack();
        int onCursor = !cursor.isEmpty() && cursor.isOf(item) ? cursor.getCount() : 0;

        return "现在物品栏里这种材料：主背包 " + main + " 个、快捷栏 " + hotbar + " 个、"
            + "合成格 " + grid + " 个、光标 " + onCursor + " 个（一共 "
            + (main + hotbar + grid + onCursor) + " 个）";
    }

    /** 把手上的东西放回主背包 27 格（优先同类半叠）。 */
    private void returnCursor() {
        ItemStack cursor = session.handler().getCursorStack();
        if (cursor.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            fail("玩家不存在");
            return;
        }

        Slot target = InventoryUtils.wholeTarget(session.handler(), mc.player.getInventory(), cursor);
        if (target == null) {
            fail("主背包 27 格放不下手上剩下的 " + id(cursor.getItem()) + " x" + cursor.getCount());
            return;
        }
        click(target.id, 0, SlotActionType.PICKUP);
    }

    // ------------------------------------------------------------------
    // 切石机选配方
    // ------------------------------------------------------------------

    private void selectStonecutterRecipe(MinecraftClient mc) {
        if (mc.world == null) {
            fail("没有世界");
            return;
        }

        CuttingRecipeDisplay.Grouping<StonecuttingRecipe> grouping =
            ((StonecutterScreenHandler) session.handler()).getAvailableRecipes();
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
                    ContainerClicks.clickButton(session.handler(), index);
                    ChatUtils.debug("切石机：选中第 %d 个配方（共 %d 个选项），等成品格",
                        index, grouping.size());
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

    /** 这台机器上的输入格（工作台 9 格、切石机 1 格）。 */
    private List<Slot> gridSlots() {
        if (plan.station() == CraftPlan.Station.STONECUTTER) {
            return List.of(session.handler().slots.get(StonecutterScreenHandler.INPUT_ID));
        }
        return ((CraftingScreenHandler) session.handler()).getInputSlots();
    }

    /** 计划里的格子 → 输入格列表里的下标。 */
    private int gridIndexOf(CraftPlan.Cell cell, List<Slot> grid) {
        return Math.min(cell.gridSlot(), grid.size() - 1);
    }

    /** 第 {@code index} 个输入格这一轮该放什么（null = 该空着）。 */
    @Nullable
    private Item wantedItemAt(List<Slot> grid, int index) {
        for (CraftPlan.Cell cell : plan.cells()) {
            if (gridIndexOf(cell, grid) == index) return cell.item();
        }
        return null;
    }

    private int resultSlotId() {
        return plan.station() == CraftPlan.Station.CRAFTING_TABLE
            ? CraftingScreenHandler.RESULT_ID
            : StonecutterScreenHandler.OUTPUT_ID;
    }

    private ItemStack slotStack(int slotId) {
        return session.handler().slots.get(slotId).getStack();
    }

    /**
     * 点一下某个槽位（{@code ScreenHandler.slots} 的下标就是槽位号）。
     *
     * @return false = 这一 tick 的点击用完了，什么也没点（下一 tick 接着来）
     */
    private boolean click(int slotId, int button, SlotActionType type) {
        if (clicksLeft <= 0) return false;
        if (!ContainerClicks.clickQuiet(session.handler(), slotId, button, type)) {
            fail("点不了格子 #" + slotId);
            return false;
        }
        clicksLeft--;
        clicks++;
        return true;
    }

    /** 界面里属于玩家物品栏的格子：快捷栏 0~8 + 主背包 9~35。 */
    private List<Slot> inventorySlots() {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : session.handler().slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() < 36) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** 物品栏（快捷栏 + 主背包）里现在有多少个成品。 */
    private int countCrafted() {
        int total = 0;
        for (Slot slot : session.handler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.isOf(plan.target())) total += stack.getCount();
        }
        return total;
    }

    private static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
