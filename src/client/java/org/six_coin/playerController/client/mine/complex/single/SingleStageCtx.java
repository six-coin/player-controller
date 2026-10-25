package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mine.MineFilter;
import org.six_coin.playerController.client.mine.MineRegion;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

    /**
     * 背包 1~5：替换用的剑 / 镐 / 镐 / 铲 / 镐（耐久必须满）。
     *
     * <p>镐子用得最多，所以备<b>三把</b>：快捷栏那把掉到 100 耐久以下就换下一把，
     * 换下来的坏镐子就占着原来那一格（补货时按格换新）。
     */
    public static final int SPARE_START = 9;
    /** 背包 1：备用剑。 */
    public static final int SPARE_SWORD = SPARE_START;
    /** 背包 2：备用镐子（第一把）。 */
    public static final int SPARE_PICKAXE_1 = SPARE_START + 1;
    /** 背包 3：备用镐子（第二把）。 */
    public static final int SPARE_PICKAXE_2 = SPARE_START + 2;
    /** 背包 4：备用铲子。 */
    public static final int SPARE_SHOVEL = SPARE_START + 3;
    /** 背包 5：备用镐子（第三把）。 */
    public static final int SPARE_PICKAXE_3 = SPARE_START + 4;
    /** 三把备用镐子的格子（换的时候挑剩得最多的那一把）。 */
    public static final int[] SPARE_PICKAXES = {SPARE_PICKAXE_1, SPARE_PICKAXE_2, SPARE_PICKAXE_3};
    /** 背包 6：**装满**圆石的潜影盒（1728）。 */
    public static final int COBBLE_BOX_A = 14;
    /** 背包 7：圆石盒，空 / 半满 / 满都行（内容不能比背包 8 少）。 */
    public static final int COBBLE_BOX_B = 15;
    /** 背包 8：圆石盒，同上（内容不能比背包 7 多）。 */
    public static final int COBBLE_BOX_FULL = 16;
    /** 背包 9：**装满**黏液块 / 树脂块的潜影盒（≥1600）。 */
    public static final int SLIME_BOX_A = 17;
    /** 背包 10：黏液块盒，空 / 半满 / 满都行（内容不能比背包 11 少）。 */
    public static final int SLIME_BOX_B = 18;
    /** 背包 11：黏液块盒，同上（内容不能比背包 10 多）。 */
    public static final int SLIME_BOX_FULL = 19;
    /** 背包 12：熔岩桶（拆平台前拿去烧垃圾，用完归位）。 */
    public static final int LAVA_BUCKET = 20;
    /** 背包 13~21：收集用的潜影盒（13~17 有装满程度的顺序要求，18~21 必须全空）。 */
    public static final int COLLECT_START = 21;
    public static final int COLLECT_END = 29;
    /** 背包 22~27：随便。 */
    public static final int FILLER_START = 30;
    public static final int FILLER_END = 35;

    /** 副手：不死图腾。 */
    public static final int OFFHAND = 40;

    /**
     * 平台 20 格的相对坐标（x 6~10、z 6~9，y = 2）。
     *
     * <p>顺序是「先近后远」，看着顺眼而已 —— 凭空放置不看相邻方块，先后无所谓。
     */
    private static final int[][] PLATFORM_XZ = {
        {6, 6}, {6, 7}, {6, 8}, {6, 9},
        {7, 6}, {7, 7}, {7, 8}, {7, 9},
        {8, 6}, {8, 7}, {8, 8}, {8, 9},
        {9, 6}, {9, 7}, {9, 8}, {9, 9},
        {10, 6}, {10, 7}, {10, 8}, {10, 9}};

    /** 走位的 8 个拐点（相对目标子区块的 x / z）。 */
    private static final int[][] ROUTE = {{14, 14}, {14, 1}, {10, 1}, {10, 14},
        {5, 14}, {5, 1}, {1, 1}, {1, 14}};

    // ------------------------------------------------------------------

    public final String dimension;
    /** 初始子区块（玩家所在）的最小角。 */
    public final BlockPos initialMin;
    /** 目标子区块（初始子区块正下方）的最小角。 */
    public final BlockPos targetMin;
    /** 本次用的方块过滤器（和 mine simple 是同一份快照）。 */
    public final MineFilter filter;
    /** {@code detail}：收集清单、五个方向怎么填、挖几层。 */
    public final SingleStageDetail detail;
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
                          SingleStageDetail detail, SingleStageData data) {
        this.dimension = dimension;
        this.initialMin = initialMin.immutable();
        this.targetMin = initialMin.below(16);
        this.filter = filter;
        this.detail = detail;
        this.collectorItems = Set.copyOf(detail.itemCollectorList());
        this.data = data;
    }

    /** 这次要挖几层（一个 level = 2 格）。 */
    public int levels() {
        return detail.levels();
    }

    /** 最底层（最后一轮）的序号。 */
    public int lastIteration() {
        return detail.levels() - 1;
    }

    // ------------------------------------------------------------------
    // 初始子区块里的固定位置
    // ------------------------------------------------------------------

    /** 临时站立点（6 4 6）。 */
    public BlockPos standPoint() {
        return initialMin.offset(6, 4, 6);
    }

    /** 单独放的那个圆石（4 5 4）。 */
    public BlockPos marker() {
        return initialMin.offset(4, 5, 4);
    }

    /** 倒熔岩时站的位置（6 6 6）。 */
    public BlockPos lavaStandPoint() {
        return initialMin.offset(6, 6, 6);
    }

    /** 倒熔岩时给服务端的 yaw（135°，朝 -x / -z 那一边）。 */
    public static final float LAVA_YAW = 135.0f;

    /** 倒熔岩时给服务端的 pitch（30°，往下 30°）。 */
    public static final float LAVA_PITCH = 30.0f;

    public BlockPos slimeSpotA() {
        return initialMin.offset(7, 3, 7);
    }

    public BlockPos slimeSpotB() {
        return initialMin.offset(7, 3, 8);
    }

    public BlockPos cobbleSpotA() {
        return initialMin.offset(8, 3, 7);
    }

    public BlockPos cobbleSpotB() {
        return initialMin.offset(8, 3, 8);
    }

    public BlockPos collectSpotA() {
        return initialMin.offset(9, 3, 7);
    }

    public BlockPos collectSpotB() {
        return initialMin.offset(9, 3, 8);
    }

    /** 4×5 的圆石平台（20 格，相对坐标 6 2 6 ~ 10 2 9）。 */
    public List<BlockPos> platform() {
        List<BlockPos> cells = new ArrayList<>(PLATFORM_XZ.length);
        for (int[] xz : PLATFORM_XZ) {
            cells.add(initialMin.offset(xz[0], 2, xz[1]));
        }
        return cells;
    }

    /**
     * 搭 / 拆平台时悬停的位置（相对坐标 8 4 8，平台正上方那一格空气）。
     *
     * <p>从临时站立点 6 4 6 看，平台最远那个角（10 2 9）离眼睛差不多 6 格，
     * 超过服务端允许的交互距离（{@code actions.reach} + 1 = 5.5），放不上去也挖不到；
     * 站到平台正上方就全都在 5 格以内了。
     */
    public BlockPos platformHover() {
        return initialMin.offset(8, 4, 8);
    }

    /** 六个盒子的位置（顺序：黏液块 ×2、圆石 ×2、收集 ×2）。 */
    public List<BlockPos> boxSpots() {
        return List.of(slimeSpotA(), slimeSpotB(), cobbleSpotA(), cobbleSpotB(),
            collectSpotA(), collectSpotB());
    }

    /**
     * 拆平台时交给 mine simple 的范围：把平台 20 格和 4 5 4 那个圆石**一起框进来**。
     *
     * <p>框是「平台 + marker」的包围盒（相对坐标 4 2 4 ~ 10 5 9）。里面本来就只剩空气和
     * 要拆的那 21 个圆石，多框进来几格空气无所谓；过滤器用 {@link MineFilter#ALL}
     * （拆自己放的东西，不该被玩家的挖掘白/黑名单挡住）。
     */
    public MineRegion platformRegion() {
        return MineRegion.of(dimension,
            initialMin.offset(4, 2, 4), initialMin.offset(10, 5, 9), MineFilter.ALL);
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
     * <p>就是需求里的「边缘目标区域」：包裹住目标区域的那一层，往这里补方块就不会漏
     * （水 / 熔岩 / 空气都是漏洞）。上面那一层不算 —— 那是我们自己走位的地方。
     *
     * <p><b>每个方向单独配置</b>（见 {@link SingleStageDetail}）：
     * <ul>
     *   <li>{@code auto}：旁边那个子区块整块都是空气的话这一面墙就不建（挡了也没用，纯浪费）；
     *       底部不看外面空不空，一律建；</li>
     *   <li>{@code cobblestone}：不管外面空不空都建（这一面用圆石填，见 {@link #cobbleCells}）。</li>
     * </ul>
     *
     * <p>底部还有一条固定规则：**不是最底层**的时候底部一律填黏液块（下一轮要把它挖掉），
     * 只有最底层才看 {@code bottom} 的配置。
     *
     * @param level 拿不到世界（{@code null}）就按「四面都要建」算
     */
    public List<BlockPos> shellCells(int iteration, @Nullable Level level) {
        List<BlockPos> cells = new ArrayList<>();
        int minX = targetMin.getX();
        int minZ = targetMin.getZ();
        int bottom = regionBottom(iteration);
        int top = regionTop(iteration);
        int subY = targetMin.getY();

        boolean west = wallNeeded(Direction.WEST, level, minX - 16, subY, minZ);
        boolean east = wallNeeded(Direction.EAST, level, minX + 16, subY, minZ);
        boolean north = wallNeeded(Direction.NORTH, level, minX, subY, minZ - 16);
        boolean south = wallNeeded(Direction.SOUTH, level, minX, subY, minZ + 16);

        for (int y = bottom; y <= top; y++) {
            for (int i = 0; i < 16; i++) {
                if (west) cells.add(new BlockPos(minX - 1, y, minZ + i));
                if (east) cells.add(new BlockPos(minX + 16, y, minZ + i));
                if (north) cells.add(new BlockPos(minX + i, y, minZ - 1));
                if (south) cells.add(new BlockPos(minX + i, y, minZ + 16));
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.add(new BlockPos(minX + x, bottom - 1, minZ + z));   // 底
            }
        }
        return cells;
    }

    /**
     * 这一轮里「要用圆石填」的壳格。
     *
     * <p>= 配置成 {@code cobblestone} 的那几面墙 + （最底层时配置成 {@code cobblestone} 的底面）。
     * 不是最底层的时候底部一律是黏液块，所以不进这个列表。
     */
    public List<BlockPos> cobbleCells(int iteration, @Nullable Level level) {
        List<BlockPos> cells = new ArrayList<>();
        int minX = targetMin.getX();
        int minZ = targetMin.getZ();
        int bottom = regionBottom(iteration);
        int top = regionTop(iteration);
        int subY = targetMin.getY();

        boolean west = wallNeeded(Direction.WEST, level, minX - 16, subY, minZ);
        boolean east = wallNeeded(Direction.EAST, level, minX + 16, subY, minZ);
        boolean north = wallNeeded(Direction.NORTH, level, minX, subY, minZ - 16);
        boolean south = wallNeeded(Direction.SOUTH, level, minX, subY, minZ + 16);

        boolean westCobble = west && detail.wall(Direction.WEST) == SingleStageDetail.WallMode.COBBLESTONE;
        boolean eastCobble = east && detail.wall(Direction.EAST) == SingleStageDetail.WallMode.COBBLESTONE;
        boolean northCobble = north && detail.wall(Direction.NORTH) == SingleStageDetail.WallMode.COBBLESTONE;
        boolean southCobble = south && detail.wall(Direction.SOUTH) == SingleStageDetail.WallMode.COBBLESTONE;

        if (westCobble || eastCobble || northCobble || southCobble) {
            for (int y = bottom; y <= top; y++) {
                for (int i = 0; i < 16; i++) {
                    if (westCobble) cells.add(new BlockPos(minX - 1, y, minZ + i));
                    if (eastCobble) cells.add(new BlockPos(minX + 16, y, minZ + i));
                    if (northCobble) cells.add(new BlockPos(minX + i, y, minZ - 1));
                    if (southCobble) cells.add(new BlockPos(minX + i, y, minZ + 16));
                }
            }
        }

        // 底部：只有最底层才可能用圆石
        if (iteration == lastIteration()
            && detail.bottom() == SingleStageDetail.WallMode.COBBLESTONE) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    cells.add(new BlockPos(minX + x, bottom - 1, minZ + z));
                }
            }
        }
        return cells;
    }

    /**
     * 这个方向的墙要不要建。
     *
     * <p>{@code cobblestone} 一律要（明确指定了）；{@code auto} 才看旁边那个子区块空不空。
     */
    private boolean wallNeeded(Direction direction, @Nullable Level level, int x, int y, int z) {
        if (detail.wall(direction) == SingleStageDetail.WallMode.COBBLESTONE) return true;
        return !skipWall(level, x, y, z);
    }

    /** 这一面旁边那个子区块是不是整块空气（是就不用建这面墙）。 */
    private static boolean skipWall(@Nullable Level level, int x, int y, int z) {
        return level != null && subChunkEmpty(level, new BlockPos(x, y, z));
    }

    /** 这个 16×16×16 的子区块是不是全是空气。 */
    public static boolean subChunkEmpty(Level level, BlockPos min) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    pos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    if (!level.getBlockState(pos).isAir()) return false;
                }
            }
        }
        return true;
    }

    /** 全量目标区域 = 目标区域 + 那一圈壳。 */
    public List<BlockPos> wrapCells(int iteration, @Nullable Level level) {
        List<BlockPos> cells = new ArrayList<>(regionCells(iteration));
        cells.addAll(shellCells(iteration, level));
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
        LocalPlayer player = PlayerUtils.player();
        if (player != null) data.refreshInventory(player.getInventory());
    }

    /** 物品栏里这一格（实时读，不走快照）。 */
    public ItemStack live(int index) {
        LocalPlayer player = PlayerUtils.player();
        if (player == null) return ItemStack.EMPTY;
        return player.getInventory().getItem(index);
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

    /**
     * **搭平台之前**要保证是空的那些格子：快捷栏 6~8、背包 22~27、以及已经放出去的盒子的原格。
     *
     * <p>和 {@link #expectedEmptySlots} 的区别：这时候收集盒还没放出去（还在背包 13~21 里），
     * 所以不能把收集位算进来 —— 算了会把还没放的盒子当垃圾扔掉。
     */
    public List<Integer> setupJunkSlots() {
        List<Integer> slots = new ArrayList<>();
        slots.add(HOTBAR_FREE_1);
        slots.add(HOTBAR_FREE_2);
        slots.add(HOTBAR_FREE_3);
        for (int i = FILLER_START; i <= FILLER_END; i++) slots.add(i);
        for (int slot : placedBoxSlots) {
            if (!slots.contains(slot)) slots.add(slot);
        }
        return slots;
    }

    // ------------------------------------------------------------------
    // 认「刚挖下来的盒子」
    // ------------------------------------------------------------------

    /** 物品栏里现在有几个潜影盒（0 ~ 背包最后一格）。 */
    public int shulkerBoxCount() {
        int count = 0;
        for (int slot = 0; slot <= FILLER_END; slot++) {
            if (ShulkerUtils.isShulkerBox(live(slot))) count++;
        }
        return count;
    }

    /**
     * 物品栏里每个潜影盒的「格子 -> 内容指纹」。
     *
     * <p>挖盒子**之前**拍一张，挖完再用 {@link #findPickedBox} 认出「刚吸进来的那个」
     * —— 光比内容不够（可能有两个一样的盒子），比「挖之前这格不是这个内容」才准。
     */
    public Map<Integer, String> boxSignatures() {
        Map<Integer, String> map = new HashMap<>();
        for (int slot = 0; slot <= FILLER_END; slot++) {
            ItemStack stack = live(slot);
            if (!ShulkerUtils.isShulkerBox(stack)) continue;
            map.put(slot, signature(ShulkerUtils.contents(stack)));
        }
        return map;
    }

    /**
     * 挖下来的那个盒子现在在哪一格；还没吸进来返回 -1。
     *
     * @param before 挖之前拍的 {@link #boxSignatures()}
     */
    public int findPickedBox(Map<Integer, String> before, List<ItemStack> contents) {
        String want = signature(contents);
        int fallback = -1;

        for (int slot = 0; slot <= FILLER_END; slot++) {
            ItemStack stack = live(slot);
            if (!ShulkerUtils.isShulkerBox(stack)) continue;
            if (!signature(ShulkerUtils.contents(stack)).equals(want)) continue;

            String was = before.get(slot);
            if (was == null || !was.equals(want)) return slot;   // 这一格是挖完才变成这个内容的
            if (fallback < 0) fallback = slot;
        }
        return fallback;
    }

    /**
     * 和 {@link #findPickedBox} 一样，但**不接受**「挖之前这一格就是同样内容」的格子。
     *
     * <p>挖一个**空盒**的时候用：物品栏里本来就可能有别的空盒（背包 10 / 11 常常是空的），
     * 宽松版会立刻认成「已经吸到了」，结果拿错盒子。
     */
    public int findPickedBoxStrict(Map<Integer, String> before, List<ItemStack> contents) {
        String want = signature(contents);
        for (int slot = 0; slot <= FILLER_END; slot++) {
            ItemStack stack = live(slot);
            if (!ShulkerUtils.isShulkerBox(stack)) continue;
            if (!signature(ShulkerUtils.contents(stack)).equals(want)) continue;
            String was = before.get(slot);
            if (was == null || !was.equals(want)) return slot;
        }
        return -1;
    }

    /** 一盒东西的指纹：物品 id -> 个数（顺序无关，方便比较）。 */
    public static String signature(List<ItemStack> contents) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : contents) {
            counts.merge(SingleStageData.idOf(stack), stack.getCount(), Integer::sum);
        }
        return counts.toString();
    }

    // ------------------------------------------------------------------
    // 放方块的有效范围
    // ------------------------------------------------------------------

    /** 放方块的有效范围半径：以玩家所在方块为中心 7×7×7 的正方体。 */
    public static final int PLACE_RANGE = 3;

    /**
     * 站在 {@code stand} 这一格的时候，{@code pos} 放不放得上去。
     *
     * <p>规则见 {@link #canPlaceAt}：以脚下那格为中心 7×7×7 的正方体。
     */
    public static boolean canPlaceFrom(BlockPos stand, BlockPos pos) {
        return Math.abs(pos.getX() - stand.getX()) <= PLACE_RANGE
            && Math.abs(pos.getY() - stand.getY()) <= PLACE_RANGE
            && Math.abs(pos.getZ() - stand.getZ()) <= PLACE_RANGE;
    }

    /**
     * 这个位置现在放不放得上去。
     *
     * <p><b>不用 {@code actions.reach}</b>：需求里实测服务端那边松得多（7 格都能放），
     * 所以这里按「以玩家下半身所在方块为中心、7×7×7 的正方体」判断
     * （{@code |dx|、|dy|、|dz| <= }{@value #PLACE_RANGE}）。
     */
    public boolean canPlaceAt(BlockPos pos) {
        BlockPos here = PlayerUtils.currentBlockPos();
        return here != null && canPlaceFrom(here, pos);
    }
}
