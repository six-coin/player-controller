package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mine.MineTools;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCheck;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCtx;
import org.six_coin.playerController.client.mine.station.MineStationPart;
import org.six_coin.playerController.client.mine.station.MineToolRules;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 第二阶段要凑出来的物品栏（就是 {@link SingleStageCheck#checkInventory} 要求的那一套）。
 *
 * <p>每一条 = 「物品栏第 N 格放什么 + 从哪个部分拿 + 什么样的算合格」。顺序就是拿的顺序
 * （工具 → 食物 / 图腾 → 潜影盒 → 空盒 → 熔岩桶），因为 {@link MineTaskTakeAction} 是
 * 「走到容器、开一次、拿一叠」，按部分排一下少走几趟。
 *
 * <p>格子编号和 {@link SingleStageCtx} 里那套常量一致（{@code PlayerInventory} 下标）：
 * <pre>
 *   0~4    快捷栏 1~5：剑 镐 斧 铲 锄（斧头 / 锄头剩 ≥1200 耐久即可，别的不管耐久）
 *   5~7    快捷栏 6~8：空着
 *   8      快捷栏 9：熟猪排（≥16）
 *   9~13   背包 1~5：备用剑 / 备用镐 / 备用镐 / 备用铲 / 备用镐（耐久满）
 *   14~16  背包 6~8：圆石盒（满 / ≥5 组 / 随便）
 *   17~19  背包 9~11：黏液块盒（满 / ≥5 组 / 随便）
 *   20     背包 12：熔岩桶
 *   21~29  背包 13~21：9 个空潜影盒（收集用）
 *   30~35  背包 22~27：随便
 *   40     副手：不死图腾（先放 35，再换到副手）
 * </pre>
 *
 * <p><b>9 个空潜影盒和熔岩桶不用去站里拿</b>：开始挖掘前玩家身上必须已经带着
 * （见 {@code MineTaskMiningAction} 的预检查），这里只负责把它们从「玩家摆的位置」
 * 挪到上表那几格（{@link #boxesToCollectSlots()} / {@link #lavaBucketMove()}）。
 */
public final class MineTaskKit {

    /** 一满盒。 */
    public static final int FULL_BOX = 27 * 64;

    /** 「半盒」的门槛：5 组。 */
    public static final int HALF_BOX = 5 * 64;

    /** 食物至少要这么多。 */
    public static final int FOOD_MIN = 16;

    /** 图腾先放在这一格，最后再换到副手（容器界面里没有副手格）。 */
    public static final int TOTEM_STAGING = 35;

    /** 副手（{@code PlayerInventory} 下标）。 */
    public static final int OFFHAND = 40;

    /** 一条「拿什么放到哪」。 */
    public record Slot(int index, String label, MineStationPart part,
                       Predicate<ItemStack> filter,
                       @Nullable Predicate<String> itemIdHint) {
    }

    private MineTaskKit() {
    }

    /** 完整的备货清单（按拿的顺序）。 */
    public static List<Slot> slots() {
        List<Slot> list = new ArrayList<>();

        // 快捷栏 1~5：剑 镐 斧 铲 锄。斧头 / 锄头只要剩 ≥1200 耐久就行，别的有备用、不看耐久
        MineToolRules.Kind[] kinds = {MineToolRules.Kind.SWORD, MineToolRules.Kind.PICKAXE,
            MineToolRules.Kind.AXE, MineToolRules.Kind.SHOVEL, MineToolRules.Kind.HOE};
        for (int i = 0; i < kinds.length; i++) {
            MineToolRules.Kind kind = kinds[i];
            list.add(new Slot(i, kind.label(), MineStationPart.TOOL_CACHE,
                stack -> MineToolRules.kindOf(stack) == kind
                    && MineToolRules.isLegal(stack)
                    && MineTools.remaining(stack) >= MineToolRules.minDurability(kind),
                toolId(kind)));
        }

        // 快捷栏 9：熟猪排
        list.add(new Slot(SingleStageCtx.HOTBAR_FOOD, "熟猪排", MineStationPart.FOOD_PROVIDER,
            stack -> stack.is(Items.COOKED_PORKCHOP) && stack.getCount() >= FOOD_MIN,
            id -> id.equals("minecraft:cooked_porkchop")));

        // 背包 1~5：备用剑 / 备用镐 ×3 / 备用铲，耐久必须满
        list.add(spare(SingleStageCtx.SPARE_SWORD, MineToolRules.Kind.SWORD));
        list.add(spare(SingleStageCtx.SPARE_PICKAXE_1, MineToolRules.Kind.PICKAXE));
        list.add(spare(SingleStageCtx.SPARE_PICKAXE_2, MineToolRules.Kind.PICKAXE));
        list.add(spare(SingleStageCtx.SPARE_SHOVEL, MineToolRules.Kind.SHOVEL));
        list.add(spare(SingleStageCtx.SPARE_PICKAXE_3, MineToolRules.Kind.PICKAXE));

        // 背包 6~8：圆石盒（满 / ≥5 组 / 随便）
        list.add(new Slot(SingleStageCtx.COBBLE_BOX_A, "装满圆石的潜影盒",
            MineStationPart.COBBLESTONE_CACHE, boxOf(Set.of(Items.COBBLESTONE), false, FULL_BOX, FULL_BOX), null));
        list.add(new Slot(SingleStageCtx.COBBLE_BOX_B, "半满以上的圆石盒",
            MineStationPart.COBBLESTONE_CACHE, boxOf(Set.of(Items.COBBLESTONE), false, HALF_BOX, FULL_BOX), null));
        list.add(new Slot(SingleStageCtx.COBBLE_BOX_FULL, "圆石盒",
            MineStationPart.COBBLESTONE_CACHE, boxOf(Set.of(Items.COBBLESTONE), false, 0, FULL_BOX), null));

        // 背包 9~11：黏液块盒（整个缓存只有一种，所以盒子里不许混装）
        Set<Item> slime = Set.of(Items.SLIME_BLOCK, Items.RESIN_BLOCK);
        list.add(new Slot(SingleStageCtx.SLIME_BOX_A, "装满黏液块 / 树脂块的潜影盒",
            MineStationPart.SLIME_CACHE, boxOf(slime, true, FULL_BOX, FULL_BOX), null));
        list.add(new Slot(SingleStageCtx.SLIME_BOX_B, "半满以上的黏液块盒",
            MineStationPart.SLIME_CACHE, boxOf(slime, true, HALF_BOX, FULL_BOX), null));
        list.add(new Slot(SingleStageCtx.SLIME_BOX_FULL, "黏液块盒",
            MineStationPart.SLIME_CACHE, boxOf(slime, true, 0, FULL_BOX), null));

        // 副手：不死图腾（先放背包 28，最后换过去）
        list.add(new Slot(TOTEM_STAGING, "不死图腾", MineStationPart.TOTEM_PROVIDER,
            stack -> stack.is(Items.TOTEM_OF_UNDYING),
            id -> id.equals("minecraft:totem_of_undying")));

        return list;
    }

    // ------------------------------------------------------------------
    // 玩家自带的两样东西（预检查里已经验过位置）
    // ------------------------------------------------------------------

    /** 背包 1~5 里的一件备用工具：种类对、合法、耐久必须满。 */
    private static Slot spare(int index, MineToolRules.Kind kind) {
        return new Slot(index, "备用" + kind.label(), MineStationPart.TOOL_CACHE,
            stack -> MineToolRules.kindOf(stack) == kind && MineToolRules.isLegal(stack)
                && stack.getDamageValue() == 0,
            toolId(kind));
    }

    /** 9 个空潜影盒：从快捷栏 1~9 搬到背包 13~21。 */
    public static List<MineTaskInventoryMoveAction.Move> boxesToCollectSlots() {
        List<MineTaskInventoryMoveAction.Move> moves = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            moves.add(new MineTaskInventoryMoveAction.Move(i, SingleStageCtx.COLLECT_START + i,
                "空潜影盒 " + (i + 1) + " → 背包 " + (13 + i)));
        }
        return moves;
    }

    /** 熔岩桶：从背包 1 搬到背包 12。 */
    public static List<MineTaskInventoryMoveAction.Move> lavaBucketMove() {
        return List.of(new MineTaskInventoryMoveAction.Move(SingleStageCtx.SPARE_START,
            SingleStageCtx.LAVA_BUCKET, "熔岩桶 → 背包 12"));
    }

    /** 一个潜影盒是不是「空的」。 */
    public static boolean isEmptyBox(ItemStack stack) {
        return ShulkerUtils.isShulkerBox(stack) && ShulkerUtils.contents(stack).isEmpty();
    }

    // ------------------------------------------------------------------
    // 判断
    // ------------------------------------------------------------------

    /** 一种工具的物品 id（给 station_data 挑容器用）。 */
    private static Predicate<String> toolId(MineToolRules.Kind kind) {
        return id -> {
            Item item = itemOf(id);
            return item != null && MineToolRules.kindOf(item.getDefaultInstance()) == kind;
        };
    }

    @Nullable
    private static Item itemOf(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return null;
        return BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
    }

    /**
     * 一个潜影盒：里面只有 {@code allowed} 里的东西，总数在 {@code min ~ max} 之间。
     *
     * @param singleType 盒子里只能有一种物品（黏液块 / 树脂块二选一）
     */
    private static Predicate<ItemStack> boxOf(Set<Item> allowed, boolean singleType, int min, int max) {
        return stack -> {
            if (!ShulkerUtils.isShulkerBox(stack)) return false;

            int total = 0;
            Set<Item> used = new HashSet<>();
            for (ItemStack inner : ShulkerUtils.contents(stack)) {
                if (!allowed.contains(inner.getItem())) return false;
                used.add(inner.getItem());
                total += inner.getCount();
            }
            if (singleType && used.size() > 1) return false;
            return total >= min && total <= max;
        };
    }
}
