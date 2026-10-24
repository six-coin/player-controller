package org.six_coin.playerController.client.mine.complex;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 给一个挖掘区算信标塔底要放在哪。
 *
 * <p>已知信标的作用域：以自己为中心，水平半径 50（含自己那一格，所以是 99x99 的正方形，
 * 也就是 {@code 中心-49 ~ 中心+49}），向下延伸 50 格、向上到世界高度。
 * 所以：
 * <ul>
 *   <li><b>纵向</b>：信标一律放在挖掘区最底下那一层（{@code min.y}），这样从上到下都罩得住；</li>
 *   <li><b>水平</b>：把挖掘区的 x、z 两个方向分别用 99 格的正方形铺满。相邻信标中心隔 98 格
 *       （两块刚好贴边、不重不漏），第一块从 {@code min + 49} 开始；</li>
 *   <li><b>贴边</b>：最后一排 / 一列如果让 9x9 的塔底超出挖掘区，就把它往回挪到
 *       {@code max - 4}（塔底半径 4），保证塔底一定落在区域里面。</li>
 * </ul>
 *
 * <p>例子：{@code 1 0 1 ~ 99 384 99} → 只要 {@code (50, 0, 50)} 一个；
 * {@code 1 0 1 ~ 100 384 100} → {@code (50,0,50)、(96,0,50)、(50,0,96)、(96,0,96)} 四个
 * （x = 96 就是 {@code 100 - 4}，塔底 92~100 正好贴着边）。
 *
 * <p>这里记的是<em>塔底</em>（9x9 铁块那层）的坐标；信标方块本身在 {@code y + }{@value #TOWER_HEIGHT}。
 */
public final class MineBeaconPlanner {

    /** 覆盖半径：中心 ± 49 = 99 格宽。 */
    public static final int COVER_RADIUS = 49;

    /** 相邻两个信标中心的间距（99 格宽、贴边不重叠，所以是 98）。 */
    public static final int SPACING = 98;

    /** 塔底（9x9 那层）的半径。 */
    public static final int BASE_RADIUS = 4;

    /** 塔底到信标方块之间隔几层（9x9 → 7x7 → 5x5 → 3x3，然后才是信标）。 */
    public static final int TOWER_HEIGHT = 4;

    private MineBeaconPlanner() {
    }

    /** 整个挖掘区要放的信标塔底坐标（先按 z、再按 x 排）。 */
    public static List<BlockPos> plan(BlockPos min, BlockPos max) {
        return plan(min.getX(), min.getY(), min.getZ(), max.getX(), max.getZ());
    }

    public static List<BlockPos> plan(int minX, int baseY, int minZ, int maxX, int maxZ) {
        List<Integer> xs = centers(minX, maxX);
        List<Integer> zs = centers(minZ, maxZ);

        List<BlockPos> result = new ArrayList<>(xs.size() * zs.size());
        for (int z : zs) {
            for (int x : xs) {
                result.add(new BlockPos(x, baseY, z));
            }
        }
        return result;
    }

    /**
     * 一个方向上的信标中心：从 {@code min + 49} 起每 98 格一个，直到盖住 {@code max}；
     * 最后一个如果塔底会超出区域，就拉回到 {@code max - 4}。
     */
    static List<Integer> centers(int min, int max) {
        List<Integer> centers = new ArrayList<>();

        int center = min + COVER_RADIUS;
        while (true) {
            centers.add(center);
            if (center + COVER_RADIUS >= max) break;

            // 再往右一个就够不着了：先按 98 格铺，但别让塔底超出区域右边
            int next = Math.min(center + SPACING, max - BASE_RADIUS);
            if (next <= center) break;      // 区域太窄（有 >= 70 的限制，正常不会走到这里）
            center = next;
        }
        return centers;
    }
}
