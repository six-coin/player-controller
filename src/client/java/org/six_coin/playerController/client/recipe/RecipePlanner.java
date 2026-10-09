package org.six_coin.playerController.client.recipe;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.context.ContextParameterMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配方规划：从「配方书 / 切石机配方」里找出能做出目标物品的配方，检查材料够不够、
 * 能不能一次摆到格子里，最后给出一份 {@link CraftPlan}。
 *
 * <p>几点说明：
 * <ul>
 *   <li>1.21.11 的客户端拿不到完整的合成配方表（{@code RecipeManager} 只剩原料属性表和切石机），
 *       能用的只有配方书里同步过来的显示数据，也就是**已经解锁过的配方** —— 没解锁的找不到；</li>
 *   <li>材料只看主背包 27 格（不含快捷栏 9 格）；</li>
 *   <li>「一次摆完」的检查：每个格子要放 {@code 合成次数} 个，不能超过那个物品的堆叠上限
 *       （比如发射器中间那把弓，堆叠上限 1，所以一次只能做一个）；</li>
 *   <li>产物数量必须是单次产量的整数倍，而且 1~64。</li>
 * </ul>
 */
public final class RecipePlanner {

    /** 规划结果：成功了给计划，失败了给一句为什么。 */
    public record Result(@Nullable CraftPlan plan, @Nullable String error) {
        public boolean ok() {
            return plan != null;
        }
    }

    private RecipePlanner() {
    }

    // ------------------------------------------------------------------
    // 工作台
    // ------------------------------------------------------------------

    public static Result planCrafting(Item target, int count) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return fail("没有玩家或世界");
        if (count < 1 || count > 64) return fail("数量要在 1~64 之间");

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        Map<Item, Integer> available = inventoryCounts(player);

        List<RecipeDisplayEntry> entries = new ArrayList<>();
        for (RecipeResultCollection collection : player.getRecipeBook().getOrderedResults()) {
            entries.addAll(collection.getAllRecipes());
        }
        if (entries.isEmpty()) {
            return fail("配方书里还没有合成配方（服务器没同步，或者一个都没解锁？）");
        }

        List<String> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (RecipeDisplayEntry entry : entries) {
            RecipeDisplay display = entry.display();

            List<SlotDisplay> ingredients;
            boolean shaped;
            int width;
            if (display instanceof ShapedCraftingRecipeDisplay shapedDisplay) {
                shaped = true;
                ingredients = shapedDisplay.ingredients();
                width = Math.max(1, shapedDisplay.width());
            } else if (display instanceof ShapelessCraftingRecipeDisplay shapelessDisplay) {
                shaped = false;
                ingredients = shapelessDisplay.ingredients();
                width = 3;
            } else {
                continue;
            }

            ItemStack result = display.result().getFirst(context);
            if (result.isEmpty() || !result.isOf(target)) continue;
            if (!seen.add(entry.id() + " " + ingredients.size())) continue;

            int perCraft = result.getCount();
            if (perCraft <= 0) continue;
            if (count % perCraft != 0) {
                rejected.add(name(target) + " 一次 " + perCraft + " 个，" + count + " 除不尽");
                continue;
            }
            int crafts = count / perCraft;

            List<CraftPlan.Cell> cells = new ArrayList<>();
            Map<Item, Integer> left = new HashMap<>(available);
            String problem = null;

            for (int i = 0; i < ingredients.size(); i++) {
                List<ItemStack> accepted = ingredients.get(i).getStacks(context);
                if (accepted.isEmpty()) continue;   // 空格子

                int gridSlot = shaped ? (i / width) * 3 + (i % width) : i;
                if (gridSlot >= 9) {
                    problem = "这个配方有 " + ingredients.size() + " 格，3×3 摆不下";
                    break;
                }

                Item chosen = choose(accepted, left, crafts);
                if (chosen == null) {
                    problem = acceptedNames(accepted) + " 不够（每格要放 " + crafts + " 个）";
                    break;
                }
                if (crafts > chosen.getMaxCount()) {
                    problem = name(chosen) + " 一格最多放 " + chosen.getMaxCount()
                        + " 个，这次每个格子要放 " + crafts + " 个";
                    break;
                }

                left.put(chosen, left.getOrDefault(chosen, 0) - crafts);
                cells.add(new CraftPlan.Cell(gridSlot, chosen, crafts));
            }

            if (problem != null) {
                rejected.add(problem);
                continue;
            }
            if (cells.isEmpty()) continue;

            return ok(new CraftPlan(CraftPlan.Station.CRAFTING_TABLE, target,
                count, crafts, perCraft, cells));
        }

