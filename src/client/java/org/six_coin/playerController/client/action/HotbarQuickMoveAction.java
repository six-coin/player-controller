package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 把快捷栏某几格里的东西 QuickMove 进**自己的 27 格物品栏**（不打开任何界面）。
 *
 * <p>用在备货第二部分阶段3 的特判上：{@code /pc recipe} 的成品会落进快捷栏，如果这个中间产物
 * 正好是下一步要用的材料，与其存进 item_storage 让下一步再跑一趟去取，不如直接挪进自己的物品栏 ——
 * 下一步合成直接从 27 格里拿材料。
 *
 * <p>为什么不用打开背包：QuickMove 的走向是由**界面**决定的，玩家自己那个界面
 * （{@code PlayerScreenHandler}）里快捷栏的 QuickMove 就是「塞进 27 格物品栏」。
 * 而玩家自己的界面一直存在（{@code player.playerScreenHandler}，syncId 0），
 * 只要此刻没有别的界面开着（服务端那边的 currentScreenHandler 就是它），
 * 直接对着这个 syncId 发点击就会被接受 —— 不需要真的把背包画出来。
 *
 * <p>点一下等这一格变空再点下一格；27 格塞不下（很少见）就把那几格记进 {@link #leftovers()}，
 * 由调用方决定怎么办（一般是照旧存进 item_storage）。
 */
public class HotbarQuickMoveAction extends Action {

    private enum Phase {
        /** 等别的界面关掉、服务端那边切回玩家自己的界面。 */
        WAIT_IDLE,
        /** 一格一格 QuickMove。 */
        MOVE,
        DONE
    }

    /** 关掉别的界面之后再等几 tick（服务端也要把 currentScreenHandler 切回来）。 */
    private static final int SETTLE_TICKS = 3;

    private static final int MAX_IDLE_WAIT_TICKS = 60;

    /** 一下点击最多等这么多 tick 生效。 */
    private static final int MAX_MOVE_WAIT_TICKS = 20;

    private static final int MAX_TOTAL_TICKS = 20 * 60;

    private final int hotbarFrom;
    private final int hotbarTo;

    private Phase phase = Phase.WAIT_IDLE;
    private int settleTicks;
    private int idleWaitTicks;
    private int moveWaitTicks;
    private int totalTicks;
    private int moved;

    /** 正在等结果的那一格（背包下标 0~8）；-1 = 没在等。 */
    private int pendingIndex = -1;

    /** 塞不进 27 格、留在快捷栏里的格子（背包下标）。 */
    private final List<Integer> leftoverIndexes = new ArrayList<>();

    private boolean allMoved;

    /**
     * @param hotbarFrom 起始格（含，背包下标 0~8）
     * @param hotbarTo   结束格（含）
     */
    public HotbarQuickMoveAction(int hotbarFrom, int hotbarTo) {
        this.hotbarFrom = Math.min(hotbarFrom, hotbarTo);
        this.hotbarTo = Math.max(hotbarFrom, hotbarTo);
    }

    /** 这几格是不是都挪进物品栏了（有没有剩下来的）。 */
    public boolean allMoved() {
        return allMoved;
    }

    /** 没挪进去的那些格子的说明（没剩就是空串）。 */
    public String leftovers() {
        if (leftoverIndexes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int index : leftoverIndexes) {
            if (sb.length() > 0) sb.append("、");
            sb.append("第 ").append(index + 1).append(" 格的 ").append(describe(hotbarStack(index)));
        }
        return sb.toString();
    }

    /** 挪了几格。 */
    public int moved() {
        return moved;
    }

    @Override
    public String name() {
        return hotbarFrom == hotbarTo
            ? "把快捷栏第 " + (hotbarFrom + 1) + " 格挪进物品栏"
            : "把快捷栏第 " + (hotbarFrom + 1) + "~" + (hotbarTo + 1) + " 格挪进物品栏";
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

        if (findNext(player) < 0) {
            allMoved = true;
            ChatUtils.debug("快捷栏第 %d~%d 格本来就是空的", hotbarFrom + 1, hotbarTo + 1);
            finish();
            return;
        }

        // 别的界面开着的话先关掉：服务端那边的 currentScreenHandler 得是我们自己的，
        // 点击才会被接受（关界面的包先发出去，TCP 保证服务端先处理它再处理我们的点击）。
        if (player.currentScreenHandler != player.playerScreenHandler) {
            ChatUtils.debug("先关掉当前界面（%s），才能对自己的物品栏点击",
                player.currentScreenHandler.getClass().getSimpleName());
            player.closeHandledScreen();
        }
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

        switch (phase) {
            case WAIT_IDLE -> tickIdle(player);
            case MOVE -> tickMove(mc, player);
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        ScreenSuppressor.release();
        if (failureReason() == null) {
            ChatUtils.debug("快捷栏第 %d~%d 格挪进物品栏：挪了 %d 格%s",
                hotbarFrom + 1, hotbarTo + 1, moved,
                leftoverIndexes.isEmpty() ? "" : "，剩下 " + leftovers() + " 没挪动");
        }
        allMoved = failureReason() == null && leftoverIndexes.isEmpty();
    }

    // ------------------------------------------------------------------

    private void tickIdle(ClientPlayerEntity player) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            if (++idleWaitTicks > MAX_IDLE_WAIT_TICKS) {
                fail("等了 " + MAX_IDLE_WAIT_TICKS + " tick，别的界面还没关掉");
            }
            return;
        }
        if (++settleTicks < SETTLE_TICKS) return;
        phase = Phase.MOVE;
    }

    private void tickMove(MinecraftClient mc, ClientPlayerEntity player) {
        ScreenHandler handler = player.playerScreenHandler;

        // 等上一次点击生效
        if (pendingIndex >= 0) {
            if (hotbarStack(pendingIndex).isEmpty()) {
                pendingIndex = -1;
                moveWaitTicks = 0;
                return;
            }
            if (++moveWaitTicks > MAX_MOVE_WAIT_TICKS) {
                ChatUtils.debug("快捷栏第 %d 格的 %s 塞不进 27 格物品栏，先放着",
                    pendingIndex + 1, describe(hotbarStack(pendingIndex)));
                leftoverIndexes.add(pendingIndex);
                pendingIndex = -1;
                moveWaitTicks = 0;
            }
            return;
        }

        int index = findNext(player);
        if (index < 0) {
            ChatUtils.debug("快捷栏第 %d~%d 格该挪的都挪完了", hotbarFrom + 1, hotbarTo + 1);
            phase = Phase.DONE;
            return;
        }

        if (!handler.getCursorStack().isEmpty()) {
            fail("光标上还有 " + describe(handler.getCursorStack()) + "，先放下再执行");
            return;
        }

        Slot slot = hotbarSlot(handler, index);
        if (slot == null) {
            leftoverIndexes.add(index);
            return;
        }

        ChatUtils.debug("QuickMove：快捷栏第 %d 格的 %s → 27 格物品栏", index + 1, describe(slot.getStack()));
        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
        moved++;
        pendingIndex = index;
        moveWaitTicks = 0;
    }

    /** 范围内下一格还有东西、而且还没被记为「挪不动」的快捷栏格；没有返回 -1。 */
    private int findNext(ClientPlayerEntity player) {
        for (int i = hotbarFrom; i <= hotbarTo; i++) {
            if (leftoverIndexes.contains(i)) continue;
            if (!player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    @Nullable
    private static Slot hotbarSlot(ScreenHandler handler, int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) return slot;
        }
        return null;
    }

    private static ItemStack hotbarStack(int index) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getInventory().getStack(index);
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
