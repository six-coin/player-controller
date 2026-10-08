package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实时刷新：监听玩家每次打开 / 关闭容器。
 *
 * <p>规则：只要这次打开的容器在缓存里（大箱子任意一半在缓存里就算），关界面的时候就把
 * 界面里容器格子重新统计一遍写回缓存；内容没变化就不写文件，只打一条 debug。
 *
 * <p>怎么认出打开的是哪个缓存条目：
 * <ol>
 *   <li>先看准星指着的那一格（打开容器时准星基本都还在容器上），大箱子还会看连着的另一半；</li>
 *   <li>准星没对上的话退一步：附近（触及范围内）只有一个缓存的容器时就认它，多个就不猜。</li>
 * </ol>
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
    private static List<CachedContainer> viewing = List.of();

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
        List<CachedContainer> previousViewing = viewing;

        openHandler = current;
        openTicks = 0;
        viewing = List.of();

        if (previous != null && !previousViewing.isEmpty()) {
            refresh(previous, previousTicks, previousViewing);
        }

        if (current == null || current == player.playerScreenHandler) return;

        List<CachedContainer> found = findCached(client, current);
        if (found.isEmpty()) return;

        viewing = found;
        ChatUtils.debug("打开的容器在缓存里：" + describe(found) + "，关界面时会重新记一遍");
    }

    /** 关界面：把容器格子重新统计一遍写回去。 */
    private static void refresh(ScreenHandler handler, int ticks, List<CachedContainer> containers) {
        if (ticks < MIN_OPEN_TICKS) {
            ChatUtils.debug("容器界面只开了 " + ticks + " tick，数据可能还没同步，这次不刷新缓存");
            return;
        }

        Map<String, Integer> items = ContainerCacheManager.snapshot(handler);
        int total = 0;
        for (int count : items.values()) total += count;

        for (CachedContainer container : containers) {
            if (ContainerCacheManager.get().updateItems(container, items)) {
                ChatUtils.info("容器缓存已更新 #" + container.id() + " " + container.type() + " "
                    + DimensionUtils.display(container.dimension()) + " " + container.coordString()
                    + "：" + items.size() + " 种 / " + total + " 个");
            } else {
                ChatUtils.debug("关掉 #" + container.id() + "（" + container.coordString()
                    + "）时内容没有变化，不更新");
            }
        }
    }

    /** 这次打开的是哪个缓存条目（可能两个：大箱子两半都缓存过）。 */
    private static List<CachedContainer> findCached(MinecraftClient client, ScreenHandler handler) {
        ContainerCacheManager cache = ContainerCacheManager.get();
        String dimension = DimensionUtils.current();
        List<CachedContainer> found = new ArrayList<>();

        BlockPos looked = PlayerUtils.lookedAtBlock();
        boolean doubleChest = InventoryUtils.containerSlots(handler).size() == 54;

        if (looked != null) {
            CachedContainer direct = cache.at(dimension, looked);
            if (direct != null) found.add(direct);

            if (doubleChest && client.world != null) {
                for (Direction dir : ContainerTypes.HORIZONTAL) {
                    BlockPos other = looked.offset(dir);
                    CachedContainer half = cache.at(dimension, other);
                    if (half == null || found.contains(half)) continue;
                    if (!ContainerTypes.sameDoubleChest(client.world, looked, other)) continue;
                    found.add(half);
                    ChatUtils.debug("打开的容器是大箱子，" + other.toShortString()
                        + " 那一半也在缓存里（#" + half.id() + "）");
                }
            }
        }

        if (!found.isEmpty()) return found;

        // 准星没看出是哪个：附近只有一个候选缓存时就认它
        List<CachedContainer> nearby = new ArrayList<>();
        double range = PlayerUtils.reach() + 1.0;
        for (CachedContainer container : cache.all()) {
            if (!container.dimension().equals(dimension)) continue;
            if (PlayerUtils.eyeDistanceTo(container.pos()) > range) continue;
            nearby.add(container);
        }

        if (nearby.size() == 1) {
            ChatUtils.debug("准星没看出打开的是哪个容器，附近只有 #" + nearby.get(0).id() + "，就按它算");
            return nearby;
        }
        if (nearby.size() > 1) {
            ChatUtils.debug("附近有 " + nearby.size() + " 个缓存的容器，认不出打开的是哪一个，这次不刷新");
        } else {
            ChatUtils.debug("打开的容器不在缓存里（准星处和附近都没有）");
        }
        return List.of();
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
        viewing = List.of();
    }
}
