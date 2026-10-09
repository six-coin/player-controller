package org.six_coin.playerController.client.action;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheUpdater;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 把**快捷栏指定格**里的整叠东西放进一个容器（{@link TakeItemAction} 的反向操作）。
 *
 * <p>备货流程用它的地方：挖掉暂存处的潜影盒以后，捡到的盒子在快捷栏（第二格），
 * 要把它塞进 item_final；或者把借来的盒子塞回 item_storage。
 * 这两个格子在 {@link ContainerPutAction}（只管主背包 27 格）的覆盖范围之外，所以单独做一个。
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

    private static final int SETTLE_TICKS = 2;
    private static final int MAX_OPEN_WAIT_TICKS = 60;
    private static final int MAX_STEP_WAIT_TICKS = 60;
    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final BlockPos pos;
    private final int hotbarIndex;
    private final boolean wantDetail;

    private ScreenHandler handler;
    private Phase phase = Phase.OPENING;

    private int openWaitTicks;
    private int settleTicks;
    private int stepWaitTicks;
    private int totalTicks;
    private int moved;

    private boolean pendingClick;
    private Step pendingStep;
    private int clickCursorCount;

    private boolean allCleared;
    private boolean containerFull;
    private int freeSlots = -1;
    private ContainerCacheManager.Breakdown detail;

    /**
     * @param hotbarIndex 要搬走的快捷栏格（背包下标 0~8）
     */
    public PutHotbarItemAction(BlockPos pos, int hotbarIndex, boolean wantDetail) {
        this.pos = pos.toImmutable();
        this.hotbarIndex = hotbarIndex;
        this.wantDetail = wantDetail;
    }

    /** 快捷栏那一格空了（东西都进容器了）。 */
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
        return "把快捷栏第 " + (hotbarIndex + 1) + " 格放进 " + pos.toShortString();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        ScreenSuppressor.acquire();

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

        ItemStack stack = player.getInventory().getStack(hotbarIndex);
        ChatUtils.debug("把快捷栏第 %d 格（%s）放进 %s",
            hotbarIndex + 1, describe(stack), pos.toShortString());
        if (stack.isEmpty()) {
            allCleared = true;
            ChatUtils.debug("快捷栏第 %d 格本来就是空的，不用放", hotbarIndex + 1);
            finish();
            return;
        }

        ContainerOpener.open(mc, player, pos);
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
        if (phase != Phase.OPENING && handler != null && player.currentScreenHandler != handler) {
            fail("容器界面被关掉了");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(player);
            case SETTLE -> {
                if (++settleTicks >= SETTLE_TICKS) {
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
        if (wantDetail && handler != null) {
            detail = ContainerCacheManager.breakdown(handler);
            freeSlots = countFreeSlots();
            if (freeSlots == 0) containerFull = true;
        }

        if (handler != null) {
            ContainerCacheUpdater.refresh(MinecraftClient.getInstance(), pos, handler);
        }
        closeScreen();
        ScreenSuppressor.release();

        if (failureReason() != null) {
            allCleared = false;
            ChatUtils.error(name() + "失败：" + failureReason());
        }

        JsonObject result = new JsonObject();
        result.addProperty("all_cleared", allCleared);
        ChatUtils.debug("放物结果：all_cleared=%s，搬了 %d 次，free_slots=%d", allCleared, moved, freeSlots);
        ChatUtils.rawCopyable(result.toString());
    }

    // ------------------------------------------------------------------

    private void tickOpening(ClientPlayerEntity player) {
        openWaitTicks++;
        ScreenHandler current = player.currentScreenHandler;
        if (current != null && current != player.playerScreenHandler) {
            handler = current;
            phase = Phase.SETTLE;
            settleTicks = 0;
            ChatUtils.debug("容器已打开：%d 格", handler.slots.size());
            return;
        }
        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("等待容器界面超时（" + openWaitTicks + " tick）");
        }
    }

    private void tickCarry() {
        Slot source = hotbarSlot(hotbarIndex);
        if (source == null) {
            fail("找不到快捷栏第 " + (hotbarIndex + 1) + " 格");
            return;
        }
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
                        allCleared = true;
                        ChatUtils.debug("快捷栏第 %d 格的东西都进容器了", hotbarIndex + 1);
                        phase = Phase.DONE;
                        return;
                    }
                    if (cursor.getCount() < clickCursorCount) {
                        // 放进去一部分，还剩一些：下一 tick 继续找能收的格子
                        pendingClick = false;
                        stepWaitTicks = 0;
                        return;
                    }
                    if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                        fail("往容器里放物品失败（光标上还有 " + describe(cursor) + "）");
                    }
                }
                case RETURN -> {
                    if (cursor.isEmpty()) {
                        pendingClick = false;
                        containerFull = true;
                        allCleared = false;
                        ChatUtils.debug("容器装不下，剩下的已经放回快捷栏第 %d 格", hotbarIndex + 1);
                        phase = Phase.DONE;
                        return;
                    }
                    if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                        fail("把剩下的放回快捷栏失败（光标上还有 " + describe(cursor) + "）");
                    }
                }
            }
            return;
        }

        if (cursor.isEmpty()) {
            ItemStack stack = source.getStack();
            if (stack.isEmpty()) {
                allCleared = true;
                ChatUtils.debug("快捷栏第 %d 格空了，放完了", hotbarIndex + 1);
                phase = Phase.DONE;
                return;
            }
            if (findTarget(stack) == null) {
                containerFull = true;
                allCleared = false;
                ChatUtils.debug("容器收不下 " + describe(stack) + "，结束");
                phase = Phase.DONE;
                return;
            }
            click(source, 0, SlotActionType.PICKUP, "拿起 " + describe(stack), Step.PICK);
            return;
        }

        Slot target = findTarget(cursor);
        if (target == null) {
            // 容器装不下了：把光标上剩下的塞回快捷栏那一格
            click(source, 0, SlotActionType.PICKUP, "把剩下的放回快捷栏", Step.RETURN);
            return;
        }
        click(target, 0, SlotActionType.PICKUP, "放进 " + InventoryUtils.describe(target), Step.PLACE);
    }

    /** 容器的格子里有没有能收下这一叠的（空格，或者同类还有余量的格子）。 */
    @Nullable
    private Slot findTarget(ItemStack stack) {
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            if (!slot.canInsert(stack)) continue;

            ItemStack existing = slot.getStack();
            if (existing.isEmpty()) return slot;
            if (ItemStack.areItemsAndComponentsEqual(existing, stack)
                && existing.getCount() < Math.min(slot.getMaxItemCount(stack), stack.getMaxCount())) {
                return slot;
            }
        }
        return null;
    }

    @Nullable
    private Slot hotbarSlot(int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) return slot;
        }
        return null;
    }

    private void click(Slot slot, int button, SlotActionType type, String action, Step step) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null) {
            fail("拿不到 interactionManager");
            return;
        }
        ChatUtils.debug("点击：%s（slot=%d，光标=%s）", action, slot.id, describe(handler.getCursorStack()));
        clickCursorCount = handler.getCursorStack().getCount();
        mc.interactionManager.clickSlot(handler.syncId, slot.id, button, type, mc.player);
        pendingClick = true;
        pendingStep = step;
        stepWaitTicks = 0;
        moved++;
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;
        mc.player.closeHandledScreen();
    }

    private int countFreeSlots() {
        if (handler == null) return -1;
        int free = 0;
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            if (slot.getStack().isEmpty()) free++;
        }
        return free;
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
