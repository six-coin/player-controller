package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
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

    private final BlockPos pos;
    /** 等的是哪种界面；null = 只要是容器界面（不是玩家自己的那个）就行。 */
    @Nullable
    private final Class<? extends ScreenHandler> expected;

    private ScreenHandler handler;
    private int openWaitTicks;
    /** 兜底：状态号一直没动静时，最多再等这么多 tick（界面出来的时候按配置充满）。 */
    private int settleLeft;
    /** 现在是不是我们占着「不显示界面」（占着才由我们放开）。 */
    private boolean holding;

    public ContainerSession(BlockPos pos) {
        this(pos, null);
    }

    /**
     * @param expected 只认这种界面（合成用：工作台 / 切石机各是一种）；null = 什么都行
     */
    public ContainerSession(BlockPos pos, @Nullable Class<? extends ScreenHandler> expected) {
        this.pos = pos.toImmutable();
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

    /**
     * 每次 tick 调一次，直到返回 {@link OpenState#READY} 或 {@link OpenState#TIMEOUT}。
     *
     * <p>界面这一下出来的时候，**格子里的内容还没到**：服务端是先发「开界面」，再把背包内容
     * 跟在一个内容包里发过来的。所以这里要等到内容真的到了才算 READY（见 {@link #settled()}），
     * 不然会读到一箱子空气。
     */
    public OpenState awaitOpen(MinecraftClient mc) {
        if (handler != null) {
            if (settled()) return OpenState.READY;
            return ++openWaitTicks > MAX_OPEN_WAIT_TICKS ? OpenState.TIMEOUT : OpenState.WAITING;
        }

        openWaitTicks++;
        ScreenHandler current = mc.player == null ? null : mc.player.currentScreenHandler;
        if (current != null && current != mc.player.playerScreenHandler
            && !ContainerCacheTracker.isCreativeInventory(mc)
            && (expected == null || expected.isInstance(current))) {
            handler = current;
            settleLeft = PlayerControllerConfig.getNetworkDelayTicks();
            ChatUtils.debug("容器界面已打开：%s（%d 格），等格子内容同步过来",
                current.getClass().getSimpleName(), current.slots.size());
            if (settled()) return OpenState.READY;
        }

        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) return OpenState.TIMEOUT;
        return OpenState.WAITING;
    }

    /**
     * 界面里的内容同步过来了没有（每次 tick 调一次；到点了返回 true）。
     *
     * <p>正常情况看界面的「状态号」：客户端这边是跟着服务端的内容包一起更新过来的，界面刚建出来时是 0，
     * 收到内容包（{@code InventoryS2CPacket} / 单格更新）之后才变成服务端那边的号 —— 内容一到就立刻开工，
     * 网络快就不用白等。
     *
     * <p>等不到就按「网络延迟」（{@code actions.network_delay_ticks}）兜底放行：
     * 万一哪个界面不走这一套（状态号一直是 0），也不至于一直卡在这儿。
     */
    public boolean settled() {
        if (handler != null && handler.getRevision() != 0) return true;
        settleLeft--;
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

    /** Shift 点击，但不打日志（一轮点很多下时用）。 */
    public boolean quickMoveQuiet(Slot slot) {
        return ContainerClicks.quickMoveQuiet(handler, slot);
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

    /**
     * 主动把这次打开的容器的内容写回缓存。
     *
     * <p>备货任务期间 {@link ContainerCacheTracker} 的自动刷新是挂起的（见那边的说明），
     * 缓存就靠这里更新：**每次取完货、界面还开着**的时候读一遍格子，把这一箱子的东西记下来。
     * 取货的动作（{@code ContainerGetAction} / {@code TakeItemAction} …）收尾都会走 {@link #close}，
     * 所以每取一次就刷新一次，不会攒到最后。
     */
    public void refreshCache(MinecraftClient mc) {
        if (handler == null) return;
        int changed = ContainerCacheUpdater.refresh(mc, pos, handler);
        if (changed > 0) {
            ChatUtils.debug("已把 " + pos.toShortString() + " 的内容主动写回容器缓存");
        }
    }

    /** 收尾：刷新容器缓存 → 关掉界面 → 解除「不显示界面」→ 把朝向还回去。 */
    public void close(MinecraftClient mc) {
        refreshCache(mc);
        if (mc.player != null && handler != null && mc.player.currentScreenHandler == handler) {
            mc.player.closeHandledScreen();
        }
        if (holding) {
            ScreenSuppressor.release();
            holding = false;
        }
        // 开容器的时候是用 Owner.STOCK 转的头（见 ContainerOpener），必须在这里还回去：
        // 不还的话 STOCK 会一直占着朝向，之后 mine / attack 的转头全都被
        // 「不让转头：现在是 STOCK 在用」挡掉（挖掘会一直卡在 ROTATE_DENIED）。
        RotateManager.get().release(Owner.STOCK);
    }
}
