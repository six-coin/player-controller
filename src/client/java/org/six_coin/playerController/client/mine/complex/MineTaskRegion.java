package org.six_coin.playerController.client.mine.complex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code world_<n>/mine/<task>/region.json} 的结构：一个复杂挖掘任务的挖掘区 + 信标位置。
 *
 * <pre>
 * {
 *   "dimension": "minecraft:overworld",
 *   "x1": 1, "y1": 0, "z1": 1,
 *   "x2": 100, "y2": 384, "z2": 100,
 *   "beacon": [{"x": 50, "y": 0, "z": 50}, ...]
 * }
 * </pre>
 *
 * <p>刚建任务的时候文件里是空的 {@code {}}（什么都还没设）；{@code a_set_region} 之后才是上面这样。
 *
 * <p>坐标落盘前一定是 {@code x1 < x2}、{@code y1 < y2}、{@code z1 < z2}（用户输入的顺序会被换过来）。
 * {@code beacon} 里记的是<em>塔底</em>（9x9 那层）的坐标，信标方块本身在 {@code y + 4}。
 */
public final class MineTaskRegion {

    /** 挖掘区至少这么宽（|x1-x2| / |z1-z2|，方块数 = 这个值 + 1）。 */
    public static final int MIN_XZ_SPAN = 70;

    /** 挖掘区至少这么高（|y1-y2|）。 */
    public static final int MIN_Y_SPAN = 10;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private String dimension;
    private int x1;
    private int y1;
    private int z1;
    private int x2;
    private int y2;
    private int z2;
    private List<Beacon> beacon = new ArrayList<>();

    /** 信标塔底的位置。 */
    public static final class Beacon {

        private int x;
        private int y;
        private int z;

        /** 给 Gson 用。 */
        private Beacon() {
        }

        public Beacon(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** 建一个区域（min / max 必须是已经排好序的角）。 */
    public static MineTaskRegion of(String dimension, BlockPos min, BlockPos max,
                                    List<BlockPos> beacons) {
        MineTaskRegion region = new MineTaskRegion();
        region.dimension = dimension;
        region.x1 = min.getX();
        region.y1 = min.getY();
        region.z1 = min.getZ();
        region.x2 = max.getX();
        region.y2 = max.getY();
        region.z2 = max.getZ();

        List<Beacon> list = new ArrayList<>();
        for (BlockPos pos : beacons) list.add(new Beacon(pos.getX(), pos.getY(), pos.getZ()));
        region.beacon = list;
        return region;
    }

    /**
     * 读一个 region.json。
     *
     * <p>文件不存在、内容不是对象、或者还是空的 {@code {}}（没设过区域）都返回 null。
     */
    @Nullable
    public static MineTaskRegion load(Path file) {
        if (!Files.exists(file)) return null;

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            MineTaskRegion loaded = GSON.fromJson(reader, MineTaskRegion.class);
            if (loaded == null || loaded.dimension == null) return null;
            if (loaded.beacon == null) loaded.beacon = new ArrayList<>();
            return loaded;
        } catch (Exception e) {
            ChatUtils.error("读取挖掘区失败（" + file + "）：" + e.getMessage());
            return null;
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos min() {
        return new BlockPos(x1, y1, z1);
    }

    public BlockPos max() {
        return new BlockPos(x2, y2, z2);
    }

    /** 信标塔底的坐标（拷贝，改它没用）。 */
    public List<BlockPos> beacons() {
        List<BlockPos> result = new ArrayList<>();
        for (Beacon entry : beacon) result.add(entry.pos());
        return result;
    }

    /** 「主世界 [1, 0, 1] ~ [100, 384, 100]，信标 4 个」这种一行。 */
    public String describe() {
        return DimensionUtils.display(dimension) + " " + min().toShortString() + " ~ "
            + max().toShortString() + "，信标 " + beacon.size() + " 个";
    }
}
