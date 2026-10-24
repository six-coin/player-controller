package org.six_coin.playerController.client.container;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationPos;
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
 * （{@link ContainerTypes#otherHalf(Level, BlockPos)}），那一格在缓存里就一起刷新。
 * 不做「附近有几个候选」这种猜测。
 *
 * <p><b>工作站里的容器永远不记</b>（见 {@link #refresh}）：{@code item_storage}、{@code item_final}、
 * 潜影盒摆放处、任务前暂存处、空潜影盒提供处这些是备货自己的仓库，内容由 check.json / station_data 管。
 */
public final class ContainerCacheUpdater {

    private ContainerCacheUpdater() {
    }

    /**
     * 这个位置（含大箱子算出来的另一半）在缓存里的条目。
     *
     * @return 可能为空；最多两个（大箱子两半都缓存过）
     */
    public static List<CachedContainer> findEntries(Level level, BlockPos pos) {
        ContainerCacheManager cache = ContainerCacheManager.get();
        String dimension = DimensionUtils.current();
        List<CachedContainer> found = new ArrayList<>();

        CachedContainer direct = cache.at(dimension, pos);
        if (direct != null) found.add(direct);

        BlockPos other = level == null ? null : ContainerTypes.otherHalf(level, pos);
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
     * <p><b>工作站里的容器不记</b>：{@code item_storage} / {@code item_final} / 潜影盒摆放处 /
     * 任务前暂存处 / 空潜影盒提供处这些都在工作站里，按约定它们<b>永远不进容器缓存</b>
     * （缓存只用来记「外面的箱子」里有什么，备货会用 check.json / station_data 管工作站的东西）。
     *
     * @return 刷了几个条目的内容（内容没变的不算）
     */
    public static int refresh(Minecraft client, BlockPos pos, AbstractContainerMenu handler) {
        if (client == null || client.level == null || pos == null || handler == null) return 0;

        // 创造模式物品栏不是真容器，别理它
        if (ContainerCacheTracker.isCreativeInventory(client)) {
            ChatUtils.debug("现在开着创造模式物品栏，不刷新容器缓存");
            return 0;
        }

        // 工作站里的容器一律不写缓存
        if (isStationContainer(client.level, pos)) {
            ChatUtils.debug("容器 " + pos.toShortString()
                + " 是工作站的容器，按约定不进容器缓存，跳过刷新");
            return 0;
        }

        List<CachedContainer> entries = findEntries(client.level, pos);
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

    /**
     * 这个位置是不是工作站里配置过的容器（大箱子的另一半也算）。
     *
     * <p>只看当前维度里配过的坐标；一个都没配就直接 false。
     */
    private static boolean isStationContainer(Level level, BlockPos pos) {
        String dimension = DimensionUtils.current();
        List<BlockPos> station = new ArrayList<>();
        for (StationPos stationPos : StationManager.get().allPositions()) {
            if (!stationPos.dimension().equals(dimension)) continue;
            station.add(stationPos.pos());
        }
        if (station.isEmpty()) return false;
        if (station.contains(pos)) return true;

        BlockPos other = level == null ? null : ContainerTypes.otherHalf(level, pos);
        return other != null && station.contains(other);
    }
}
