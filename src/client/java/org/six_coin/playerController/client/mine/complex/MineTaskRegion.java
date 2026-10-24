package org.six_coin.playerController.client.mine.complex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
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
 * {@code world_<n>/mine/<task>/region.json} 的结构：一个复杂挖掘任务的挖掘区。
 *
 * <pre>
 * {
 *   "dimension": "minecraft:overworld",
 *   "x1": 0, "y1": -59, "z1": 0,
 *   "x2": 271, "y2": 106, "z2": 271,
 *   "inspected": false
 * }
 * </pre>
 *
 * <p>刚建任务的时候文件里是空的 {@code {}}（什么都还没设）；{@code a_set_region} 之后才是上面这样。
 *
 * <p><b>范围原样记下</b>：{@code a_set_region} 不再做任何「机器校准」（以前会把两个角排序、算信标），
 * 只按 {@link #validate} 检查用户写进来的两个角，过了就照抄进文件。所以
 * {@code x1 < x2}、{@code y1 < y2}、{@code z1 < z2} 是硬要求，不会帮你换过来。
 *
 * <p>几个数是挖矿机自己要用到的（都是 16 / 2 的整数倍，好让机器正好对齐子区块）：
 * <ul>
 *   <li>{@code y1} 是偶数、{@code y2 + 1} 是 16 的倍数：挖的是整层子区块；</li>
 *   <li>{@code x1}、{@code z1} 是 16 的倍数，{@code x2 + 1}、{@code z2 + 1} 是 16 的倍数：
 *       正好盖住整数个区块；</li>
 *   <li>{@code y2 - y1 >= 16}（至少一层子区块）、{@code y2 + 16 <= 建筑高度限制}
 *       （机器站在区域顶上，还要留 16 格）。</li>
 * </ul>
 *
 * <p>{@code inspected} 是 {@code /pc mine task <task_name> b_inspect_region} 写进去的：检查通过才是 {@code true}，
 * 每次 {@code a_set_region} 都会重置成 {@code false}。
 *
 * <p>{@code checked} 是 {@code c_station_check}（跑一遍 {@code /pc mine station check}）通过之后
 * 才写进去的 {@code true}；没通过这个字段直接不写（{@code null} 时 Gson 会省略）。
 */
public final class MineTaskRegion {

    /** 挖掘区最少要有一层子区块高。 */
    public static final int MIN_Y_SPAN = 16;

    /** 区域顶上要留出来的高度（机器自己占的地方）。 */
    public static final int HEAD_ROOM = 16;

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
    private boolean inspected;

    /**
     * {@code c_station_check} 通过之后才有值：{@code true} = 挖掘站检查通过。
     *
     * <p>用 {@link Boolean} 而不是 {@code boolean}：没过就是 {@code null}，Gson 不会写出这个字段
     * —— 需求要的是「通过才加 {@code "checked": true}，否则不加」。
     */
    private Boolean checked;

    /** 建一个区域（两个角必须已经满足 {@link #validate}；{@code inspected} 从 false 开始）。 */
    public static MineTaskRegion of(String dimension,
                                    int x1, int y1, int z1,
                                    int x2, int y2, int z2) {
        MineTaskRegion region = new MineTaskRegion();
        region.dimension = dimension;
        region.x1 = x1;
        region.y1 = y1;
        region.z1 = z1;
        region.x2 = x2;
        region.y2 = y2;
        region.z2 = z2;
        region.inspected = false;
        return region;
    }

    /**
     * 检查一组角合不合法。
     *
     * @param maxY 这个维度的建筑高度限制（最高能放方块的 y，见
     *             {@link DimensionUtils#buildMaxY(String)}）
     * @return 问题列表；空表示通过
     */
    public static List<String> validate(String dimension,
                                        int x1, int y1, int z1,
                                        int x2, int y2, int z2, int maxY) {
        List<String> problems = new ArrayList<>();

        // 1) 两个角必须是「小 -> 大」，而且不能是同一个面
        if (x1 >= x2) problems.add("x1 必须小于 x2（现在是 " + x1 + " / " + x2 + "）");
        if (y1 >= y2) problems.add("y1 必须小于 y2（现在是 " + y1 + " / " + y2 + "）");
        if (z1 >= z2) problems.add("z1 必须小于 z2（现在是 " + z1 + " / " + z2 + "）");

        // 2) y1 是偶数、y2 + 1 是 16 的倍数（挖的是整层子区块）
        if (Math.floorMod(y1, 2) != 0) {
            problems.add("y1 必须是偶数（现在是 " + y1 + "）");
        }
        if (Math.floorMod(y2 + 1, 16) != 0) {
            problems.add("y2 + 1 必须是 16 的倍数（现在是 " + y2 + "，y2+1 = " + (y2 + 1) + "）");
        }

        // 3) 至少一层子区块高
        if (y2 - y1 < MIN_Y_SPAN) {
            problems.add("y2 - y1 至少要 " + MIN_Y_SPAN + "（现在是 " + (y2 - y1) + "）");
        }

        // 4) 顶上还要留出机器的高度
        if (y2 + HEAD_ROOM > maxY) {
            problems.add("y2 + " + HEAD_ROOM + " 不能超过建筑高度限制 " + maxY
                + "（现在是 y2 = " + y2 + "，y2+" + HEAD_ROOM + " = " + (y2 + HEAD_ROOM) + "）");
        }

        // 5) x / z 必须正好落在区块边界上
        if (Math.floorMod(x1, 16) != 0) {
            problems.add("x1 必须是 16 的倍数（现在是 " + x1 + "）");
        }
        if (Math.floorMod(z1, 16) != 0) {
            problems.add("z1 必须是 16 的倍数（现在是 " + z1 + "）");
        }
        if (Math.floorMod(x2 + 1, 16) != 0) {
            problems.add("x2 + 1 必须是 16 的倍数（现在是 " + x2 + "，x2+1 = " + (x2 + 1) + "）");
        }
        if (Math.floorMod(z2 + 1, 16) != 0) {
            problems.add("z2 + 1 必须是 16 的倍数（现在是 " + z2 + "，z2+1 = " + (z2 + 1) + "）");
        }

        return problems;
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

    /** 区域顶上的那个角 + 1（机器站位 / {@code b_inspect_region} 检查的入口）。 */
    public BlockPos headPos() {
        return new BlockPos(x1, y2 + 1, z1);
    }

    /** 检查通过了没有（{@code b_inspect_region} 写的）。 */
    public boolean inspected() {
        return inspected;
    }

    public void setInspected(boolean value) {
        this.inspected = value;
    }

    /** 挖掘站检查通过了没有（{@code c_station_check} 写的；没过就是 false，文件里也没有这个字段）。 */
    public boolean checked() {
        return checked != null && checked;
    }

    /** {@code true} = 写 {@code "checked": true}；{@code false} = 把这个字段去掉。 */
    public void setChecked(boolean value) {
        this.checked = value ? Boolean.TRUE : null;
    }

    /** 这个方块在不在框里（端点算在里面）。 */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= x1 && pos.getX() <= x2
            && pos.getY() >= y1 && pos.getY() <= y2
            && pos.getZ() >= z1 && pos.getZ() <= z2;
    }

    /** 框里一共多少格（含端点）。 */
    public long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    /** 盖住几个区块（16×16 那种）。 */
    public int chunkCount() {
        return ((x2 >> 4) - (x1 >> 4) + 1) * ((z2 >> 4) - (z1 >> 4) + 1);
    }

    /** 「主世界 [0, -59, 0] ~ [271, 106, 271]，17×17 区块，未检查」这种一行。 */
    public String describe() {
        return DimensionUtils.display(dimension) + " " + min().toShortString() + " ~ "
            + max().toShortString() + "，" + chunkCount() + " 个区块，"
            + (inspected ? "§a已检查" : "§c未检查")
            + (checked() ? "§r，§a挖掘站已检查" : "");
    }
}
