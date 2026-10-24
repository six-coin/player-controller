package org.six_coin.playerController.client.container;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.List;

/**
 * 实时刷新：监听玩家每次打开 / 关闭容器。
 *
 * <p>规则：只要这次打开的容器在缓存里（大箱子任意一半在缓存里就算），关界面的时候就把
 * 界面里容器格子重新统计一遍写回缓存；内容没变化就不写文件，只打一条 debug。
 *
 * <p>怎么认出打开的是哪个容器：看准星指着的那一格（打开容器时准星就在容器上，界面开着的时候
 * 准星也不会动）；如果是大箱子，再按方块状态算出另一半在哪一格
 * （见 {@link ContainerTypes#otherHalf(net.minecraft.world.level.Level, BlockPos)}），一起看。
 * 不做「附近有几个候选」这种猜测。
 *
 * <p>内容是从「界面里的容器格子」读的，因为客户端拿不到箱子方块自己同步过来的内容
 * （原版是把槽位数据发给界面处理器的）。
 *
 * <p><b>可以整体挂起</b>（{@link #setSuspended(boolean)}）：备货任务期间必须挂起 ——
 * 那套流程是自己开箱子、自己点格子，而且客户端视角不动（转头只是给服务端发包），
 * 这里「看准星指着哪一格」会认错容器，把不相干的缓存写坏。备货改用
 * {@link ContainerSession#close} 里那次**主动刷新**（拿完东西、界面还开着的时候读一遍）。
 */
public final class ContainerCacheTracker {

    @Nullable
    private static AbstractContainerMenu openHandler;
    private static int openTicks;

    /** 这次打开的容器在哪一格（准星指着的那格）；不在缓存里就是 null。 */
    @Nullable
    private static BlockPos viewingPos;

    /** 是不是被挂起了（备货任务期间是 true：不跟踪、也不自动刷新）。 */
    private static boolean suspended;

    private ContainerCacheTracker() {
    }

    /** 挂起 / 恢复「开关容器时自动刷新缓存」。 */
    public static void setSuspended(boolean value) {
        if (suspended == value) return;
        suspended = value;
        reset();
        ChatUtils.debug(value ? "容器缓存的自动刷新已挂起（备货流程会自己主动刷新）"
            : "容器缓存的自动刷新已恢复");
    }

    public static boolean isSuspended() {
        return suspended;
    }

    public static void tick(Minecraft client) {
        if (suspended) {
            // 挂起期间连「这次开的是哪个容器」都不记：恢复的时候不会拿旧界面去刷新
            if (openHandler != null) reset();
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            reset();
            return;
        }

        AbstractContainerMenu current = player.containerMenu;
        if (current == openHandler) {
            openTicks++;
            return;
        }

        // 界面变了：先把上一个界面收尾（可能是关闭，也可能直接换了另一个界面）
        AbstractContainerMenu previous = openHandler;
        int previousTicks = openTicks;
        BlockPos previousPos = viewingPos;

        openHandler = current;
        openTicks = 0;
        viewingPos = null;

        if (previous != null && previousPos != null) {
            // 内容跟在开界面的包后面才同步过来：状态号还是 0（一个内容包都没收到）又开得太短，就不刷新，
            // 免得把一箱子空气写进缓存
            if (previous.getStateId() == 0
                && previousTicks < PlayerControllerConfig.getNetworkDelayTicks()) {
                ChatUtils.debug("容器界面只开了 " + previousTicks + " tick，数据可能还没同步，这次不刷新缓存");
            } else {
                ContainerCacheUpdater.refresh(client, previousPos, previous);
            }
        }

        if (current == null || current == player.inventoryMenu) return;

        // 创造模式物品栏虽然也有自己的界面处理器，但它不是容器：直接不理
        if (isCreativeInventory(client)) {
            ChatUtils.debug("打开的是创造模式物品栏，不是容器，不跟踪也不刷新");
            return;
        }

        BlockPos looked = PlayerUtils.lookedAtBlock();
        if (looked == null) {
            ChatUtils.debug("看不出打开的是哪个方块（准星没对着方块），这次不跟踪");
            return;
        }

        List<CachedContainer> found = ContainerCacheUpdater.findEntries(client.level, looked);
        if (found.isEmpty()) {
            ChatUtils.debug("打开的容器 " + looked.toShortString()
                + "（含大箱子按方块状态算出的另一半）不在缓存里，不跟踪");
            return;
        }

        viewingPos = looked;
        ChatUtils.debug("打开的容器在缓存里：" + describe(found) + "，关界面时会重新记一遍");
    }

    /** 现在打开的是不是创造模式物品栏（它不是真容器，别去认它）。 */
    public static boolean isCreativeInventory(Minecraft client) {
        return client.gui.screen() instanceof CreativeModeInventoryScreen;
    }

    private static String describe(List<CachedContainer> containers) {
        StringBuilder sb = new StringBuilder();
        for (CachedContainer container : containers) {
            if (sb.length() > 0) sb.append("、");
            sb.append("#").append(container.id()).append(" ").append(container.coordString());
        }
        return sb.toString();
    }

    private static void reset() {
        openHandler = null;
        openTicks = 0;
        viewingPos = null;
    }
}
