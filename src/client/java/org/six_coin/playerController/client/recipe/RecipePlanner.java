package org.six_coin.playerController.client.recipe;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.stock.materials.MaterialsConfig;
import org.six_coin.playerController.client.stock.materials.MaterialsRecipes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方规划：从**本地完整配方表**（{@link LocalRecipes}）里按用户定好的偏好
 * （{@code config/player-controller/materials/recipe_preferences.json}、
 * {@code tag_preferences.json}、{@code ingredient_preferences.json}）找出目标物品的那一条配方，
 * 检查材料够不够、能不能一次摆到格子里，最后给出一份 {@link CraftPlan}。
 *
 * <p>几点说明：
 * <ul>
 *   <li>配方不再「看背包里有什么就猜哪条」：同一个产物有好几种做法时，
 *       用哪一条由偏好决定（见 {@link MaterialsRecipes#decide}）；
 *       {@code /pc recipe crafting_table} 只在工作台配方里挑，{@code stonecutter} 只在切石机配方里挑；</li>
 *   <li>配方里的标签格 / 「一串可选物品」格也用偏好定死了（标签格不在偏好里就直接报错，
 *       不会拿背包里碰巧有的那个顶上）；</li>
 *   <li>材料只看主背包 27 格（不含快捷栏）；</li>
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

    /** 能做出 {@code target} 的工作台配方：定下来的那一条（没定下来就是空的）。 */
    public static List<Option> craftingOptions(Item target) {
        MaterialsRecipes.Decision decision = decide(target, MaterialsRecipes.Kind.CRAFTING_TABLE);
        if (!decision.ok()) return List.of();
        return List.of(optionOf(decision));
    }

    /** 能切出 {@code target} 的切石机配方：定下来的那一条（没定下来就是空的）。 */
    public static List<Option> stonecuttingOptions(Item target) {
        MaterialsRecipes.Decision decision = decide(target, MaterialsRecipes.Kind.STONECUTTER);
        if (!decision.ok()) return List.of();
        return List.of(optionOf(decision));
    }

    /** 一条定下来的配方 -> 一个候选项（每一格都是唯一物品，或者空格子）。 */
    private static Option optionOf(MaterialsRecipes.Decision decision) {
        MaterialsRecipes.Recipe recipe = decision.recipe();
        List<List<Item>> cells = new ArrayList<>();
        for (Item picked : decision.picked()) {
            cells.add(picked == null ? List.of() : List.of(picked));
        }
        return new Option(recipe.recipeType(), recipe.perCraft(), recipe.shaped(), recipe.width(),
            cells);
    }

    /** 按偏好挑配方。 */
    private static MaterialsRecipes.Decision decide(Item target, MaterialsRecipes.Kind only) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return MaterialsRecipes.decide(mc, MaterialsConfig.load(), target, only);
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

        MaterialsRecipes.Decision decision = decide(target, MaterialsRecipes.Kind.CRAFTING_TABLE);
        if (!decision.ok()) return fail(describeProblem(decision, target, "工作台"));

        return planFromOptions(List.of(optionOf(decision)), CraftPlan.Station.CRAFTING_TABLE,
            target, count, player, maxCount);
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

        MaterialsRecipes.Decision decision = decide(target, MaterialsRecipes.Kind.STONECUTTER);
        if (!decision.ok()) return fail(describeProblem(decision, target, "切石机"));

        return planFromOptions(List.of(optionOf(decision)), CraftPlan.Station.STONECUTTER,
            target, count, player, maxCount);
    }

    // ------------------------------------------------------------------
    // 把候选配方变成计划
    // ------------------------------------------------------------------

    private static Result planFromOptions(List<Option> options, CraftPlan.Station station,
                                          Item target, int count, ClientPlayerEntity player,
                                          int maxCount) {
        Map<Item, Integer> available = inventoryCounts(player);
        List<String> rejected = new ArrayList<>();

        for (Option option : options) {
            CraftPlan plan = planFromOption(station, option, target, count, available, rejected);
            if (plan == null) continue;
            if (plan.actualCount() > maxCount) {
                rejected.add("向上取整到 " + plan.actualCount() + " 个，超过快捷栏第 4~9 格能装的 "
                    + maxCount + " 个");
                continue;
            }
            return ok(plan);
        }

        return fail("这个配方不行：" + String.join("；", rejected));
    }

    /**
     * 这个产物的配方定下来没有；没定下来返回一句为什么（定下来了返回 null）。
     *
     * <p>给「已经知道材料有哪些、只是没配出配方」的调用方用来拼报错
     * （比如备货第二部分阶段3 的 {@code MaxCraft}）。
     */
    @Nullable
    public static String decisionProblem(Item target, MaterialsRecipes.Kind only) {
        MaterialsRecipes.Decision decision = decide(target, only);
        if (decision.ok()) return null;
        return describeProblem(decision, target, only.display());
    }

    /** 一条定下来的配方要用的材料（每一格一个物品，空的格子不算）。 */
    public static List<Item> recipeMaterials(Item target, MaterialsRecipes.Kind only) {
        MaterialsRecipes.Decision decision = decide(target, only);
        if (!decision.ok()) return List.of();

        List<Item> items = new ArrayList<>();
        for (Item picked : decision.picked()) {
            if (picked != null && !items.contains(picked)) items.add(picked);
        }
        return items;
    }

    /** 挑不出配方时给用户一句能照做的话。 */
    private static String describeProblem(MaterialsRecipes.Decision decision, Item target,
                                          String station) {        MaterialsRecipes.Problem problem = decision.problem();
        if (problem == null) {
            return "本地配方表里没有能用" + station + "做出 " + name(target) + " 的配方";
        }
        return switch (problem.kind()) {
            case AMBIGUOUS -> name(target) + " 有好几种" + station + "的做法，先选一条：/pc materials "
                + "recipe_preferences show " + name(target);
            case BAD_PREFERENCE -> "recipe_preferences 里把 " + name(target) + " 定成了 "
                + problem.recipe() + "，但这条配方做不出它、也不是" + station + "配方（/pc materials "
                + "recipe_preferences show " + name(target) + " 看有哪些）";
            case UNDECIDED_TAG -> "配方里的标签 " + MaterialsConfig.tagId(problem.tag())
                + " 还没定用哪个物品（/pc materials tag_preferences show "
                + MaterialsConfig.tagId(problem.tag()) + "）";
            case UNDECIDED_INGREDIENT -> "配方里的 " + problem.slotKey()
                + " 这一格有好几种可选物品，还没定用哪个（/pc materials ingredient_preferences show "
                + problem.slotKey() + "）";
        };
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

        // 数量不是单次产量的整数倍就**向上取整**（要 53 个栅栏、一次出 3 个 → 合 18 次 = 54 个）。
        // 多出来的那部分由调用方处理（备货里 final=true 的步骤会把多余的放回 item_storage）。
        int crafts = (count + perCraft - 1) / perCraft;
        if (crafts <= 0) return null;

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
