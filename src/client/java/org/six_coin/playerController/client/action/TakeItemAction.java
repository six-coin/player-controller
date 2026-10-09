package org.six_coin.playerController.client.action;

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
import org.six_coin.playerController.client.container.ContainerCacheUpdater;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.function.Predicate;

/**
 * 从一个容器里拿**一整叠**符合条件的物品，直接放进**快捷栏的指定格**。
 *
 * <p>这是备货流程用的「轮子」（需求里点名要造的那种）：阶段1 从任务前物品暂存处取钻石镐放进快捷栏第一格、
 * 阶段3 从空潜影盒提供处取一个空盒放进快捷栏第三格，走的都是它。
 *
 * <p>为什么不复用 {@link ContainerGetAction}：那个只管主背包 27 格（绝对不碰快捷栏），
 * 而且它会按 item_list 扣数量、只取一部分；这里要的是「拿整叠 + 塞进指定的快捷栏格」。
 *
 * <p>找不到符合条件的东西**不算失败**：动作正常结束，{@link #found()} 是 false，由调用方决定怎么办
 * （备货里是「终止，拒绝执行」）。
 *
 * <p>快捷栏那一格被占了就直接失败（不会乱塞）：备货流程要求快捷栏第二、第三格是留出来的。
 */
public class TakeItemAction extends Action {

    private enum Phase {
        /** 等容器界面打开。 */
        OPENING,
        /** 找符合条件的容器格。 */
        FIND,
        /** 把源格整叠拿到光标上。 */
        PICK,
        /** 放进快捷栏指定格。 */
        DROP,
        DONE
    }

    private static final int MAX_OPEN_WAIT_TICKS = 60;
    private static final int MAX_STEP_WAIT_TICKS = 60;
    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final BlockPos pos;
    private final int hotbarIndex;
    private final Predicate<ItemStack> filter;
    /** 日志里怎么称呼要找的东西。 */
    private final String what;

    private ScreenHandler handler;
    private Phase phase = Phase.OPENING;

    private int openWaitTicks;
    private int stepWaitTicks;
    private int totalTicks;

    private Slot source;
    private ItemStack expected = ItemStack.EMPTY;
    private boolean pendingClick;

    private boolean found;
    private ItemStack taken = ItemStack.EMPTY;

    /**
     * @param hotbarIndex 目标快捷栏格（背包下标 0~8，也就是「快捷栏第 N 格」的 N-1）
     * @param filter      什么样的物品算「要找的」
     * @param what        日志用的名字，比如「钻石镐」「空潜影盒」
     */
    public TakeItemAction(BlockPos pos, int hotbarIndex, Predicate<ItemStack> filter, String what) {
        this.pos = pos.toImmutable();
        this.hotbarIndex = hotbarIndex;
        this.filter = filter;
        this.what = what;
    }

    /** 有没有找到并拿走。 */
    public boolean found() {
        return found;
    }

    /** 拿到手的东西（{@link #found()} 为 false 时是空气）。 */
    public ItemStack taken() {
        return taken;
    }

    @Override
    public String name() {
        return "从 " + pos.toShortString() + " 取一个" + what + "放进快捷栏第 " + (hotbarIndex + 1) + " 格";
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

        ChatUtils.debug("取物到快捷栏：目标 %s，要找 %s，放进快捷栏第 %d 格（背包下标 %d）",
            pos.toShortString(), what, hotbarIndex + 1, hotbarIndex);

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
            case FIND -> tickFind();
            case PICK -> tickPick();
            case DROP -> tickDrop();
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        if (handler != null) {
            ContainerCacheUpdater.refresh(MinecraftClient.getInstance(), pos, handler);
        }
        closeScreen();
        ScreenSuppressor.release();

        if (failureReason() == null) {
            if (found) {
                ChatUtils.debug("取到了 %s，已经在快捷栏第 %d 格", describe(taken), hotbarIndex + 1);
            } else {
                ChatUtils.debug("容器里没有符合条件的%s", what);
            }
        }
    }

