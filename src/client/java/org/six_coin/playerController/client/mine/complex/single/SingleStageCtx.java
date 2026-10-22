package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.mine.MineFilter;
import org.six_coin.playerController.client.mine.MineRegion;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code /pc mine debug_chunk_miner} 一次的共享上下文：坐标、物品栏布局、数据缓存、各种区域。
 *
 * <p><b>坐标系</b>：所有「相对坐标」都相对**初始子区块**（玩家所在的那个 16×16×16），
 * 和需求里写的一样（0 0 0 ~ 15 15 15）。目标子区块是初始子区块正下方那个。
 *
 * <p><b>物品栏下标</b>：全部用 {@code PlayerInventory} 的下标（快捷栏 0~8、主背包 9~35、副手 40），
 * 需求里的「快捷栏 N / 背包 N」都在这里换算好了，别在别处再写一遍数字。
 */
public final class SingleStageCtx {

    // ------------------------------------------------------------------
    // 物品栏布局（PlayerInventory 下标）
    // ------------------------------------------------------------------

    /** 快捷栏 1：剑。 */
    public static final int HOTBAR_SWORD = 0;
    /** 快捷栏 2：镐。 */
    public static final int HOTBAR_PICKAXE = 1;
    /** 快捷栏 3：斧。 */
    public static final int HOTBAR_AXE = 2;
    /** 快捷栏 4：锹。 */
    public static final int HOTBAR_SHOVEL = 3;
    /** 快捷栏 5：锄。 */
    public static final int HOTBAR_HOE = 4;
    /** 快捷栏 6~8：必须是空位（临时放盒子的地方）。 */
    public static final int HOTBAR_FREE_1 = 5;
    public static final int HOTBAR_FREE_2 = 6;
    public static final int HOTBAR_FREE_3 = 7;
    /** 快捷栏 9：食物（熟猪排）。 */
    public static final int HOTBAR_FOOD = 8;

    /** 背包 1~5：替换用的剑 / 镐 / 斧 / 铲 / 锄（耐久必须满）。 */
    public static final int SPARE_START = 9;
    /** 背包 6：只有圆石的潜影盒（可以不装满）。 */
    public static final int COBBLE_BOX_A = 14;
    /** 背包 7：同上。 */
    public static final int COBBLE_BOX_B = 15;
    /** 背包 8：装满圆石的潜影盒（1728）。 */
    public static final int COBBLE_BOX_FULL = 16;
    /** 背包 9：只有黏液块 / 树脂块的潜影盒（可以不装满）。 */
    public static final int SLIME_BOX_A = 17;
    /** 背包 10：同上。 */
    public static final int SLIME_BOX_B = 18;
    /** 背包 11：黏液块 / 树脂块 ≥ 1600 的潜影盒。 */
    public static final int SLIME_BOX_FULL = 19;
    /** 背包 12：替换不死图腾。 */
    public static final int SPARE_TOTEM = 20;
    /** 背包 13~21：收集用的潜影盒（13~17 有装满程度的顺序要求，18~21 必须全空）。 */
    public static final int COLLECT_START = 21;
    public static final int COLLECT_END = 29;
    /** 背包 22~27：随便。 */
    public static final int FILLER_START = 30;
    public static final int FILLER_END = 35;

    /** 副手：不死图腾。 */
    public static final int OFFHAND = 40;

    /** 初始子区块里，放置位置用的相对坐标（x, y, z）。 */
    private static final int[][] PLATFORM_XZ = {{6, 6}, {6, 7}, {6, 8}, {6, 9}, {6, 10},
        {7, 6}, {7, 7}, {7, 8}, {7, 9}, {7, 10},
        {8, 6}, {8, 7}, {8, 8}, {8, 9}, {8, 10},
        {9, 6}, {9, 7}, {9, 8}, {9, 9}, {9, 10}};

    /** 走位的 8 个拐点（相对目标子区块的 x / z）。 */
    private static final int[][] ROUTE = {{14, 14}, {14, 1}, {10, 1}, {10, 14},
        {5, 14}, {5, 1}, {1, 1}, {1, 14}};

    /** 每个子区块要挖 8 轮，每轮 2 层。 */
    public static final int ITERATIONS = 8;

    // ------------------------------------------------------------------

    public final String dimension;
    /** 初始子区块（玩家所在）的最小角。 */
    public final BlockPos initialMin;
    /** 目标子区块（初始子区块正下方）的最小角。 */
    public final BlockPos targetMin;
    /** 本次用的方块过滤器（和 mine simple 是同一份快照）。 */
    public final MineFilter filter;
    /** item_collector 里那些物品的 id。 */
    public final Set<String> collectorItems;
    /** 子区块数据缓存（物品栏 + 六个盒子）。 */
    public final SingleStageData data;

