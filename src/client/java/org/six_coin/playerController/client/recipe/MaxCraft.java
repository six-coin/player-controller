package org.six_coin.playerController.client.recipe;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「单背包最大合成」：给定**可用的物品（只看种类，不看数量）**和想要的产物，
 * 算出**一次能合出多少个**以及**需要哪些材料、各多少个**。
 *
 * <p>两个接口：
 * <ul>
 *   <li>{@link #craftingTable}：工作台（配方书里已经解锁的配方）；</li>
 *   <li>{@link #stonecutter}：切石机（服务器同步的配方）。</li>
 * </ul>
 *
 * <p>限制（需求指定）：
 * <ul>
 *   <li>材料总共占 **不超过 27 格**（每个格子最多叠那个物品的堆叠上限）；</li>
 *   <li>产物占 **不超过 6 格**（也就是最多 6 × 64 = 384 个，堆叠上限小的物品更少）。</li>
 * </ul>
 *
 * <p>做法是**二分**：可行性（材料格数、产物格数）随合成次数单调不变差，所以可以二分找最大值。
 * 单次合成出多个产物（比如栅栏一次 3 个）也支持：二分的自变量是「合成几次」，
 * 返回的产物数量一定是单次产量的整数倍。
 */
public final class MaxCraft {

    /** 材料最多占几格。 */
    public static final int MAX_MATERIAL_SLOTS = 27;

    /** 产物最多占几格。 */
    public static final int MAX_RESULT_SLOTS = 6;

    /** 二分的上界：材料 27 格、每格最多 64 个，最多也就 1728 次。 */
    private static final int MAX_CRAFTS = MAX_MATERIAL_SLOTS * 64;

    /**
     * 结果。
     *
     * @param crafts      合几次
     * @param resultCount 一共出多少个（= crafts × 单次产量）
     * @param perCraft    单次产量
     * @param materials   需要的材料（物品 → 个数）
     * @param recipeType  crafting_shaped / crafting_shapeless / stonecutting
     */
    public record Result(int crafts, int resultCount, int perCraft,
                         Map<Item, Integer> materials, String recipeType) {

        /** 需要的材料拼成一行，给日志 / 报错用。 */
        public String describeMaterials() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
                if (sb.length() > 0) sb.append("、");
                sb.append(Registries.ITEM.getId(entry.getKey())).append(" x").append(entry.getValue());
            }
            return sb.toString();
        }
    }

    private MaxCraft() {
    }

    /**
     * 工作台：用 {@code available} 里的物品（种类）能一次合出多少个 {@code result}。
     *
     * @param available 可用的物品（只看种类）
     * @return 合不出来返回 null
     */
    @Nullable
    public static Result craftingTable(Item result, Collection<Item> available) {
        return search(RecipePlanner.craftingOptions(result), available, result);
    }

    /**
     * 切石机：用 {@code available} 里的物品能一次切出多少个 {@code result}。
     */
    @Nullable
    public static Result stonecutter(Item result, Collection<Item> available) {
        return search(RecipePlanner.stonecuttingOptions(result), available, result);
    }

    // ------------------------------------------------------------------

    @Nullable
    private static Result search(List<RecipePlanner.Option> options, Collection<Item> available,
                                 Item result) {
        if (options.isEmpty() || available.isEmpty()) return null;

        Set<Item> pool = available instanceof Set<Item> set
            ? set : new java.util.LinkedHashSet<>(available);
        Result best = null;

        for (RecipePlanner.Option option : options) {
            // 每个格子挑一个「可用物品」里有的东西；挑不到这个配方就跳过
            List<Item> pick = new ArrayList<>();
            boolean usable = true;
            for (List<Item> accepted : option.cells()) {
                if (accepted.isEmpty()) {
                    pick.add(null);   // 空格子
                    continue;
                }
                Item chosen = null;
                for (Item item : accepted) {
                    if (pool.contains(item)) {
                        chosen = item;
                        break;
                    }
                }
                if (chosen == null) {
                    usable = false;
                    break;
                }
                pick.add(chosen);
            }
            if (!usable) continue;

            int crafts = maxCrafts(option, pick, result);
            if (crafts <= 0) continue;

            int resultCount = crafts * option.perCraft();
            Map<Item, Integer> materials = new LinkedHashMap<>();
            for (Item item : pick) {
                if (item == null) continue;
                materials.merge(item, crafts, Integer::sum);
            }

            Result candidate = new Result(crafts, resultCount, option.perCraft(),
                materials, option.recipeType());
            if (best == null || candidate.resultCount() > best.resultCount()) best = candidate;
        }

        return best;
    }

    /** 二分找「这个配方最多能合几次」。 */
    private static int maxCrafts(RecipePlanner.Option option, List<Item> pick, Item result) {
        int lo = 0;
        int hi = MAX_CRAFTS;   // 0 次一定可行，MAX_CRAFTS 次一定放不下，中间二分
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (feasible(option, pick, mid, result)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 合 {@code crafts} 次的话，材料的格数和产物的格数都不超吗。 */
    private static boolean feasible(RecipePlanner.Option option, List<Item> pick, int crafts,
                                    Item result) {
        if (crafts <= 0) return true;

        // 产物：同一种物品，占格按它自己的堆叠上限算
        int resultCount = crafts * option.perCraft();
        if (resultCount <= 0) return false;
        if (slotsFor(resultCount, result) > MAX_RESULT_SLOTS) return false;

        // 材料：按**种类**合计（背包里同一种材料会叠在一起），再算占几格
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (Item item : pick) {
            if (item == null) continue;
            totals.merge(item, crafts, Integer::sum);
        }

        int materialSlots = 0;
        for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
            materialSlots += slotsFor(entry.getValue(), entry.getKey());
            if (materialSlots > MAX_MATERIAL_SLOTS) return false;
        }
        return materialSlots <= MAX_MATERIAL_SLOTS;
    }

    /** {@code amount} 个 {@code item} 要占几格（每格最多 {@code item.getMaxCount()} 个）。 */
    private static int slotsFor(int amount, Item item) {
        if (amount <= 0) return 0;
        int perSlot = Math.max(1, item.getMaxCount());
        return (amount + perSlot - 1) / perSlot;
    }

    /**
     * 日志用：把结果说成一句话。
     */
    public static String describe(Item result, @Nullable Result found) {
        if (found == null) return "合不出 " + Registries.ITEM.getId(result);
        return Registries.ITEM.getId(result) + " x" + found.resultCount()
            + "（合 " + found.crafts() + " 次，每次 " + found.perCraft() + " 个；材料 "
            + found.describeMaterials() + "）";
    }
}