    // ------------------------------------------------------------------

    private void tickOpening(ClientPlayerEntity player) {
        openWaitTicks++;
        ScreenHandler current = player.currentScreenHandler;
        if (current != null && current != player.playerScreenHandler) {
            handler = current;
            phase = Phase.FIND;
            stepWaitTicks = 0;
            ChatUtils.debug("容器已打开：%d 格，找 %s", handler.slots.size(), what);
            return;
        }
        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("等待容器界面超时（" + openWaitTicks + " tick）");
        }
    }

    private void tickFind() {
        source = null;
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !filter.test(stack)) continue;
            source = slot;
            break;
        }

        if (source == null) {
            found = false;
            ChatUtils.debug("容器里没有符合条件的%s", what);
            finish();
            return;
        }

        Slot target = hotbarSlot(hotbarIndex);
        if (target == null) {
            fail("找不到快捷栏第 " + (hotbarIndex + 1) + " 格");
            return;
        }
        if (!target.getStack().isEmpty()) {
            fail("快捷栏第 " + (hotbarIndex + 1) + " 格被 " + InventoryUtils.describe(target) + " 占了，先腾出来");
            return;
        }

        expected = source.getStack().copy();
        ChatUtils.debug("选中第 %d 格：%s → 快捷栏第 %d 格（%s）",
            source.id, describe(expected), hotbarIndex + 1, InventoryUtils.describe(target));
        click(source, 0, SlotActionType.PICKUP, "拿起 " + describe(expected));
        phase = Phase.PICK;
    }

    private void tickPick() {
        ItemStack cursor = handler.getCursorStack();

        if (pendingClick) {
            if (!cursor.isEmpty() && ItemStack.areItemsAndComponentsEqual(cursor, expected)) {
                pendingClick = false;
                stepWaitTicks = 0;
                expected = cursor.copy();
                click(hotbarSlot(hotbarIndex), 0, SlotActionType.PICKUP, "放进快捷栏第 " + (hotbarIndex + 1) + " 格");
                phase = Phase.DROP;
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("拿起源格物品失败：光标上是 " + describe(cursor) + "，期望 " + describe(expected));
                return;
            }
            return;
        }

        click(source, 0, SlotActionType.PICKUP, "拿起源格整叠");
    }

    private void tickDrop() {
        Slot target = hotbarSlot(hotbarIndex);

        if (pendingClick) {
            if (handler.getCursorStack().isEmpty()) {
                pendingClick = false;
                stepWaitTicks = 0;
                if (target == null || target.getStack().isEmpty()) {
                    fail("东西没放进快捷栏第 " + (hotbarIndex + 1) + " 格");
                    return;
                }
                taken = target.getStack().copy();
                found = true;
                ChatUtils.info("已取出 " + describe(taken) + " 放进快捷栏第 " + (hotbarIndex + 1) + " 格");
                phase = Phase.DONE;
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("放进快捷栏失败：光标上还有 " + describe(handler.getCursorStack()));
                return;
            }
            return;
        }

        click(target, 0, SlotActionType.PICKUP, "放进快捷栏第 " + (hotbarIndex + 1) + " 格");
    }

    // ------------------------------------------------------------------

    /** 玩家快捷栏某一格（0~8，也就是背包下标）在界面里的 Slot。 */
    @Nullable
    private Slot hotbarSlot(int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) return slot;
        }
        return null;
    }

    private void click(Slot slot, int button, SlotActionType type, String action) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (slot == null) {
            fail("找不到要点的格子（" + action + "）");
            return;
        }
        if (mc.interactionManager == null) {
            fail("拿不到 interactionManager");
            return;
        }
        ChatUtils.debug("点击：%s（slot=%d，光标=%s）", action, slot.id, describe(handler.getCursorStack()));
        mc.interactionManager.clickSlot(handler.syncId, slot.id, button, type, mc.player);
        pendingClick = true;
        stepWaitTicks = 0;
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;
        mc.player.closeHandledScreen();
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