    /** 当前空收集潜影盒位（{@link #COLLECT_START} 起，每换一次 +1）。 */
    public int collectSlot = COLLECT_START;

    /**
     * 已经放到平台上的那几个盒子原来是物品栏的哪几格（圆石 + 黏液块）。
     *
     * <p>卸货时要判断「理应是空位的地方有没有东西」，这几格就是其中一部分；
     * 收集盒那两格另外按 {@link #collectSlot} 现算（会换）。
     */
    public final List<Integer> placedBoxSlots = new ArrayList<>();

    public SingleStageCtx(String dimension, BlockPos initialMin, MineFilter filter,
                          Set<String> collectorItems, SingleStageData data) {
        this.dimension = dimension;
        this.initialMin = initialMin.toImmutable();
        this.targetMin = initialMin.down(16);
        this.filter = filter;
        this.collectorItems = Set.copyOf(collectorItems);
        this.data = data;
    }

    // ------------------------------------------------------------------
    // 初始子区块里的固定位置
    // ------------------------------------------------------------------

    /** 临时站立点（6 4 6）。 */
    public BlockPos standPoint() {
        return initialMin.add(6, 4, 6);
    }

    /** 单独放的那个圆石（4 5 4）。 */
    public BlockPos marker() {
        return initialMin.add(4, 5, 4);
    }

    public BlockPos slimeSpotA() {
        return initialMin.add(7, 3, 7);
    }

    public BlockPos slimeSpotB() {
        return initialMin.add(7, 3, 8);
    }

    public BlockPos cobbleSpotA() {
        return initialMin.add(8, 3, 7);
    }

    public BlockPos cobbleSpotB() {
        return initialMin.add(8, 3, 8);
    }

    public BlockPos collectSpotA() {
        return initialMin.add(9, 3, 7);
    }

    public BlockPos collectSpotB() {
        return initialMin.add(9, 3, 8);
    }

    /** 4×5 的圆石平台（20 格，相对坐标 6 2 6 ~ 9 2 10）。 */
    public List<BlockPos> platform() {
        List<BlockPos> cells = new ArrayList<>(PLATFORM_XZ.length);
        for (int[] xz : PLATFORM_XZ) {
            cells.add(initialMin.add(xz[0], 2, xz[1]));
        }
        return cells;
    }

    /**
     * 搭平台时悬停的位置（相对坐标 8 4 8，平台正上方那一格空气）。
     *
     * <p>从临时站立点 6 4 6 看，平台最远那个角（9 2 10）离眼睛差不多 6 格，
     * 超过服务端允许的交互距离（{@code actions.reach} + 1 = 5.5），放不上去；
     * 站到平台正上方就全都在 4.5 格以内了。
     */
    public BlockPos platformHover() {
        return initialMin.add(8, 4, 8);
    }

    /** 六个盒子的位置（顺序：黏液块 ×2、圆石 ×2、收集 ×2）。 */
    public List<BlockPos> boxSpots() {
        return List.of(slimeSpotA(), slimeSpotB(), cobbleSpotA(), cobbleSpotB(),
            collectSpotA(), collectSpotB());
    }

    /** 这个坐标是不是我们放的某个盒子。 */
    public boolean isBoxSpot(BlockPos pos) {
        return boxSpots().contains(pos);
    }

    /** 收集盒 A / B 各自对应物品栏的哪一格。 */
    public int collectSlotA() {
        return collectSlot;
    }

    public int collectSlotB() {
        return collectSlot + 1;
    }

    // ------------------------------------------------------------------
    // 目标区域
    // ------------------------------------------------------------------

    /** 第 {@code iteration}（0 开始）轮的目标区域最下面那层。 */
    public int regionBottom(int iteration) {
        return targetMin.getY() + 14 - 2 * iteration;
    }

    public int regionTop(int iteration) {
        return regionBottom(iteration) + 1;
    }

    /** 目标区域上面相邻那一层（走位 / 放粘液块用的那层）。 */
    public int walkLayer(int iteration) {
        return regionTop(iteration) + 1;
    }

    public MineRegion region(int iteration) {
        return MineRegion.of(dimension,
            new BlockPos(targetMin.getX(), regionBottom(iteration), targetMin.getZ()),
            new BlockPos(targetMin.getX() + 15, regionTop(iteration), targetMin.getZ() + 15),
            filter);
    }

