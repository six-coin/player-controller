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
 *   <li>材料一格装不下（要合的次数超过堆叠上限）就**分几轮**：每轮摆能摆的最大值，合完再摆下一轮，
 *       不再因此拒绝执行（需求里明说的）；</li>
 *   <li>产物数量必须是单次产量的整数倍，而且不超过「快捷栏第 4~9 格的空位 × 64」
 *       （成品会被 QuickMove 到快捷栏靠后的格子）。</li>
 * </ul>
 */
public final class RecipePlanner {

    /** 成品会被 QuickMove 到快捷栏的这几格（背包下标 3~8，也就是第 4~9 格）。 */
    public static final int RESULT_HOTBAR_FROM = 3;
    public static final int RESULT_HOTBAR_TO = 8;

    /** 规划结果：成功了给计划，失败了给一句为什么。 */
    public record Result(@Nullable CraftPlan plan, @Nullable String error) {
        public boolean ok() {
            return plan != null;
        }
    }

    /**
     * 一个候选配方：单次产量 + 每个格子可以放哪些物品（空列表 = 空格子）。
     *
     * @param shaped 有形状的配方要按 width 摆进 3×3；无序配方就一格一个
     */
    public record Option(String recipeType, int perCraft, boolean shaped, int width,
                         List<List<Item>> cells) {
    }

    private RecipePlanner() {
    }

    // ------------------------------------------------------------------
    // 候选配方
    // ------------------------------------------------------------------

    /** 配方书里所有能做出 {@code target} 的工作台配方。 */
    public static List<Option> craftingOptions(Item target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return List.of();

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        List<RecipeDisplayEntry> entries = new ArrayList<>();
        for (RecipeResultCollection collection : player.getRecipeBook().getOrderedResults()) {
            entries.addAll(collection.getAllRecipes());
        }

        List<Option> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (RecipeDisplayEntry entry : entries) {
            RecipeDisplay display = entry.display();

            List<SlotDisplay> ingredients;
            boolean shaped;
            int width;
            String recipeType;
            if (display instanceof ShapedCraftingRecipeDisplay shapedDisplay) {
                shaped = true;
                ingredients = shapedDisplay.ingredients();
                width = Math.max(1, shapedDisplay.width());
                recipeType = "crafting_shaped";
            } else if (display instanceof ShapelessCraftingRecipeDisplay shapelessDisplay) {
                shaped = false;
                ingredients = shapelessDisplay.ingredients();
                width = 3;
                recipeType = "crafting_shapeless";
            } else {
                continue;
            }

            ItemStack result = display.result().getFirst(context);
            if (result.isEmpty() || !result.isOf(target)) continue;
            if (!seen.add(entry.id() + " " + ingredients.size())) continue;

            List<List<Item>> cells = new ArrayList<>();
            for (SlotDisplay slot : ingredients) {
                List<Item> accepted = new ArrayList<>();
                for (ItemStack option : slot.getStacks(context)) {
                    if (!option.isEmpty()) accepted.add(option.getItem());
                }
                cells.add(accepted);
            }

            options.add(new Option(recipeType, Math.max(1, result.getCount()), shaped, width, cells));
        }
        return options;
    }

    /** 服务器同步过来的、能切出 {@code target} 的切石机配方。 */
    public static List<Option> stonecuttingOptions(Item target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.getNetworkHandler() == null) return List.of();

        RecipeManager manager = mc.getNetworkHandler().getRecipeManager();
        if (manager == null) return List.of();
        CuttingRecipeDisplay.Grouping<StonecuttingRecipe> grouping = manager.getStonecutterRecipes();
        if (grouping == null || grouping.isEmpty()) return List.of();

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        List<Option> options = new ArrayList<>();

