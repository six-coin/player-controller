package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 一次「跟容器打交道」的会话：屏蔽界面 → 右键打开 → 等界面同步 → 点格子 → 收尾。
 *
 * <p>要开容器干活的动作用它就行，不用各自再写一遍这些：
 * <ul>
 *   <li>打开前先把客户端界面挡住（{@link ScreenSuppressor}）；</li>
 *   <li>看向方块右键（{@link ContainerOpener}），等容器界面出来（创造模式背包不算）；</li>
 *   <li>点格子（{@link ContainerClicks}），判断界面是不是被服务端关掉了；</li>
 *   <li>收尾：刷新容器缓存 → 关界面 → 解除屏蔽。</li>
 * </ul>
 *
 * <p>会话不管业务：放什么、拿什么、满没满都是动作自己的事。
 */
public final class ContainerSession {

    /** 等界面打开的结果。 */
    public enum OpenState {
        /** 还没出来：下 tick 接着等。 */
        WAITING,
        /** 界面出来了。 */
        READY,
        /** 等太久了。 */
        TIMEOUT
    }

    /** 等容器界面出来最多等这么多 tick。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;

    /** 默认打开之后等几 tick 再动（让槽位数据同步完）。 */
    private static final int DEFAULT_SETTLE_TICKS = 2;

    private final BlockPos pos;
    private final int settleTicks;
    /** 等的是哪种界面；null = 只要是容器界面（不是玩家自己的那个）就行。 */
    @Nullable
    private final Class<? extends ScreenHandler> expected;

    private ScreenHandler handler;
    private int openWaitTicks;
    private int settleLeft;
    /** 现在是不是我们占着「不显示界面」（占着才由我们放开）。 */
    private boolean holding;

    public ContainerSession(BlockPos pos) {
        this(pos, DEFAULT_SETTLE_TICKS, null);
    }

    /**
     * @param settleTicks 界面出来之后先等几 tick（槽位数据是服务端同步过来的）
     */
    public ContainerSession(BlockPos pos, int settleTicks) {
        this(pos, settleTicks, null);
    }

    /**
     * @param expected 只认这种界面（合成用：工作台 / 切石机各是一种）；null = 什么都行
     */
    public ContainerSession(BlockPos pos, Class<? extends ScreenHandler> expected) {
        this(pos, DEFAULT_SETTLE_TICKS, expected);
    }

    private ContainerSession(BlockPos pos, int settleTicks,
                             @Nullable Class<? extends ScreenHandler> expected) {
        this.pos = pos.toImmutable();
        this.settleTicks = Math.max(0, settleTicks);
        this.expected = expected;
    }

    public BlockPos pos() {
        return pos;
    }

    // ------------------------------------------------------------------

    /** 开始：挡住客户端界面，然后看向容器右键打开。 */
    public void open(MinecraftClient mc, ClientPlayerEntity player) {
        if (!holding) {
            ScreenSuppressor.acquire();
            holding = true;
        }
        ContainerOpener.open(mc, player, pos);
    }

    /** 每次 tick 调一次，直到返回 {@link OpenState#READY} 或 {@link OpenState#TIMEOUT}。 */
    public OpenState awaitOpen(MinecraftClient mc) {
        if (handler != null) return OpenState.READY;

        openWaitTicks++;
        ScreenHandler current = mc.player == null ? null : mc.player.currentScreenHandler;
        if (current != null && current != mc.player.playerScreenHandler
            && !ContainerCacheTracker.isCreativeInventory(mc)
            && (expected == null || expected.isInstance(current))) {
            handler = current;
            settleLeft = settleTicks;
            ChatUtils.debug("容器界面已打开：%s（%d 格）",
                current.getClass().getSimpleName(), current.slots.size());
            return OpenState.READY;
        }

        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) return OpenState.TIMEOUT;
        return OpenState.WAITING;
    }

    /** 界面刚出来那一小段等待到头了没（每次 tick 调一次；到点了返回 true）。 */
    public boolean settled() {
        if (settleLeft > 0) settleLeft--;
        return settleLeft <= 0;
    }

    /** 界面还在不在（被服务端关掉了就是 false）。 */
    public boolean stillOpen(MinecraftClient mc) {
        return handler != null && mc.player != null && mc.player.currentScreenHandler == handler;
    }

    public boolean isOpen() {
        return handler != null;
    }

    public ScreenHandler handler() {
        return handler;
    }

    // ------------------------------------------------------------------

    /** 点一下这个格子（拿 / 放）。 */
    public boolean click(Slot slot, int button, SlotActionType type, String action) {
        return ContainerClicks.click(handler, slot, button, type, action);
    }

    /** 点一下这个格子，但不打日志（一次任务里会点很多下时用）。 */
    public boolean clickQuiet(Slot slot, int button, SlotActionType type) {
        return ContainerClicks.clickQuiet(handler, slot, button, type);
    }

    /** Shift 点击：让服务端自己决定放哪儿。 */
    public boolean quickMove(Slot slot, String action) {
        return ContainerClicks.quickMove(handler, slot, action);
    }

    /** 容器还剩几个空格（界面开着时才算得出来；没开就是 -1）。 */
    public int freeSlots() {
        if (handler == null) return -1;

        int free = 0;
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            if (slot.getStack().isEmpty()) free++;
        }
        return free;
    }

    /** 界面里现在有什么（散装物品 + 每个潜影盒的内容）。 */
    public ContainerCacheManager.Breakdown breakdown() {
        return handler == null ? null : ContainerCacheManager.breakdown(handler);
    }

    /** 收尾：刷新容器缓存 → 关掉界面 → 解除「不显示界面」。 */
    public void close(MinecraftClient mc) {
        if (handler != null) {
            ContainerCacheUpdater.refresh(mc, pos, handler);
        }
        if (mc.player != null && handler != null && mc.player.currentScreenHandler == handler) {
            mc.player.closeHandledScreen();
        }
        if (holding) {
            ScreenSuppressor.release();
            holding = false;
        }
    }
}
