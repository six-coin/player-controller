package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.six_coin.playerController.client.mine.station.MineToolRules;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 执行前的两道检查（需求 (1) 和 (2)）。返回一串「为什么不行」，空列表就是通过。
 *
 * <p>(1) 初始子区块必须全是空气（不然我们没地方站、也没地方放盒子），
 * 目标子区块里的方块必须都能被当前 mine filter 挖（水 / 熔岩不算问题 —— 它们能被粘液块替换掉）。
 *
 * <p>(2) 物品栏的布局：工具、食物、图腾、熔岩桶、三种潜影盒的格子都要对。
 */
public final class SingleStageCheck {

    /** 每个子区块每边 16 格。 */
    private static final int CHUNK = 16;

    /** 圆石一满盒是 27 × 64。 */
    public static final int FULL_BOX = 27 * 64;

    /** 黏液块那盒的最低要求。 */
    public static final int SLIME_BOX_MIN = 1600;

    /** 背包 7 那盒圆石的最低要求。 */
    public static final int COBBLE_BOX_MIN = 64;

    /** 背包 10 那盒黏液块 / 树脂块的最低要求。 */
    public static final int SLIME_BOX_B_MIN = 192;

    /** 食物最少要多少个。 */
    public static final int FOOD_MIN = 16;

    /** 这个功能要求的 `actions.reach` 下限。 */
    public static final double MIN_REACH = 5.0;

    /** 潜影盒的三种装满程度。 */
    public enum Fill {
        EMPTY("空"),
        HALF("半满"),
        FULL("满");

        private final String label;