        for (CuttingRecipeDisplay.GroupEntry<StonecuttingRecipe> entry : grouping.entries()) {
            ItemStack result = firstMatch(entry.recipe().optionDisplay().getStacks(context), target);
            if (result == null) continue;

            List<Item> accepted = new ArrayList<>();
            for (RegistryEntry<Item> item : entry.input().getMatchingItems().toList()) {
                accepted.add(item.value());
            }
            if (accepted.isEmpty()) continue;

            options.add(new Option("stonecutting", Math.max(1, result.getCount()),
                false, 1, List.of(accepted)));
        }
        return options;
    }

    // ------------------------------------------------------------------
    // 工作台
    // ------------------------------------------------------------------

    public static Result planCrafting(Item target, int count) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || MinecraftClient.getInstance().world == null) return fail("没有玩家或世界");
        if (count < 1) return fail("数量至少 1");

        int maxCount = maxResultCount(player);
        if (count > maxCount) {
            return fail("数量最多 " + maxCount + "（快捷栏第 4~9 格空着 "
                + hotbarFreeSlots(player) + " 格，每格最多 64 个）");
        }

        List<Option> options = craftingOptions(target);
        if (options.isEmpty()) {
            return fail("配方书里没有能做出 " + name(target) + " 的配方（没解锁的配方客户端看不到）");
        }

        Map<Item, Integer> available = inventoryCounts(player);
        List<String> rejected = new ArrayList<>();

        for (Option option : options) {
            CraftPlan plan = planFromOption(CraftPlan.Station.CRAFTING_TABLE, option, target,
                count, available, rejected);
            if (plan != null) return ok(plan);
        }

        return fail("这些配方都不行：" + String.join("；", rejected));
    }

    // ------------------------------------------------------------------
    // 切石机
    // ------------------------------------------------------------------

    public static Result planStonecutting(Item target, int count) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || MinecraftClient.getInstance().world == null) return fail("没有玩家或世界");
        if (count < 1) return fail("数量至少 1");

        int maxCount = maxResultCount(player);
        if (count > maxCount) {
            return fail("数量最多 " + maxCount + "（快捷栏第 4~9 格空着 "
                + hotbarFreeSlots(player) + " 格，每格最多 64 个）");
        }

        List<Option> options = stonecuttingOptions(target);
        if (options.isEmpty()) {
            return fail("切石机配方里没有能切出 " + name(target) + " 的（或者还没连上服务器）");
        }

        Map<Item, Integer> available = inventoryCounts(player);
        List<String> rejected = new ArrayList<>();

        for (Option option : options) {
            CraftPlan plan = planFromOption(CraftPlan.Station.STONECUTTER, option, target,
                count, available, rejected);
            if (plan != null) return ok(plan);
        }

        return fail("这些配方都不行：" + String.join("；", rejected));
    }

    // ------------------------------------------------------------------
    // 把一个候选配方变成计划
    // ------------------------------------------------------------------

    @Nullable
    private static CraftPlan planFromOption(CraftPlan.Station station, Option option, Item target,
                                            int count, Map<Item, Integer> available,
                                            List<String> rejected) {
        int perCraft = option.perCraft();
        if (perCraft <= 0) return null;
        if (count % perCraft != 0) {
            rejected.add(name(target) + " 一次 " + perCraft + " 个，" + count + " 除不尽");
            return null;
        }

        int crafts = count / perCraft;
        Map<Item, Integer> left = new HashMap<>(available);
        List<CraftPlan.Cell> cells = new ArrayList<>();
        int roundCrafts = Integer.MAX_VALUE;

        for (int i = 0; i < option.cells().size(); i++) {
            List<Item> accepted = option.cells().get(i);
            if (accepted.isEmpty()) continue;   // 空格子

            int gridSlot = option.shaped()
                ? (i / option.width()) * 3 + (i % option.width())
                : i;
            if (gridSlot >= 9) {
                rejected.add("这个配方有 " + option.cells().size() + " 格，3×3 摆不下");
                return null;
            }

            Item chosen = choose(accepted, left, crafts);
            if (chosen == null) {
                rejected.add(acceptedNames(accepted) + " 不够（要 " + crafts + " 个）");
                return null;
            }

            left.put(chosen, left.getOrDefault(chosen, 0) - crafts);
            cells.add(new CraftPlan.Cell(gridSlot, chosen));
            roundCrafts = Math.min(roundCrafts, Math.max(1, chosen.getMaxCount()));
        }

        if (cells.isEmpty()) return null;
        return new CraftPlan(station, target, count, perCraft, crafts, roundCrafts, cells);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 某个配方单次合成出几个（生成第二部分的 final_steps 时，要把数量取整到它的整数倍）。
     *
     * @param recipeType {@code stonecutting} 走切石机配方，其它走工作台配方
     * @return 查不到返回 0
     */
    public static int perCraft(@Nullable String recipeType, Item item) {
        List<Option> options = recipeType != null && recipeType.startsWith("stonecutting")
            ? stonecuttingOptions(item)
            : craftingOptions(item);

        int best = 0;
        for (Option option : options) {
            best = Math.max(best, option.perCraft());
        }
        return best;
    }

    /** 快捷栏第 4~9 格（背包下标 3~8）空着几格。 */
    public static int hotbarFreeSlots(ClientPlayerEntity player) {
        int free = 0;
        for (int i = RESULT_HOTBAR_FROM; i <= RESULT_HOTBAR_TO; i++) {
            if (player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    /** 一次最多能做多少个：快捷栏第 4~9 格的空位 × 64。 */
    public static int maxResultCount(ClientPlayerEntity player) {
        return hotbarFreeSlots(player) * 64;
    }

    /** 主背包 27 格（不含快捷栏）里每种物品有多少。 */
    public static Map<Item, Integer> inventoryCounts(ClientPlayerEntity player) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** 从可接受的物品里挑一个：扣除这次要用的量之后还剩最多的那个。 */
    @Nullable
    public static Item choose(List<Item> accepted, Map<Item, Integer> left, int crafts) {
        Item best = null;
        int bestLeft = -1;
        for (Item item : accepted) {
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

    private static String acceptedNames(List<Item> accepted) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < accepted.size() && i < 4; i++) {
            if (i > 0) sb.append("/");
            sb.append(name(accepted.get(i)));
        }
        if (accepted.size() > 4) sb.append("/…");
        return sb.toString();
    }

    /** 日志用：这个物品一格最多叠多少。 */
    public static int maxStackOf(Item item) {
        return Math.max(1, item.getMaxCount());
    }

    /** 切石机的输入（{@link Ingredient}）名字，错误信息用。 */
    public static String inputNames(Ingredient ingredient) {
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
