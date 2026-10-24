package org.six_coin.playerController.client.mine.complex;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mine.MineFilter;
import org.six_coin.playerController.client.mine.MineRegion;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code world_<n>/mine/mine.json} 的结构。
 *
 * <pre>
 * {
 *   "complex": ["test_task"],
 *   "simple": {
 *     "dimension": "minecraft:overworld",
 *     "x1": 1, "y1": 0, "z1": 1,
 *     "x2": 100, "y2": 384, "z2": 100
 *   }
 * }
 * </pre>
 *
 * <p>字段名（snake_case）就是文件里的键名 —— Gson 直接按字段名读写，所以别改名。
 * {@code simple} 是 {@code /pc mine simple} 的缓存：写进去之后重新进世界也还在（见
 * {@code MineManager#resumeSaved()}）；没有任务的时候是 null，Gson 就不会写出来。
 */
public final class MineComplexData {

    private List<String> complex = new ArrayList<>();
    private SimpleRegion simple;

    List<String> complex() {
        if (complex == null) complex = new ArrayList<>();
        return complex;
    }

    /** simple 的缓存；没有就是 null。 */
    @Nullable
    public SimpleRegion simple() {
        return simple;
    }

    public void simple(@Nullable SimpleRegion value) {
        this.simple = value;
    }

    /** simple 任务的范围（只有维度 + 两个角，过滤器进世界时按当前列表重新取）。 */
    public static final class SimpleRegion {

        private String dimension;
        private int x1;
        private int y1;
        private int z1;
        private int x2;
        private int y2;
        private int z2;

        public static SimpleRegion of(MineRegion region) {
            SimpleRegion value = new SimpleRegion();
            value.dimension = region.dimension();
            value.x1 = region.min().getX();
            value.y1 = region.min().getY();
            value.z1 = region.min().getZ();
            value.x2 = region.max().getX();
            value.y2 = region.max().getY();
            value.z2 = region.max().getZ();
            return value;
        }

        @Nullable
        public String dimension() {
            return dimension;
        }

        public BlockPos min() {
            return new BlockPos(x1, y1, z1);
        }

        public BlockPos max() {
            return new BlockPos(x2, y2, z2);
        }

        /** 还原成能直接跑的任务（过滤器用当前生效的那张表）。 */
        public MineRegion toRegion(MineFilter filter) {
            return MineRegion.of(dimension, min(), max(), filter);
        }
    }
}
