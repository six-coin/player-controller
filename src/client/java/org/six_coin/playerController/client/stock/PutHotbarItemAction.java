package org.six_coin.playerController.client.stock;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 把**快捷栏某几格**里的整叠东西放进一个容器（{@link TakeItemAction} 的反向操作）。
 *
 * <p>两处用到它：
 * <ul>
 *   <li>备货第一部分阶段3：挖掉暂存处的潜影盒以后，捡到的盒子在快捷栏第二格，
 *       要把它塞进 item_final / item_storage（单格）；</li>
 *   <li>备货第二部分阶段3：{@code /pc recipe} 的成品会 QuickMove 到快捷栏 4~9 格，
 *       合成完要把这几格一起放进目标容器（一段）。
 * </ul>
 *
 * <p>容器收不下就 {@link #allCleared()} = false、{@link #containerFull()} = true，
 * 由调用方决定换下一个容器（跟 {@link ContainerPutAction} 一个套路）。
 * 一 tick 最多一下点击，点完等光标状态变成预期值再继续。
 */
public class PutHotbarItemAction extends Action {

    private enum Phase {
        OPENING,
        /** 打开之后等几 tick，让槽位同步完。 */
        SETTLE,
        CARRY,
        DONE
    }

    /** 上一次点击在干什么，用来判断点成功了没。 */
    private enum Step {
        PICK,
        PLACE,
        RETURN
    }

    private static final int MAX_STEP_WAIT_TICKS = 60;
    private static final int MAX_TOTAL_TICKS = 20 * 120;

    /** 一次最多搬几叠（防止哪里出错时一直搬）。 */
    private static final int MAX_STACKS = 64;

    private final BlockPos pos;
    private final int hotbarFrom;
    private final int hotbarTo;
    private final boolean wantDetail;

    private final ContainerSession session;
    private Phase phase = Phase.OPENING;

    private int stepWaitTicks;
    private int totalTicks;
    private int moved;
    private int stacks;

    private boolean pendingClick;
    private Step pendingStep;
    private int clickCursorCount;
    /** 正在搬的那一格（背包下标 0~8）。 */
    private int currentIndex = -1;

    private boolean allCleared;
    private boolean containerFull;
    private int freeSlots = -1;
    private ContainerCacheManager.Breakdown detail;

    /**
     * @param hotbarIndex 要搬走的快捷栏格（背包下标 0~8）
     */
    public PutHotbarItemAction(BlockPos pos, int hotbarIndex, boolean wantDetail) {
        this(pos, hotbarIndex, hotbarIndex, wantDetail);
    }

    /**
     * @param hotbarFrom 起始格（含，背包下标 0~8）
     * @param hotbarTo   结束格（含）
     */
    public PutHotbarItemAction(BlockPos pos, int hotbarFrom, int hotbarTo, boolean wantDetail) {
        this.pos = pos.toImmutable();
        this.hotbarFrom = Math.min(hotbarFrom, hotbarTo);
        this.hotbarTo = Math.max(hotbarFrom, hotbarTo);
        this.wantDetail = wantDetail;
        this.session = new ContainerSession(this.pos);
    }

    /** 快捷栏那几格空了（东西都进容器了）。 */
    public boolean allCleared() {
        return allCleared;
    }

    /** 容器一格空位都没有 / 装不下这一叠了。 */
    public boolean containerFull() {
        return containerFull;
    }

    /** 还剩几个空格（wantDetail 时才有意义）。 */
    public int freeSlots() {
        return freeSlots;
    }

    /** 放完之后容器里剩什么（wantDetail 时才有意义）。 */
    public ContainerCacheManager.Breakdown detail() {
        return detail;
    }

    @Override
    public String name() {
        return hotbarFrom == hotbarTo
            ? "把快捷栏第 " + (hotbarFrom + 1) + " 格放进 " + pos.toShortString()
            : "把快捷栏第 " + (hotbarFrom + 1) + "~" + (hotbarTo + 1) + " 格放进 " + pos.toShortString();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            fail(pos.toShortString() + " 不是容器（没有物品栏）");
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("容器 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }
        if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
            fail("光标上还有物品，先放下再执行");
            return;
        }

        ItemStack first = firstStack(player);
        ChatUtils.debug("把快捷栏第 %d~%d 格（第一叠是 %s）放进 %s",
            hotbarFrom + 1, hotbarTo + 1, InventoryUtils.describe(first), pos.toShortString());
        if (first.isEmpty()) {
            allCleared = true;
            ChatUtils.debug("快捷栏第 %d~%d 格本来就是空的，不用放", hotbarFrom + 1, hotbarTo + 1);
            finish();
            return;
        }

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
        if (phase != Phase.OPENING && session.isOpen() && !session.stillOpen(mc)) {
            fail("容器界面被关掉了");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(mc);
            case SETTLE -> {
                if (session.settled()) {
                    phase = Phase.CARRY;
                    stepWaitTicks = 0;
                }
            }
            case CARRY -> tickCarry();
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (wantDetail && session.isOpen()) {
            detail = session.breakdown();
            freeSlots = session.freeSlots();
            if (freeSlots == 0) containerFull = true;
        }
        session.close(mc);

        if (failureReason() != null) {
            allCleared = false;
            ChatUtils.error(name() + "失败：" + failureReason());
        }

        JsonObject result = new JsonObject();
        result.addProperty("all_cleared", allCleared);
        ChatUtils.debug("放物结果：all_cleared=%s，搬了 %d 次，free_slots=%d", allCleared, moved, freeSlots);
        output().rawCopyable(result.toString());
    }

    // ------------------------------------------------------------------

    private void tickOpening(MinecraftClient mc) {
        switch (session.awaitOpen(mc)) {
            case READY -> phase = Phase.SETTLE;
            case TIMEOUT -> fail("等待容器界面超时");
            case WAITING -> {
            }
        }
    }

    private void tickCarry() {
        ScreenHandler handler = session.handler();
        ItemStack cursor = handler.getCursorStack();

        // 上一次点击还没生效
        if (pendingClick) {
            switch (pendingStep) {
                case PICK -> {
                    if (!cursor.isEmpty()) {
                        pendingClick = false;
                        stepWaitTicks = 0;
                        return;
                    }
                    if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                        fail("拿起快捷栏里的东西失败");
                    }
                }
                case PLACE -> {
                    if (cursor.isEmpty()) {
                        pendingClick = false;
                        stepWaitTicks = 0;
                        stacks++;
                        if (stacks >= MAX_STACKS) {
                            allCleared = false;
                            fail("搬了 " + stacks + " 叠还没搬完，先停下看看");
                            return;
                        }
                        return;   // 下一 tick 接着搬下一格
                    }
                    if (cursor.getCount() < clickCursorCount) {
                        // 放进去一部分，还剩一些：下一 tick 继续找能收的格子
                        pendingClick = false;
                        stepWaitTicks = 0;
                        return;
                    }
                    if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                        fail("往容器里放物品失败（光标上还有 " + InventoryUtils.describe(cursor) + "）");
                    }
                }
                case RETURN -> {
                    if (cursor.isEmpty()) {
                        pendingClick = false;
                        containerFull = true;
                        allCleared = false;
                        ChatUtils.debug("容器装不下，剩下的已经放回快捷栏第 %d 格", currentIndex + 1);
                        phase = Phase.DONE;
                        return;
                    }
                    if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                        fail("把剩下的放回快捷栏失败（光标上还有 " + InventoryUtils.describe(cursor) + "）");
                    }
                }
            }
            return;
        }

        if (cursor.isEmpty()) {
            // 找还没有搬走的格子（范围内第一个非空的）
            Slot source = findSource();
            if (source == null) {
                allCleared = true;
                ChatUtils.debug("快捷栏第 %d~%d 格都空了，放完了", hotbarFrom + 1, hotbarTo + 1);
                phase = Phase.DONE;
                return;
            }
            currentIndex = source.getIndex();

            ItemStack stack = source.getStack();
            if (InventoryUtils.acceptTarget(handler, stack) == null) {
                containerFull = true;
                allCleared = false;
                ChatUtils.debug("容器收不下 " + InventoryUtils.describe(stack) + "，结束");
                phase = Phase.DONE;
                return;
            }
            click(source, 0, SlotActionType.PICKUP, "拿起 " + InventoryUtils.describe(stack), Step.PICK);
            return;
        }

        Slot current = InventoryUtils.hotbarSlot(handler, currentIndex);
        if (current == null) {
            fail("找不到快捷栏第 " + (currentIndex + 1) + " 格");
            return;
        }

        Slot target = InventoryUtils.acceptTarget(handler, cursor);
        if (target == null) {
            // 容器装不下了：把光标上剩下的塞回快捷栏那一格
            click(current, 0, SlotActionType.PICKUP, "把剩下的放回快捷栏", Step.RETURN);
            return;
        }
        click(target, 0, SlotActionType.PICKUP, "放进 " + InventoryUtils.describe(target), Step.PLACE);
    }

    /** 范围内第一个还有东西的快捷栏格；都空了返回 null。 */
    @Nullable
    private Slot findSource() {
        for (int i = hotbarFrom; i <= hotbarTo; i++) {
            Slot slot = InventoryUtils.hotbarSlot(session.handler(), i);
            if (slot != null && !slot.getStack().isEmpty()) return slot;
        }
        return null;
    }

    /** 快捷栏范围内第一叠东西（只给日志用）。 */
    private ItemStack firstStack(ClientPlayerEntity player) {
        for (int i = hotbarFrom; i <= hotbarTo; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** 点一下（该等的结果交给 {@link Step}）。 */
    private void click(Slot slot, int button, SlotActionType type, String action, Step step) {
        ScreenHandler handler = session.handler();
        if (!session.click(slot, button, type, action)) {
            fail("点不了格子（" + action + "）");
            return;
        }
        clickCursorCount = handler.getCursorStack().getCount();
        pendingClick = true;
        pendingStep = step;
        stepWaitTicks = 0;
        moved++;
    }
}
