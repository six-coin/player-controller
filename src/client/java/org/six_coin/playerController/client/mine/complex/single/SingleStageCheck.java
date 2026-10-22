package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.six_coin.playerController.client.mine.station.MineToolRules;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 执行前的两道检查（需求 (1) 和 (2)）。返回一串「为什么不行」，空列表就是通过。
 *
 * <p>(1) 初始子区块必须全是空气（不然我们没地方站、也没地方放盒子），
 * 目标子区块里**每一个**方块都必须是「按当前 mine filter 挖得掉的」（有一个挖不动就拒绝执行）。
 *
 * <p>(2) 物品栏的布局：工具、食物、图腾、三种潜影盒的格子都要对。
 */
public final class SingleStageCheck {

    /** 每个子区块每边 16 格。 */
    private static final int CHUNK = 16;

    /** 圆石一满盒是 27 × 64。 */
    public static final int FULL_BOX = 27 * 64;

    /** 黏液块那盒的最低要求。 */
    public static final int SLIME_BOX_MIN = 1600;

    /** 食物最少要多少个。 */
    public static final int FOOD_MIN = 16;

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
    public static List<String> checkSubChunks(World world, SingleStageCtx ctx) {
        List<String> problems = new ArrayList<>();

        int solid = 0;
        BlockPos firstSolid = null;
        for (BlockPos pos : cells(ctx.initialMin)) {
            if (world.getBlockState(pos).isAir()) continue;
            solid++;
            if (firstSolid == null) firstSolid = pos;
        }
        if (solid > 0) {
            problems.add("初始子区块 " + ctx.initialMin.toShortString() + " 不是空的（有 " + solid
                + " 个非空气方块，第一个是 " + firstSolid.toShortString() + " " + idOf(world, firstSolid)
                + "）—— 先站到一个全是空气的子区块里");
        }

        int blocked = 0;
        List<String> samples = new ArrayList<>();
        for (BlockPos pos : cells(ctx.targetMin)) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            String why = notMineable(world, ctx, pos, state);
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

    /** 这个方块能不能被这次任务挖掉；能挖返回 null，挖不掉返回原因。 */
    private static String notMineable(World world, SingleStageCtx ctx, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof FluidBlock) return "是流体";
        if (state.getHardness(world, pos) < 0.0F) return "挖不动";
        if (!ctx.filter.allows(state.getBlock())) {
            return "被 mine filter 排除（" + Registries.BLOCK.getId(state.getBlock()) + "）";
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

    private static String idOf(World world, BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
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
        } else if (!food.isOf(Items.COOKED_PORKCHOP)) {
            problems.add("快捷栏 9 是 " + SingleStageData.idOf(food) + "，要放熟猪排");
        } else if (food.getCount() < FOOD_MIN) {
            problems.add("快捷栏 9 的熟猪排只有 " + food.getCount() + " 个（要 ≥" + FOOD_MIN + "）");
        }

        // 副手：不死图腾
        ItemStack offhand = data.stack(SingleStageCtx.OFFHAND);
        if (!offhand.isOf(Items.TOTEM_OF_UNDYING)) {
            problems.add("副手是 " + SingleStageData.idOf(offhand) + "，要放不死图腾");
        }

        // 背包 1~5：替换工具，耐久必须满
        toolSlot(data, problems, SingleStageCtx.SPARE_START, MineToolRules.Kind.SWORD, "背包 1", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 1, MineToolRules.Kind.PICKAXE, "背包 2", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 2, MineToolRules.Kind.AXE, "背包 3", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 3, MineToolRules.Kind.SHOVEL, "背包 4", true);
        toolSlot(data, problems, SingleStageCtx.SPARE_START + 4, MineToolRules.Kind.HOE, "背包 5", true);

        // 背包 6、7：只有圆石的盒子（可以不装满）；背包 8：装满圆石
        boxSlot(data, problems, SingleStageCtx.COBBLE_BOX_A, "背包 6", "圆石", Set.of(Items.COBBLESTONE), -1);
        boxSlot(data, problems, SingleStageCtx.COBBLE_BOX_B, "背包 7", "圆石", Set.of(Items.COBBLESTONE), -1);
        boxSlot(data, problems, SingleStageCtx.COBBLE_BOX_FULL, "背包 8", "圆石",
            Set.of(Items.COBBLESTONE), FULL_BOX);

        // 背包 9、10：只有黏液块 / 树脂块（可以不装满）；背包 11：≥1600
        Set<Item> slime = Set.of(Items.SLIME_BLOCK, Items.RESIN_BLOCK);
        boxSlot(data, problems, SingleStageCtx.SLIME_BOX_A, "背包 9", "黏液块 / 树脂块", slime, -1);
        boxSlot(data, problems, SingleStageCtx.SLIME_BOX_B, "背包 10", "黏液块 / 树脂块", slime, -1);
        boxSlot(data, problems, SingleStageCtx.SLIME_BOX_FULL, "背包 11", "黏液块 / 树脂块", slime,
            SLIME_BOX_MIN);

        // 背包 12：替换不死图腾
        ItemStack spareTotem = data.stack(SingleStageCtx.SPARE_TOTEM);
        if (!spareTotem.isOf(Items.TOTEM_OF_UNDYING)) {
            problems.add("背包 12 是 " + SingleStageData.idOf(spareTotem) + "，要放不死图腾");
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
        if (requireFull && stack.getDamage() > 0) {
            problems.add(label + " 的" + kind.label() + "耐久不是满的（剩 "
                + (stack.getMaxDamage() - stack.getDamage()) + "/" + stack.getMaxDamage() + "）");
        }
    }

    private static void emptySlot(SingleStageData data, List<String> problems, int index, String label) {
        if (!data.isEmpty(index)) {
            problems.add(label + " 必须是空的，现在是 " + SingleStageData.idOf(data.stack(index)));
        }
    }

    /**
     * 潜影盒格子：必须是潜影盒、里面只有 {@code allowed}、数量满足 {@code minItems}（-1 = 不限）。
     */
    private static void boxSlot(SingleStageData data, List<String> problems, int index, String label,
                                String what, Set<Item> allowed, int minItems) {
        ItemStack stack = data.stack(index);
        if (stack.isEmpty()) {
            problems.add(label + " 是空的，要放装满" + what + "的潜影盒");
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
        if (minItems >= 0 && total < minItems) {
            problems.add(label + " 的盒子里只有 " + total + " 个" + what + "（要 ≥" + minItems + "）");
        }
    }
}
