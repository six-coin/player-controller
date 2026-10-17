package org.six_coin.playerController.client.mending;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * mending 的一趟「换物品」：需要的话打开末影箱，点几下格子（和副手交换），再关掉。
 *
 * <p><b>为什么末影箱只在换物品的时候开</b>：界面开着的时候，服务端只同步那个界面里的格子
 * （末影箱界面里没有副手格和盔甲格），副手的耐久在客户端就一直是旧的 ——
 * 检测就看不到「正在修」这件事。所以平时箱子是关着的，只有真要换东西的这一趟才开。
 *
 * <p><b>为什么要做成 {@link Action}</b>：打开界面要横跨几个 tick，而
 * {@code ActionManager} 在没有任务跑的时候会兜底把「不显示容器界面」的计数清零
 * （见 {@code ScreenSuppressor#reset()}）。这一趟占着动作队列，兜底就不会误伤；
 * 万一中途抛异常，{@link #onEnd()} 也会把界面关掉、抑制放开。
 *
 * <p>点哪一格、什么时候结束都不在这里，交给 {@link MendingManager#swapStep}：
 * 这个动作只管「开界面 → 等格子内容同步 → 每一 tick 问一次还要不要继续 → 关界面」。
 */
public class MendingSwapAction extends Action {

    /** 等末影箱界面出来最多等这么多 tick。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;

    /** 要打开的末影箱；null = 不用开界面，直接点玩家自己的物品栏。 */
    @Nullable
    private final BlockPos chestPos;

    @Nullable
    private ScreenHandler handler;
    private int openWaitTicks;
    private int settleLeft;
    private boolean opened;
    /** 现在是不是我们占着「不显示容器界面」（占着才由我们放开）。 */
    private boolean suppressorHeld;

    /**
     * @param chestPos 要打开的末影箱；传 null 就是「这一趟不用开界面，只点物品栏」
     */
    public MendingSwapAction(@Nullable BlockPos chestPos) {
        this.chestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    @Override
    public String name() {
        if (chestPos == null) return "mending 换物品（物品栏）";
        return "mending 换物品（末影箱 " + chestPos.toShortString() + "）";
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

        if (chestPos == null) {
            handler = player.currentScreenHandler;
            if (handler == null) {
                fail("拿不到物品栏界面");
                return;
            }
            return;
        }

        ScreenSuppressor.acquire();
        suppressorHeld = true;
        ContainerOpener.open(mc, player, chestPos, Owner.MENDING);
        // 右键完了就把朝向还回去：不然 mending 一直占着「谁在转头」，攻击那边每 tick 都会被拒
        RotateManager.get().release(Owner.MENDING);
        ChatUtils.debug("mending：右键末影箱 %s，等界面", chestPos.toShortString());
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不见了");
            return;
        }

        if (!opened) {
            if (!awaitOpen(mc, player)) return;
            opened = true;
        }

        if (handler == null || player.currentScreenHandler != handler) {
            fail("界面被关掉了");
            return;
        }

        if (!MendingManager.get().swapStep(player, handler, chestPos != null)) {
            finish();
        }
    }

    @Override
    protected void onEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (chestPos != null) {
            if (handler != null && mc.player != null && mc.player.currentScreenHandler == handler) {
                mc.player.closeHandledScreen();
            }
        }
        if (suppressorHeld) {
            ScreenSuppressor.release();
            suppressorHeld = false;
        }
        handler = null;
    }

    // ------------------------------------------------------------------

    /**
     * 等界面出来、再等格子内容同步过来。
     *
     * @return true = 可以点格子了
     */
    private boolean awaitOpen(MinecraftClient mc, ClientPlayerEntity player) {
        if (chestPos == null) return true;

        if (handler == null) {
            ScreenHandler current = player.currentScreenHandler;
            if (current != null && current != player.playerScreenHandler) {
                handler = current;
                settleLeft = PlayerControllerConfig.getNetworkDelayTicks();
                ChatUtils.debug("mending：末影箱界面已打开（%d 格），等格子内容同步", current.slots.size());
                return false;
            }
            if (++openWaitTicks > MAX_OPEN_WAIT_TICKS) {
                fail("等末影箱界面超时（" + chestPos.toShortString() + "）");
            }
            return false;
        }

        // 状态号跟着内容包一起变过来，变了就说明内容到了；一直不动就按 network_delay_ticks 兜底
        if (handler.getRevision() != 0) settleLeft = 0;
        if (settleLeft > 0) {
            settleLeft--;
            return false;
        }
        ChatUtils.debug("mending：末影箱界面内容已同步");
        return true;
    }
}
