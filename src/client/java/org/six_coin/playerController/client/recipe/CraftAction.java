package org.six_coin.playerController.client.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.util.context.ContextMap;
import net.minecraft.core.BlockPos;
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
 * <p><b>摆材料急不得</b>：点击是本地预测、立即生效的，但服务端只接受其中的一部分时，
 * 会把它实际的数量推回来。所以每次只点一格、只点一批（上限见 {@code ContainerClicks.MAX_CLICKS_PER_TICK}），
 * 点完等这一格的数量稳定下来再按最新的数量接着摆。一圈摆完再等「网络延迟」
 * （{@code actions.network_delay_ticks}）那么多 tick，然后**逐格核对**
 * 「计划的格子有对的材料、别的输入格是空的」，不对就再摆一遍（这一遍按实际数量补差、清多的）。
 *
 * <p><b>唯一不能照搬的是成品格</b>：它不是客户端算的，是服务端按配方算出来推给客户端的。
 * 所以「材料齐了没有」不能靠看成品格（那一刻客户端看到的可能还是旧的），而是靠核对输入格；
 * 「合出几个」也只能等服务端把物品栏的增量推回来（{@link #tickWaitCrafted}）。
 *
 * <p>材料只从主背包 27 格和快捷栏 9 格里拿（见 {@link #findSource}），
 * 手上多出来的材料一律放回主背包 27 格；要是某个格子被多塞了（客户端和服务端的数字对不上时会出现），
 * 多出来的那部分会被拿回来重新分（见 {@link #findGridSource}），实在分不匀就清空格子从头摆一遍。
 *
 * <p>要合的次数超过一格能叠的上限时，{@link CraftPlan#rounds()} 会是好几轮：
 * 摆一轮 → 合完（原版一次 QuickMove 会把这一轮能合的都合掉）→ 再摆下一轮。
 */
public class CraftAction extends Action {

    /**
     * 等服务器同步的 tick 数：每批点击之后等这一格的数量稳下来、一轮摆完之后核对格子，都用它。
     *
     * <p>一 tick 连发一大串点击时，服务端可能只接受了其中一部分、并把它那份数量推回来 ——
     * 等它推完再往下走，才不会把「客户端以为摆好了」当成真的摆好了。
     * 等的是配置里的「网络延迟」（{@code actions.network_delay_ticks}）。
     */
    private int verifyWaitTicks() {
        return PlayerControllerConfig.getNetworkDelayTicks();
    }

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

    /** 材料凑不齐时最多先歇一歇、重扫几次（服务端的数字可能还在路上）。 */
    private static final int MAX_SUPPLY_RETRIES = 2;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    private enum Phase {
        /** 等界面打开（还能用之前，界面里的格子内容也要同步过来）。 */
        OPENING,
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
    private int totalTicks;

    /** 摆到第几个输入格了（每轮从头开始）。 */
    private int gridIndex;

    /** 刚动过的格子（-1 = 没有等它稳定下来的一批点击）。 */
    private int watchingCell = -1;
    /** 那一批点完之后这一格「应该」有几个。 */
    private int watchingCount;
    /** 这一格的数量连着几 tick 没变了。 */
    private int watchingTicks;

    /** 界面打开后第一次看到的状态号（初值不可能出现），和它连着几 tick 没变了。 */
    private int openRevision = Integer.MIN_VALUE;
    private int openSettleTicks;

    /** 这一遍先把格子清空再摆（上一步摆歪了、材料被卡在别的格子里时用）。 */
    private boolean clearFirst;

    /** 「材料凑不齐」的时候先歇几 tick 再看（服务端的数字可能还在路上），歇几次还不行才报错。 */
    private int placeWaitTicks;
    private int supplyRetries;

    /** 这一 tick 还能点几下（每 tick 开头充满）。 */
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
        // 只认自己那一种界面（工作台 / 切石机）；界面打开之后等同步的事由 session 管
        this.session = new ContainerSession(stationPos,
            plan.station() == CraftPlan.Station.CRAFTING_TABLE
                ? CraftingMenu.class : StonecutterMenu.class);
    }

    @Override
    public String name() {
        return plan.station() == CraftPlan.Station.CRAFTING_TABLE ? "工作台合成" : "切石机切石";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("没有玩家或世界");
            return;
        }

        ChatUtils.debug("开始 " + plan.describe());
        session.open(mc, player);
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
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

        // 点击是本地预测、立即生效的，一 tick 里可以连点好几下；这一步点满了就下一 tick 接着来
        clicksLeft = ContainerClicks.MAX_CLICKS_PER_TICK;

        switch (phase) {
            case OPENING -> tickOpening(mc);
            case PLACE -> tickPlace();
            case VERIFY -> tickVerify();
            case READY -> tickReady(mc);
            case WAIT_CRAFTED -> tickWaitCrafted();
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        session.close(Minecraft.getInstance());

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

    private void tickOpening(Minecraft mc) {
        switch (session.awaitOpen(mc)) {
            case READY -> {
                // 界面的内容到了，但上一步的点击可能还在被服务端处理：状态号（每推一次格子内容就变）
                // 连着几 tick 不动了再开工 —— 不然第一下拿材料就是照着一份过期的物品栏点的。
                int revision = session.handler().getStateId();
                if (revision != openRevision) {
                    openRevision = revision;
                    openSettleTicks = 0;
                    return;
                }
                if (++openSettleTicks < Math.max(2, verifyWaitTicks())) return;
                gridIndex = 0;
                watchingCell = -1;
                clearFirst = false;
                supplyRetries = 0;
                placeWaitTicks = 0;
                phase = Phase.PLACE;
            }
            case TIMEOUT -> fail("打开" + (plan.station() == CraftPlan.Station.CRAFTING_TABLE ? "工作台" : "切石机")
                + "超时");
            case WAITING -> {
            }
        }
    }

    /**
     * 摆这一轮的材料：一格一格来，该空的弄空、该放的按数量放够，手上多的放回物品栏。
     *
     * <p><b>每次只动一格、只点一批</b>：点完先等这一格的数量稳定下来（服务端会把它**实际**接受到的数量
     * 推回来），再按最新的数量决定下一步。一 tick 里连点几十下时服务端只接受其中一部分，
     * 客户端照着自己那份偏高的预测接着摆，就会把材料多塞进前面的格子（格子能叠到 64，多塞的它也收得下），
     * 后面的格子反而没得放 —— 报出来的就是「找不到足够的材料」。
     *
     * <p>这一圈摆完先等「网络延迟」那么多 tick，再逐格核对（见 {@link #tickVerify}）。
     */
    private void tickPlace() {
        int amount = plan.amountForRound(round);
        if (amount <= 0) {
            fail("第 " + (round + 1) + " 轮每格要放 0 个（计划有误）");
            return;
        }

        List<Slot> grid = gridSlots();

        // 上一次没凑齐，先歇几 tick 等服务器的数字过来
        if (placeWaitTicks > 0) {
            placeWaitTicks--;
            return;
        }

        // 上一批点完，先等这一格稳定下来（数量连着「网络延迟」那么多 tick 没变）
        if (watchingCell >= 0 && !awaitStable(grid)) return;

        // 重摆：先把格子清空（材料全退回物品栏），再按计划重摆。
        // 上一步摆歪的格子会把材料困在里面，前面的格子就会一直「找不到足够的材料」。
        if (clearFirst) {
            clearGridStep(grid);
            return;
        }

        if (gridIndex >= grid.size()) {
            // 手上不能留着东西（会挡住下一格拿材料）
            if (!session.handler().getCarried().isEmpty()) {
                returnCursor();
                return;
            }
            placePasses++;
            verifyTicks = verifyWaitTicks();
            phase = Phase.VERIFY;
            ChatUtils.debug("第 %d/%d 轮材料摆好了（每格 %d 个，这一轮摆了 %d 遍、点了 %d 下），等服务器同步格子的数量",
                round + 1, plan.rounds(), amount, placePasses, clicks);
            return;
        }

        int index = gridIndex;
        Slot slot = grid.get(index);
        Item want = wantedItemAt(grid, index);
        ItemStack have = slot.getItem();
        ItemStack cursor = session.handler().getCarried();

        // 手上有东西：这一格还要的话就接着往里放，不然先放回物品栏
        if (!cursor.isEmpty()) {
            if (want != null && cursor.is(want)
                && (have.isEmpty() || have.is(want)) && have.getCount() < amount) {
                placeFromCursor(slot, index, amount - have.getCount(), have.getCount());
            } else {
                returnCursor();
            }
            return;
        }

        // 这一格该空着
        if (want == null) {
            if (have.isEmpty()) {
                gridIndex++;
                return;
            }
            click(slot.index, 0, ContainerInput.PICKUP);   // 拿走，下一 tick 放回物品栏
            watch(index, 0);
            return;
        }

        // 东西不对 / 放多了：先清掉，下一 tick 按计划重摆
        if (!have.isEmpty() && (!have.is(want) || have.getCount() > amount)) {
            click(slot.index, 0, ContainerInput.PICKUP);
            watch(index, 0);
            return;
        }

        if (have.getCount() == amount) {
            gridIndex++;
            return;
        }

        // 从物品栏拿这一格还差的材料（整叠拿起来，下一 tick 放进去，多的再放回去）
        int need = amount - have.getCount();
        int source = findSource(want, need);
        if (source < 0) source = findGridSource(grid, want, index);
        if (source < 0) {
            // 数字可能还没同步完（材料其实在别的格子里、只是客户端还没看到）：再等几 tick 重扫一遍
            if (supplyRetries < MAX_SUPPLY_RETRIES) {
                supplyRetries++;
                ChatUtils.debug("这一格还差 %d 个 %s，物品栏和别的格里都没找到：先等服务器的数字同步，"
                    + "第 %d/%d 次重扫", need, id(want), supplyRetries, MAX_SUPPLY_RETRIES);
                gridIndex = 0;
                watchingCell = -1;
                clearFirst = true;
                placeWaitTicks = Math.max(2, verifyWaitTicks()) + 1;
                return;
            }
            fail("找不到足够的 " + id(want) + "（这一轮每格要 " + amount + " 个，还差 " + need
                + " 个）；" + describeWhereIs(want));
            return;
        }
        click(source, 0, ContainerInput.PICKUP);
    }

    /** 记下「刚动过这一格、它现在应该有 count 个」，接下来先等它稳定下来再动别的格子。 */
    private void watch(int index, int count) {
        watchingCell = index;
        watchingCount = count;
        watchingTicks = 0;
    }

    /**
     * 清场的一步：手上有东西就先放回物品栏，否则拿走一个还非空的输入格。
     *
     * <p>跟摆材料一样，每 tick 只动一下、动完等它稳定。格子全空之后把 {@link #clearFirst} 放掉，
     * 这一遍从头按计划重摆。
     */
    private void clearGridStep(List<Slot> grid) {
        ItemStack cursor = session.handler().getCarried();
        if (!cursor.isEmpty()) {
            returnCursor();
            return;
        }
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i).getItem().isEmpty()) continue;
            click(grid.get(i).index, 0, ContainerInput.PICKUP);
            watch(i, 0);
            return;
        }
        clearFirst = false;
        gridIndex = 0;
        ChatUtils.debug("输入格已清空，重新摆这一轮的材料");
    }

    /**
     * 等刚动过的那一格稳定下来：数量连着「网络延迟」那么多 tick 没变才算数。
     *
     * <p>一批点击点出去之后，服务端会把它**实际**接受到的数量推回来 —— 客户端这一刻的数字可能偏高，
     * 不等它推完就接着摆，会照着错的数字继续点，把材料多塞进这一格。
     *
     * @return true = 稳定了，可以接着动
     */
    private boolean awaitStable(List<Slot> grid) {
        int now = grid.get(watchingCell).getItem().getCount();
        if (now != watchingCount) {
            if (now < watchingCount) {
                ChatUtils.debug("格子 %d 的数量被服务端改成 %d 个（这一批点完应该是 %d 个），按实际数量接着补",
                    watchingCell, now, watchingCount);
            }
            watchingCount = now;
            watchingTicks = 0;
            return false;
        }
        if (++watchingTicks < Math.max(2, verifyWaitTicks())) return false;
        watchingCell = -1;
        return true;
    }

    /**
     * 手上拿着的材料往这一格放：够放多少放多少（正好够就一下左键，不然一格一格右键放）。
     *
     * @param space 这一格还能放几个
     * @param have  放之前这一格有几个（放完之后用来看服务端认不认这个数）
     */
    private void placeFromCursor(Slot slot, int index, int space, int have) {
        ItemStack cursor = session.handler().getCarried();
        int place = Math.min(space, cursor.getCount());
        if (place <= 0) return;

        if (place == cursor.getCount()) {
            click(slot.index, 0, ContainerInput.PICKUP);
            watch(index, have + place);
            return;
        }
        int placed = 0;
        while (placed < place && click(slot.index, 1, ContainerInput.PICKUP)) {
            placed++;
        }
        watch(index, have + placed);
    }

    /**
     * 材料不在物品栏里，但**别的输入格**里有一叠多的（之前多塞进去的）：从那儿拿。
     *
     * <p>只拿「比这一轮该放的多」的格子，已经摆好的格子不动 —— 不然这边补上了、那边又短了。
     */
    private int findGridSource(List<Slot> grid, Item item, int exceptIndex) {
        int amount = plan.amountForRound(round);
        for (int i = 0; i < grid.size(); i++) {
            if (i == exceptIndex) continue;
            ItemStack stack = grid.get(i).getItem();
            if (stack.isEmpty() || !stack.is(item)) continue;
            if (stack.getCount() > amount) return grid.get(i).index;
        }
        return -1;
    }

    /**
     * 核对格子：计划的格子有对的材料（数量够这一轮用）、别的输入格是空的。
     *
     * <p>不对就再摆一遍（{@link #tickPlace} 会把差的补上、多的清掉），摆了几遍还是不对就报错停下 ——
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
        watchingCell = -1;
        clearFirst = true;
        supplyRetries = 0;
        placeWaitTicks = 0;
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

            ItemStack stack = grid.get(index).getItem();
            if (stack.isEmpty()) {
                return "第 " + index + " 格应该是 " + id(cell.item()) + "，但里面是空的";
            }
            if (!stack.is(cell.item())) {
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
            ItemStack stack = grid.get(i).getItem();
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
    private void tickReady(Minecraft mc) {
        if (plan.station() == CraftPlan.Station.STONECUTTER && recipeIndex < 0) {
            selectStonecutterRecipe(mc);
            return;
        }

        ItemStack result = slotStack(resultSlotId());
        if (!result.isEmpty()) {
            if (!result.is(plan.target())) {
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
        if (!click(resultSlotId(), 0, ContainerInput.QUICK_MOVE)) return;   // 这一 tick 点满了，下一 tick 再点

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
        watchingCell = -1;
        clearFirst = false;
        supplyRetries = 0;
        placeWaitTicks = 0;
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
        watchingCell = -1;
        clearFirst = true;
        supplyRetries = 0;
        placeWaitTicks = 0;
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
     *       {@code Inventory.addStack} 是**先从快捷栏**找位置的；</li>
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
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(item)) continue;
            if (stack.getCount() <= need) {
                if (stack.getCount() > exactCount) {
                    exact = slot.index;
                    exactCount = stack.getCount();
                }
                continue;
            }
            if (fallback < 0) fallback = slot.index;
        }
        return exact >= 0 ? exact : fallback;
    }

    /** 找材料时报一句「这东西到底在哪儿」，方便判断是计划本身短了还是被塞在别处了。 */
    private String describeWhereIs(Item item) {
        int main = 0;
        int hotbar = 0;
        for (Slot slot : inventorySlots()) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(item)) continue;
            if (slot.getContainerSlot() < 9) hotbar += stack.getCount();
            else main += stack.getCount();
        }

        int grid = 0;
        for (Slot slot : gridSlots()) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(item)) grid += stack.getCount();
        }

        ItemStack cursor = session.handler().getCarried();
        int onCursor = !cursor.isEmpty() && cursor.is(item) ? cursor.getCount() : 0;

        return "现在物品栏里这种材料：主背包 " + main + " 个、快捷栏 " + hotbar + " 个、"
            + "合成格 " + grid + " 个、光标 " + onCursor + " 个（一共 "
            + (main + hotbar + grid + onCursor) + " 个）";
    }

    /** 把手上的东西放回主背包 27 格（优先同类半叠）。 */
    private void returnCursor() {
        ItemStack cursor = session.handler().getCarried();
        if (cursor.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            fail("玩家不存在");
            return;
        }

        Slot target = InventoryUtils.wholeTarget(session.handler(), mc.player.getInventory(), cursor);
        if (target == null) {
            fail("主背包 27 格放不下手上剩下的 " + id(cursor.getItem()) + " x" + cursor.getCount());
            return;
        }
        click(target.index, 0, ContainerInput.PICKUP);
    }

    // ------------------------------------------------------------------
    // 切石机选配方
    // ------------------------------------------------------------------

    private void selectStonecutterRecipe(Minecraft mc) {
        if (mc.level == null) {
            fail("没有世界");
            return;
        }

        SelectableRecipe.SingleInputSet<StonecutterRecipe> grouping =
            ((StonecutterMenu) session.handler()).getVisibleRecipes();
        if (grouping == null || grouping.isEmpty()) {
            if (++recipeWaitTicks > READY_WAIT_TICKS * 2) {
                recipeWaitTicks = 0;
                retryPlace("切石机一直没列出可选配方（输入格的石头可能没摆进去）");
            }
            return;
        }

        ContextMap context = SlotDisplayContext.fromLevel(mc.level);
        int index = 0;
        for (SelectableRecipe.SingleInputEntry<StonecutterRecipe> entry : grouping.entries()) {
            for (ItemStack option : entry.recipe().optionDisplay().resolveForStacks(context)) {
                if (!option.isEmpty() && option.is(plan.target())) {
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
            return List.of(session.handler().slots.get(StonecutterMenu.INPUT_SLOT));
        }
        return ((CraftingMenu) session.handler()).getInputGridSlots();
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
            ? CraftingMenu.RESULT_SLOT
            : StonecutterMenu.RESULT_SLOT;
    }

    private ItemStack slotStack(int slotId) {
        return session.handler().slots.get(slotId).getItem();
    }

    /**
     * 点一下某个槽位（{@code ScreenHandler.slots} 的下标就是槽位号）。
     *
     * @return false = 这一 tick 的点击用完了，什么也没点（下一 tick 接着来）
     */
    private boolean click(int slotId, int button, ContainerInput type) {
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
            if (slot.container instanceof Inventory && slot.getContainerSlot() < 36) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** 物品栏（快捷栏 + 主背包）里现在有多少个成品。 */
    private int countCrafted() {
        int total = 0;
        for (Slot slot : session.handler().slots) {
            if (!(slot.container instanceof Inventory)) continue;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(plan.target())) total += stack.getCount();
        }
        return total;
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