        if (rejected.isEmpty()) {
            return fail("配方书里没有能做出 " + name(target) + " 的配方（没解锁的配方客户端看不到）");
        }
        return fail("这些配方都不行：" + String.join("；", rejected));
    }

    // ------------------------------------------------------------------
    // 切石机
    // ------------------------------------------------------------------

    public static Result planStonecutting(Item target, int count) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return fail("没有玩家或世界");
        if (count < 1 || count > 64) return fail("数量要在 1~64 之间");

        RecipeManager manager = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getRecipeManager();
        if (manager == null) return fail("还没连上服务器，拿不到切石机配方");

        CuttingRecipeDisplay.Grouping<StonecuttingRecipe> grouping = manager.getStonecutterRecipes();
        if (grouping == null || grouping.isEmpty()) return fail("服务器没同步切石机配方");

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        Map<Item, Integer> available = inventoryCounts(player);
        List<String> rejected = new ArrayList<>();

        for (CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe> entry : grouping.entries()) {
            ItemStack result = firstMatch(entry.recipe().optionDisplay().getStacks(context), target);
            if (result == null) continue;

            int perCraft = Math.max(1, result.getCount());
            if (count % perCraft != 0) {
                rejected.add(name(target) + " 一次 " + perCraft + " 个，" + count + " 除不尽");
                continue;
            }
            int crafts = count / perCraft;

            Item input = chooseInput(entry.input(), available, crafts);
            if (input == null) {
                rejected.add(inputNames(entry.input()) + " 不够（要 " + crafts + " 个）");
                continue;
            }
            if (crafts > input.getMaxCount()) {
                rejected.add(name(input) + " 一格最多放 " + input.getMaxCount()
                    + " 个，这次要放 " + crafts + " 个");
                continue;
            }

            List<CraftPlan.Cell> cells = List.of(new CraftPlan.Cell(0, input, crafts));
            return ok(new CraftPlan(CraftPlan.Station.STONECUTTER, target, count, crafts, perCraft, cells));
        }

        if (rejected.isEmpty()) return fail("切石机配方里没有能切出 " + name(target) + " 的");
        return fail("这些配方都不行：" + String.join("；", rejected));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 主背包 27 格（不含快捷栏）里每种物品有多少。 */
    public static Map<Item, Integer> inventoryCounts(ClientPlayerEntity player) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (i >= 9 && i < 36) {   // 主背包
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** 从可接受的物品里挑一个：扣除这次要用的量之后还剩最多的那个。 */
    @Nullable
    private static Item choose(List<ItemStack> accepted, Map<Item, Integer> left, int crafts) {
        Item best = null;
        int bestLeft = -1;
        for (ItemStack option : accepted) {
            Item item = option.getItem();
            int remaining = left.getOrDefault(item, 0) - crafts;
            if (remaining < 0) continue;
            if (remaining > bestLeft) {
                bestLeft = remaining;
                best = item;
            }
        }
        return best;
    }

    @Nullable
    private static Item chooseInput(Ingredient ingredient, Map<Item, Integer> available, int crafts) {
        Item best = null;
        int bestLeft = -1;
        for (RegistryEntry<Item> entry : ingredient.getMatchingItems().toList()) {
            Item item = entry.value();
            int remaining = available.getOrDefault(item, 0) - crafts;
            if (remaining < 0) continue;
            if (remaining > bestLeft) {
                bestLeft = remaining;
                best = item;
            }
        }
        return best;
    }

    @Nullable
    private static ItemStack firstMatch(List<ItemStack> options, Item target) {
        for (ItemStack option : options) {
            if (!option.isEmpty() && option.isOf(target)) return option;
        }
        return null;
    }

    private static Result ok(CraftPlan plan) {
        return new Result(plan, null);
    }

    private static Result fail(String error) {
        return new Result(null, error);
    }

    private static String name(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    private static String acceptedNames(List<ItemStack> accepted) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < accepted.size() && i < 4; i++) {
            if (i > 0) sb.append("/");
            sb.append(name(accepted.get(i).getItem()));
        }
        if (accepted.size() > 4) sb.append("/…");
        return sb.toString();
    }

    private static String inputNames(Ingredient ingredient) {
        StringBuilder sb = new StringBuilder();
        List<RegistryEntry<Item>> items = ingredient.getMatchingItems().toList();
        for (int i = 0; i < items.size() && i < 4; i++) {
            if (i > 0) sb.append("/");
            sb.append(name(items.get(i).value()));
        }
        if (items.size() > 4) sb.append("/…");
        return sb.toString();
    }
}
