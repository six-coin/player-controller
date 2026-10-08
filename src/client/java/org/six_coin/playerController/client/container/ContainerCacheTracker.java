package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
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
 * （见 {@link ContainerTypes#otherHalf(net.minecraft.world.World, BlockPos)}），一起看。
 * 不做「附近有几个候选」这种猜测。
 *
 * <p>内容是从「界面里的容器格子」读的，因为客户端拿不到箱子方块自己同步过来的内容
 * （原版是把槽位数据发给界面处理器的）。
 */
public final class ContainerCacheTracker {

    /** 界面开了几个 tick 之后才信任里面的数据（太短可能还没同步完）。 */
    private static final int MIN_OPEN_TICKS = 2;

    @Nullable
    private static ScreenHandler openHandler;
    private static int openTicks;

    /** 这次打开的容器在哪一格（准星指着的那格）；不在缓存里就是 null。 */
    @Nullable
    private static BlockPos viewingPos;

    private ContainerCacheTracker() {
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            reset();
            return;
        }

        ScreenHandler current = player.currentScreenHandler;
        if (current == openHandler) {
            openTicks++;
            return;
        }

        // 界面变了：先把上一个界面收尾（可能是关闭，也可能直接换了另一个界面）
        ScreenHandler previous = openHandler;
        int previousTicks = openTicks;
        BlockPos previousPos = viewingPos;

        openHandler = current;
        openTicks = 0;
        viewingPos = null;

        if (previous != null && previousPos != null) {
            if (previousTicks < MIN_OPEN_TICKS) {
                ChatUtils.debug("容器界面只开了 " + previousTicks + " tick，数据可能还没同步，这次不刷新缓存");
            } else {
                ContainerCacheUpdater.refresh(client, previousPos, previous);
            }
        }

        if (current == null || current == player.playerScreenHandler) return;

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

        List<CachedContainer> found = ContainerCacheUpdater.findEntries(client.world, looked);
        if (found.isEmpty()) {
            ChatUtils.debug("打开的容器 " + looked.toShortString()
                + "（含大箱子按方块状态算出的另一半）不在缓存里，不跟踪");
            return;
        }

        viewingPos = looked;
        ChatUtils.debug("打开的容器在缓存里：" + describe(found) + "，关界面时会重新记一遍");
    }

    /** 现在打开的是不是创造模式物品栏（它不是真容器，别去认它）。 */
    public static boolean isCreativeInventory(MinecraftClient client) {
        return client.currentScreen instanceof CreativeInventoryScreen;
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