    /** 目标区域里的所有方块。 */
    public List<BlockPos> regionCells(int iteration) {
        List<BlockPos> cells = new ArrayList<>(16 * 2 * 16);
        int bottom = regionBottom(iteration);
        for (int y = bottom; y <= bottom + 1; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    cells.add(new BlockPos(targetMin.getX() + x, y, targetMin.getZ() + z));
                }
            }
        }
        return cells;
    }

    /**
     * 目标区域外面那一圈「壳」：东西南北 4 面 16×2 的墙 + 下面 16×16 的底，都是 1 格厚。
     *
     * <p>就是需求里的「边缘目标区域」：包裹住目标区域的那一层，往这里补粘液块就不会漏
     * （水 / 熔岩 / 空气都是漏洞）。上面那一层不算 —— 那是我们自己走位的地方。
     */
    public List<BlockPos> shellCells(int iteration) {
        List<BlockPos> cells = new ArrayList<>();
        int minX = targetMin.getX();
        int minZ = targetMin.getZ();
        int bottom = regionBottom(iteration);
        int top = regionTop(iteration);

        for (int y = bottom; y <= top; y++) {
            for (int i = 0; i < 16; i++) {
                cells.add(new BlockPos(minX - 1, y, minZ + i));      // 西
                cells.add(new BlockPos(minX + 16, y, minZ + i));     // 东
                cells.add(new BlockPos(minX + i, y, minZ - 1));      // 北
                cells.add(new BlockPos(minX + i, y, minZ + 16));     // 南
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.add(new BlockPos(minX + x, bottom - 1, minZ + z));   // 底
            }
        }
        return cells;
    }

    /** 全量目标区域 = 目标区域 + 那一圈壳。 */
    public List<BlockPos> wrapCells(int iteration) {
        List<BlockPos> cells = new ArrayList<>(regionCells(iteration));
        cells.addAll(shellCells(iteration));
        return cells;
    }

    // ------------------------------------------------------------------
    // 走位路线
    // ------------------------------------------------------------------

    /**
     * 第 {@code y} 层上的整条路线，**一格一格**排好
     * （14 14 → 14 1 → 10 1 → 10 14 → 5 14 → 5 1 → 1 1 → 1 14）。
     */
    public List<BlockPos> routeCells(int y) {
        List<BlockPos> cells = new ArrayList<>();
        int x = ROUTE[0][0];
        int z = ROUTE[0][1];
        cells.add(new BlockPos(targetMin.getX() + x, y, targetMin.getZ() + z));

        for (int i = 1; i < ROUTE.length; i++) {
            int targetX = ROUTE[i][0];
            int targetZ = ROUTE[i][1];
            while (x != targetX) {
                x += Integer.signum(targetX - x);
                cells.add(new BlockPos(targetMin.getX() + x, y, targetMin.getZ() + z));
            }
            while (z != targetZ) {
                z += Integer.signum(targetZ - z);
                cells.add(new BlockPos(targetMin.getX() + x, y, targetMin.getZ() + z));
            }
        }
        return cells;
    }

    /** 某一层上相对坐标 {@code (x, z)} 的那一格。 */
    public BlockPos routePoint(int y, int x, int z) {
        return new BlockPos(targetMin.getX() + x, y, targetMin.getZ() + z);
    }

    // ------------------------------------------------------------------

    /** 「主世界 [x, y, z]」这种一行。 */
    public String describe() {
        return DimensionUtils.display(dimension) + " 初始子区块 " + initialMin.toShortString()
            + "，目标子区块 " + targetMin.toShortString();
    }

    // ------------------------------------------------------------------
    // 数据缓存
    // ------------------------------------------------------------------

    /** 重新拍一张物品栏快照（每次要判断之前调）。 */
    public void refresh() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player != null) data.refreshInventory(player.getInventory());
    }

    /** 物品栏里这一格（实时读，不走快照）。 */
    public ItemStack live(int index) {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) return ItemStack.EMPTY;
        return player.getInventory().getStack(index);
    }

    /**
     * 「理应是空位」的那些格子：快捷栏 6~8、背包 22~27、放了盒子出去的那几格、当前两个收集盒的格子。
     *
     * <p>卸货的时候这些格子里的东西会被扔掉 —— 它们本来就不该有东西。
     */
    public List<Integer> expectedEmptySlots() {
        List<Integer> slots = new ArrayList<>();
        slots.add(HOTBAR_FREE_1);
        slots.add(HOTBAR_FREE_2);
        slots.add(HOTBAR_FREE_3);
        for (int i = FILLER_START; i <= FILLER_END; i++) slots.add(i);
        for (int slot : placedBoxSlots) {
            if (!slots.contains(slot)) slots.add(slot);
        }
        if (!slots.contains(collectSlot)) slots.add(collectSlot);
        if (!slots.contains(collectSlot + 1)) slots.add(collectSlot + 1);
        return slots;
    }
}
