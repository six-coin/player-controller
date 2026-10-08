package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「把某个正在打开的容器刷新进缓存」这件事，两个地方都要用：
 * <ul>
 *   <li>{@link ContainerCacheTracker}：玩家自己开关容器时刷新；</li>
 *   <li>{@code ContainerGetAction}：{@code /pc container get} 拿完东西之后刷新。</li>
 * </ul>
 *
 * <p>找出对应缓存条目的规则：先看这个位置自己；如果是大箱子，再按方块状态算出另一半在哪一格
 * （{@link ContainerTypes#otherHalf(World, BlockPos)}），那一格在缓存里就一起刷新。
 * 不做「附近有几个候选」这种猜测。
 */
public final class ContainerCacheUpdater {

    private ContainerCacheUpdater() {
    }

    /**
     * 这个位置（含大箱子算出来的另一半）在缓存里的条目。
     *
     * @return 可能为空；最多两个（大箱子两半都缓存过）
     */
    public static List<CachedContainer> findEntries(World world, BlockPos pos) {
        ContainerCacheManager cache = ContainerCacheManager.get();
        String dimension = DimensionUtils.current();
        List<CachedContainer> found = new ArrayList<>();

        CachedContainer direct = cache.at(dimension, pos);
        if (direct != null) found.add(direct);

        BlockPos other = world == null ? null : ContainerTypes.otherHalf(world, pos);
        if (other != null) {
            CachedContainer half = cache.at(dimension, other);
            if (half != null && !found.contains(half)) {
                found.add(half);
                ChatUtils.debug("大箱子：另一半 " + other.toShortString()
                    + " 也在缓存里（#" + half.id() + "）");
            }
        }
        return found;
    }

    /**
     * 把这次打开的容器对应的缓存刷新一遍（内容没变化就不写文件，只打 debug）。
     *
     * @return 刷了几个条目的内容（内容没变的不算）
     */
    public static int refresh(MinecraftClient client, BlockPos pos, ScreenHandler handler) {
        if (client == null || client.world == null || pos == null || handler == null) return 0;

        // 创造模式物品栏不是真容器，别理它
        if (ContainerCacheTracker.isCreativeInventory(client)) {
            ChatUtils.debug("现在开着创造模式物品栏，不刷新容器缓存");
            return 0;
        }

        List<CachedContainer> entries = findEntries(client.world, pos);
        if (entries.isEmpty()) {
            ChatUtils.debug("容器的位置 " + pos.toShortString() + "（及大箱子另一半）不在缓存里，不用刷新");
            return 0;
        }

        Map<String, Integer> items = ContainerCacheManager.snapshot(handler);
        int total = 0;
        for (int count : items.values()) total += count;

        int changed = 0;
        for (CachedContainer container : entries) {
            if (ContainerCacheManager.get().updateItems(container, items)) {
                changed++;
                ChatUtils.debug("容器缓存已更新 #" + container.id() + " " + container.type() + " "
                    + DimensionUtils.display(container.dimension()) + " " + container.coordString()
                    + "：" + items.size() + " 种 / " + total + " 个");
            } else {
                ChatUtils.debug("刷新 #" + container.id() + "（" + container.coordString()
                    + "）时内容没有变化，不更新");
            }
        }
        return changed;
    }
}
