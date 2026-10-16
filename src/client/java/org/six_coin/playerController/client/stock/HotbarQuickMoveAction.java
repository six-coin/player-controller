package org.six_coin.playerController.client.stock;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.InventoryUtils;
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
 * <p>搬法：一轮把范围内还有东西的格子一起点一遍（点击是本地预测、立即生效的，一 tick 能连点好几下，
 * 上限见 {@code ContainerClicks.MAX_CLICKS_PER_TICK}），等「网络延迟」那么多 tick 逐格核对。整格空了算挪进去了；
 * 还剩着就是 27 格那边塞不下了（一次 QuickMove 会把能塞的都塞完），记进 {@link #leftovers()}，
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

    private static final int MAX_IDLE_WAIT_TICKS = 60;

    /** 一轮点完之后等几 tick 再核对：等「网络延迟」（{@code actions.network_delay_ticks}）。 */
    private int roundWaitTicks() {
        return PlayerControllerConfig.getNetworkDelayTicks();
    }

    private static final int MAX_TOTAL_TICKS = 20 * 60;

    private final int hotbarFrom;
    private final int hotbarTo;

    private Phase phase = Phase.WAIT_IDLE;
    private int idleTicks;
    private int roundTicks;
    private int totalTicks;
    /** 一共点了几下。 */
    private int clicks;
    /** 挪空了几格。 */
    private int movedStacks;

    /** 这一轮点了哪几格（背包下标）。 */
    private final List<Integer> batch = new ArrayList<>();

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
            sb.append("第 ").append(index + 1).append(" 格的 ").append(InventoryUtils.describe(hotbarStack(index)));
        }
        return sb.toString();
    }

    /** 挪了几格。 */
    public int moved() {
        return movedStacks;
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

        if (findAny(player) < 0) {
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
            case MOVE -> tickMove(player);
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        ScreenSuppressor.release();
        if (failureReason() == null) {
            ChatUtils.debug("快捷栏第 %d~%d 格挪进物品栏：挪了 %d 格（点了 %d 下）%s",
                hotbarFrom + 1, hotbarTo + 1, movedStacks, clicks,
                leftoverIndexes.isEmpty() ? "" : "，剩下 " + leftovers() + " 没挪动");
        }
        allMoved = failureReason() == null && leftoverIndexes.isEmpty();
    }

    // ------------------------------------------------------------------

    private void tickIdle(ClientPlayerEntity player) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            if (++idleTicks > MAX_IDLE_WAIT_TICKS) {
                fail("等了 " + MAX_IDLE_WAIT_TICKS + " tick，别的界面还没关掉");
            }
            return;
        }
        // 关界面的包发出去之后，服务端把 currentScreenHandler 切回来也要一个来回
        if (++idleTicks < PlayerControllerConfig.getNetworkDelayTicks() + 1) return;
        phase = Phase.MOVE;
    }

    private void tickMove(ClientPlayerEntity player) {
        ScreenHandler handler = player.playerScreenHandler;

        // 上一轮还在等核对
        if (roundTicks > 0) {
            roundTicks--;
            if (roundTicks == 0) verifyRound(player);
            return;
        }

        if (!handler.getCursorStack().isEmpty()) {
            fail("光标上还有 " + InventoryUtils.describe(handler.getCursorStack()) + "，先放下再执行");
            return;
        }

        // 一轮把范围内还有东西的格子一起点一遍
        batch.clear();
        int budget = ContainerClicks.MAX_CLICKS_PER_TICK;
        for (int i = hotbarFrom; i <= hotbarTo && batch.size() < budget; i++) {
            if (leftoverIndexes.contains(i)) continue;
            if (player.getInventory().getStack(i).isEmpty()) continue;

            Slot slot = InventoryUtils.hotbarSlot(handler, i);
            if (slot == null) {
                leftoverIndexes.add(i);
                continue;
            }

            batch.add(i);
            ContainerClicks.quickMoveQuiet(handler, slot);
            clicks++;
        }

        if (batch.isEmpty()) {
            ChatUtils.debug("快捷栏第 %d~%d 格该挪的都挪完了", hotbarFrom + 1, hotbarTo + 1);
            phase = Phase.DONE;
            return;
        }

        if (batch.size() > 1) ChatUtils.debug("一轮 QuickMove %d 格", batch.size());
        roundTicks = roundWaitTicks();
    }

    /**
     * 核对上一轮：整格空了算挪进去了；还剩着就是 27 格那边塞不下了
     * （一次 QuickMove 会把能塞的都塞完，所以剩下的不会再有地方放）。
     */
    private void verifyRound(ClientPlayerEntity player) {
        int done = 0;

        for (int index : batch) {
            ItemStack rest = player.getInventory().getStack(index);
            if (rest.isEmpty()) {
                done++;
                continue;
            }
            if (leftoverIndexes.contains(index)) continue;

            leftoverIndexes.add(index);
            ChatUtils.debug("快捷栏第 %d 格的 %s 塞不进 27 格物品栏，先放着（还剩 %d 个）",
                index + 1, InventoryUtils.describe(rest), rest.getCount());
        }

        ChatUtils.debug("核对了 %d 格：挪进去 %d 格", batch.size(), done);
        movedStacks += done;
        batch.clear();
    }

    /** 范围内还有东西、而且还没被记为「挪不动」的快捷栏格；没有返回 -1。 */
    private int findAny(ClientPlayerEntity player) {
        for (int i = hotbarFrom; i <= hotbarTo; i++) {
            if (leftoverIndexes.contains(i)) continue;
            if (!player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private static ItemStack hotbarStack(int index) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getInventory().getStack(index);
    }
}