        Fill(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private SingleStageCheck() {
    }

    // ------------------------------------------------------------------
    // (1) 子区块
    // ------------------------------------------------------------------

    /** (1)：初始子区块全空 + 目标子区块全部可挖。 */
    public static List<String> checkSubChunks(Level level, SingleStageCtx ctx) {
        List<String> problems = new ArrayList<>();

        int solid = 0;
        BlockPos firstSolid = null;
        for (BlockPos pos : cells(ctx.initialMin)) {
            if (level.getBlockState(pos).isAir()) continue;
            solid++;
            if (firstSolid == null) firstSolid = pos;
        }
        if (solid > 0) {
            problems.add("初始子区块 " + ctx.initialMin.toShortString() + " 不是空的（有 " + solid
                + " 个非空气方块，第一个是 " + firstSolid.toShortString() + " " + idOf(level, firstSolid)
                + "）—— 先站到一个全是空气的子区块里");
        }

        int blocked = 0;
        List<String> samples = new ArrayList<>();
        for (BlockPos pos : cells(ctx.targetMin)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            String why = notMineable(ctx, state);
            if (why == null) continue;
            blocked++;
            if (samples.size() < 5) samples.add(pos.toShortString() + " " + why);
        }
        if (blocked > 0) {
            problems.add("目标子区块 " + ctx.targetMin.toShortString() + " 里有 " + blocked
                + " 个方块挖不掉（例如 " + String.join("、", samples) + "）—— 这个子区块没法整块清空");
        }
        return problems;
    }

    /**
     * 这个方块能不能被这次任务挖掉；能挖返回 null，挖不掉返回原因。
     *
     * <p><b>只看 mine filter</b>：水 / 熔岩现在能直接拿粘液块替换掉，硬度也拦不住我们
     * （挖不动的那种 mine simple 自己会跳过，不值得为它拒绝整个子区块）。
     */
    private static String notMineable(SingleStageCtx ctx, BlockState state) {
        if (!ctx.filter.allows(state.getBlock())) {
            return "被 mine filter 排除（" + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）";
        }
        return null;
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(CHUNK * CHUNK * CHUNK);
        for (int x = 0; x < CHUNK; x++) {
            for (int y = 0; y < CHUNK; y++) {
                for (int z = 0; z < CHUNK; z++) {
                    cells.add(new BlockPos(min.getX() + x, min.getY() + y, min.getZ() + z));
                }
            }
        }
        return cells;
    }

    private static String idOf(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    // ------------------------------------------------------------------
    // (3) 配置
    // ------------------------------------------------------------------

    /**
     * (3)：{@code actions.reach} 必须 ≥ {@value #MIN_REACH}。
     *
     * <p>这个功能是「站在区域上面那一层挖」「按 7×7×7 放方块」，reach 小了会够不着。
     */
    public static List<String> checkReach() {
        double reach = PlayerUtils.reach();
        if (reach >= MIN_REACH) return List.of();
        return List.of("actions.reach 现在是 " + reach + "，这个功能要求 ≥ " + MIN_REACH
            + "（用 /pc config actions reach 5 改）");
    }

    // ------------------------------------------------------------------
    // (2) 物品栏
    // ------------------------------------------------------------------

    /** (2)：物品栏布局（用 {@link SingleStageData} 里的快照判断）。 */
    public static List<String> checkInventory(SingleStageCtx ctx) {
        SingleStageData data = ctx.data;
        List<String> problems = new ArrayList<>();

        // 快捷栏 1~5：剑镐斧铲锄，不要求耐久
        toolSlot(data, problems, SingleStageCtx.HOTBAR_SWORD, MineToolRules.Kind.SWORD, "快捷栏 1", false);
        toolSlot(data, problems, SingleStageCtx.HOTBAR_PICKAXE, MineToolRules.Kind.PICKAXE, "快捷栏 2", false);
        toolSlot(data, problems, SingleStageCtx.HOTBAR_AXE, MineToolRules.Kind.AXE, "快捷栏 3", false);
        toolSlot(data, problems, SingleStageCtx.HOTBAR_SHOVEL, MineToolRules.Kind.SHOVEL, "快捷栏 4", false);
        toolSlot(data, problems, SingleStageCtx.HOTBAR_HOE, MineToolRules.Kind.HOE, "快捷栏 5", false);

        // 快捷栏 6~8：必须是空的
        emptySlot(data, problems, SingleStageCtx.HOTBAR_FREE_1, "快捷栏 6");
        emptySlot(data, problems, SingleStageCtx.HOTBAR_FREE_2, "快捷栏 7");
        emptySlot(data, problems, SingleStageCtx.HOTBAR_FREE_3, "快捷栏 8");

        // 快捷栏 9：熟猪排 ≥ 16
        ItemStack food = data.stack(SingleStageCtx.HOTBAR_FOOD);
        if (food.isEmpty()) {
            problems.add("快捷栏 9 是空的，要放熟猪排（≥" + FOOD_MIN + " 个）");
        } else if (!food.is(Items.COOKED_PORKCHOP)) {
            problems.add("快捷栏 9 是 " + SingleStageData.idOf(food) + "，要放熟猪排");
        } else if (food.getCount() < FOOD_MIN) {
            problems.add("快捷栏 9 的熟猪排只有 " + food.getCount() + " 个（要 ≥" + FOOD_MIN + "）");
        }

        // 副手：不死图腾
        ItemStack offhand = data.stack(SingleStageCtx.OFFHAND);
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {
            problems.add("副手是 " + SingleStageData.idOf(offhand) + "，要放不死图腾");
        }

        // 背包 1~5：替换工具，耐久必须满
        toolSlot(data, problems, SingleStageCtx.SPARE_START, MineToolRules.Kind.SWORD, "背包 1", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 1, MineToolRules.Kind.PICKAXE, "背包 2", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 2, MineToolRules.Kind.AXE, "背包 3", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 3, MineToolRules.Kind.SHOVEL, "背包 4", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 4, MineToolRules.Kind.HOE, "背包 5", true);

        // 背包 6~8：圆石。6 必须是装满的（1728）；7 至少 64 个；8 随便（空 / 半满 / 满都行），
        // 只要 7 不比 8 少（倒序 —— 拆站装回去的时候也是按这个顺序放的）
        Set<Item> cobble = Set.of(Items.COBBLESTONE);
        boxSlot(data, problems, SingleStageCtx.COBBLE_BOX_A, "背包 6", "圆石", cobble, FULL_BOX);
        boxSlot(data, problems, SingleStageCtx.COBBLE_BOX_B, "背包 7", "圆石", cobble, COBBLE_BOX_MIN);
        boxSlot(data, problems, SingleStageCtx.COBBLE_BOX_FULL, "背包 8", "圆石", cobble, 0);
        descending(problems, data, "圆石", "背包 7", SingleStageCtx.COBBLE_BOX_B,
            "背包 8", SingleStageCtx.COBBLE_BOX_FULL);

        // 背包 9~11：黏液块 / 树脂块。9 必须装满（≥1600）；10 至少 192；11 随便，但要倒序
        Set<Item> slime = Set.of(Items.SLIME_BLOCK, Items.RESIN_BLOCK);
        boxSlot(data, problems, SingleStageCtx.SLIME_BOX_A, "背包 9", "黏液块 / 树脂块", slime,
            SLIME_BOX_MIN);
        boxSlot(data, problems, SingleStageCtx.SLIME_BOX_B, "背包 10", "黏液块 / 树脂块", slime,
            SLIME_BOX_B_MIN);
        boxSlot(data, problems, SingleStageCtx.SLIME_BOX_FULL, "背包 11", "黏液块 / 树脂块", slime, 0);
        descending(problems, data, "黏液块 / 树脂块", "背包 10", SingleStageCtx.SLIME_BOX_B,
            "背包 11", SingleStageCtx.SLIME_BOX_FULL);

        // 背包 12：熔岩桶（拆平台前拿去烧垃圾，烧完归位）
        ItemStack lava = data.stack(SingleStageCtx.LAVA_BUCKET);
        if (!lava.is(Items.LAVA_BUCKET)) {
            problems.add("背包 12 是 " + SingleStageData.idOf(lava) + "，要放一个熔岩桶");
        }

        // 背包 13~21：收集用的潜影盒
        checkCollectBoxes(data, problems);

        return problems;
    }

    /**
     * 背包 13~17 必须是「若干个满 + 最多一个半 + 若干个空」这种顺序；18~21 必须全空。
     *
     * <p>这样「从 13 开始往后找」才总是先碰到装满的、再碰到半满的、最后是空的。
     */
    private static void checkCollectBoxes(SingleStageData data, List<String> problems) {
        List<Fill> states = new ArrayList<>();
        for (int index = SingleStageCtx.COLLECT_START; index <= SingleStageCtx.COLLECT_END; index++) {
            String label = "背包 " + (index - SingleStageCtx.COLLECT_START + 13);
            ItemStack stack = data.stack(index);
            if (!ShulkerUtils.isShulkerBox(stack)) {
                problems.add(label + " 是 " + SingleStageData.idOf(stack) + "，要放潜影盒");
                states.add(Fill.EMPTY);
                continue;
            }
            states.add(fillOf(stack));
        }

        // 13~17：满* 半? 空*
        boolean sawEmpty = false;
        boolean sawHalf = false;
        for (int i = 0; i < 5; i++) {
            Fill fill = states.get(i);
            String label = "背包 " + (13 + i);
            if (fill == Fill.EMPTY) {
                sawEmpty = true;
            } else if (sawEmpty) {
                problems.add(label + " 是" + fill.label() + "，但前面已经有空盒子了"
                    + "（13~17 的顺序必须是「满…、最多一个半满、空…」）");
            } else if (fill == Fill.HALF) {
                if (sawHalf) problems.add(label + " 又是一个半满的（13~17 里最多只能有一个半满）");
                sawHalf = true;
            } else if (sawHalf) {
                problems.add(label + " 是满的，但前面已经有半满的了"
                    + "（13~17 的顺序必须是「满…、最多一个半满、空…」）");
            }
        }

        // 18~21：必须全空
        for (int i = 5; i < 9; i++) {
            if (states.get(i) != Fill.EMPTY) {
                problems.add("背包 " + (13 + i) + " 是" + states.get(i).label()
                    + "的，18~21 必须是空潜影盒");
            }
        }
    }

    /** 一个潜影盒的装满程度。 */
    public static Fill fillOf(ItemStack box) {
        int used = ShulkerUtils.contents(box).size();
        if (used == 0) return Fill.EMPTY;
        return used >= SingleStageData.BOX_SLOTS ? Fill.FULL : Fill.HALF;
    }

    // ------------------------------------------------------------------

    private static void toolSlot(SingleStageData data, List<String> problems, int index,
                                 MineToolRules.Kind kind, String label, boolean requireFull) {
        ItemStack stack = data.stack(index);
        if (stack.isEmpty()) {
            problems.add(label + " 是空的，要放" + kind.label());
            return;
        }
        if (MineToolRules.kindOf(stack) != kind) {
            problems.add(label + " 是 " + SingleStageData.idOf(stack) + "，要放" + kind.label());
            return;
        }
        if (MineToolRules.level(stack, Enchantments.MENDING) < 1) {
            problems.add(label + " 的" + kind.label() + "没有经验修补");
        }
        if (MineToolRules.level(stack, Enchantments.UNBREAKING) < 3) {
            problems.add(label + " 的" + kind.label() + "没有耐久 III");
        }
        if (requireFull && stack.getDamageValue() > 0) {
            problems.add(label + " 的" + kind.label() + "耐久不是满的（剩 "
                + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage() + "）");
        }
    }

    private static void emptySlot(SingleStageData data, List<String> problems, int index, String label) {
        if (!data.isEmpty(index)) {
            problems.add(label + " 必须是空的，现在是 " + SingleStageData.idOf(data.stack(index)));
        }
    }

    /**
     * 潜影盒格子：必须是潜影盒、里面只有 {@code allowed}，数量至少 {@code minItems}
     * （0 = 只要是这类东西就行，空盒子也算）。
     */
    private static void boxSlot(SingleStageData data, List<String> problems, int index, String label,
                                String what, Set<Item> allowed, int minItems) {
        ItemStack stack = data.stack(index);
        if (stack.isEmpty()) {
            problems.add(label + " 是空的，要放装" + what + "的潜影盒");
            return;
        }
        if (!ShulkerUtils.isShulkerBox(stack)) {
            problems.add(label + " 是 " + SingleStageData.idOf(stack) + "，要放潜影盒");
            return;
        }

        int total = 0;
        for (ItemStack inner : ShulkerUtils.contents(stack)) {
            if (!allowed.contains(inner.getItem())) {
                problems.add(label + " 的盒子里有 " + SingleStageData.idOf(inner)
                    + "，只能装" + what);
                return;
            }
            total += inner.getCount();
        }
        if (minItems > 0 && total < minItems) {
            problems.add(label + " 的盒子里只有 " + total + " 个" + what + "（要 ≥" + minItems + "）");
        }
    }

    /** 前面那格的东西不能比后面那格少（卸货装回去的时候也是按这个顺序放的）。 */
    private static void descending(List<String> problems, SingleStageData data, String what,
                                   String firstLabel, int firstIndex,
                                   String secondLabel, int secondIndex) {
        int first = boxItems(data.stack(firstIndex));
        int second = boxItems(data.stack(secondIndex));
        if (first < second) {
            problems.add(firstLabel + " 的" + what + "（" + first + "）比 " + secondLabel
                + " 的（" + second + "）还少，顺序反了（多的要在前面）");
        }
    }

    /** 一盒东西一共几个。 */
    public static int boxItems(ItemStack box) {
        int total = 0;
        for (ItemStack inner : ShulkerUtils.contents(box)) total += inner.getCount();
        return total;
    }
}
