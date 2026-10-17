package org.six_coin.playerController.client.stock.materials;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.recipe.display.StonecutterRecipeDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.context.ContextParameterMap;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.recipe.LocalRecipes;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link LocalRecipes} 那份本地配方表整理成「产物 -> 能做出它的配方」，
 * 并按 {@link MaterialsConfig} 里的偏好把一条配方、以及配方里每一格用哪个物品**定下来**。
 *
 * <p>「定下来」这件事是材料清单和 {@code /pc recipe} 共用的：
 * 用户没写偏好、而同一个产物有好几种做法时**不替用户做决定**，直接报「多种合成方式」。
 * 挑配方的顺序是：
 * <ol>
 *   <li>{@code recipe_preferences} 里写了就用它（做不出这个产物的偏好会被指出）；</li>
 *   <li>否则切石机配方**唯一**就用它（切石机优先，一次只吃一个材料）；</li>
 *   <li>否则工作台配方唯一就用它；</li>
 *   <li>都不唯一 → 报错，让用户写偏好。</li>
 * </ol>
 *
 * <p>配方里的一格有三种可能：一个具体物品、一个标签（{@code #minecraft:planks}）、
 * 一串可选的物品（原版里火把的煤炭/木炭、TNT 的沙子/红沙、黑色羊毛的任意羊毛都是这种）。
 * 后两种都要查偏好（标签查 {@code tag_preferences}，匿名列表查 {@code ingredient_preferences}），
 * 查不到就报错。
 */
public final class MaterialsRecipes {

    /** 配方属于哪种工作站。 */
    public enum Kind {
        CRAFTING_TABLE("crafting_table", "工作台"),
        STONECUTTER("stonecutter", "切石机");

        private final String jsonType;
        private final String display;

        Kind(String jsonType, String display) {
            this.jsonType = jsonType;
            this.display = display;
        }

        /** 写进 materials.json 的 {@code type}。 */
        public String jsonType() {
            return jsonType;
        }

        public String display() {
            return display;
        }
    }

    /**
     * 配方里的一格。
     *
     * @param tag   这一格写的是标签（{@code #minecraft:planks}）；不是标签就是 null
     * @param items 这一格能放的具体物品（标签格是空的）
     */
    public record Slot(@Nullable TagKey<Item> tag, List<Item> items) {

        public static final Slot EMPTY = new Slot(null, List.of());

        public boolean isEmpty() {
            return tag == null && items.isEmpty();
        }

        /** 要用户决定的「一串可选物品」格。 */
        public boolean isChoice() {
            return tag == null && items.size() > 1;
        }
    }

    /**
     * 一条能用来追溯的配方。
     *
     * @param slots  和显示数据里的格子一一对应（有形状的配方包含空格子，下标就是格子位置）
     * @param width  有形状配方的宽度（摆进 3×3 时用）
     */
    public record Recipe(Identifier id, Kind kind, int perCraft, boolean shaped, int width,
                         List<Slot> slots) {

        /** {@code crafting_shaped} / {@code crafting_shapeless} / {@code stonecutting}。 */
        public String recipeType() {
            if (kind == Kind.STONECUTTER) return "stonecutting";
            return shaped ? "crafting_shaped" : "crafting_shapeless";
        }

        /**
         * 这个配方里第 {@code index} 格的键（只对「一串可选物品」格有意义）。
         *
         * <p>同一组候选物品不管重复用了几格都算同一处，所以键是
         * {@code <配方 id>#<这一组候选物品在配方里第几次出现>}。
         */
        public String slotKey(int index) {
            List<List<Item>> seen = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (!slot.isChoice()) continue;

                int n = seen.indexOf(slot.items());
                if (n < 0) {
                    seen.add(slot.items());
                    n = seen.size() - 1;
                }
                if (i == index) return id + "#" + n;
            }
            return id + "#0";
        }

        /** 第 {@code n} 处「一串可选物品」格子能放的物品；找不到返回空表。 */
        public List<Item> choiceItems(int n) {
            List<List<Item>> seen = new ArrayList<>();
            for (Slot slot : slots) {
                if (!slot.isChoice()) continue;
                int index = seen.indexOf(slot.items());
                if (index < 0) {
                    seen.add(slot.items());
                    index = seen.size() - 1;
                }
                if (index == n) return slot.items();
            }
            return List.of();
        }
    }

    /** 挑不出来时是哪一种问题。 */
    public enum ProblemKind {
        /** 同一个产物有好几种做法，而用户没写偏好。 */
        AMBIGUOUS,
        /** 偏好里写的那条配方做不出这个产物。 */
        BAD_PREFERENCE,
        /** 配方里有标签物品，而用户没说用标签里的哪个。 */
        UNDECIDED_TAG,
        /** 配方里有一串可选的物品，而用户没说用哪个。 */
        UNDECIDED_INGREDIENT
    }

    /**
     * 为什么挑不出来。
     *
     * @param slotKey 只有 {@link ProblemKind#UNDECIDED_INGREDIENT} 用得上
     * @param tag     只有 {@link ProblemKind#UNDECIDED_TAG} 用得上
     * @param items   {@link ProblemKind#UNDECIDED_INGREDIENT} 的候选物品
     * @param recipe  只有 {@link ProblemKind#BAD_PREFERENCE} 用得上
     */
    public record Problem(ProblemKind kind, @Nullable String slotKey, @Nullable TagKey<Item> tag,
                          List<Item> items, @Nullable Identifier recipe) {
    }

    /**
     * 挑配方 + 定格子的结果。
     *
     * @param recipe 定下来的配方；null 表示「不用追溯」（没配方，或者有问题）
     * @param picked 每一格用哪个物品，和 {@link Recipe#slots()} 一一对应（空格子是 null）
     */
    public record Decision(@Nullable Recipe recipe, @Nullable Problem problem, List<Item> picked) {

        public boolean ok() {
            return recipe != null;
        }

        public boolean noRecipe() {
            return recipe == null && problem == null;
        }
    }

    private final Map<Item, List<Recipe>> byResult = new LinkedHashMap<>();
    private final Map<Identifier, Recipe> byId = new LinkedHashMap<>();

    private MaterialsRecipes() {
    }

    // ------------------------------------------------------------------
    // 建表
    // ------------------------------------------------------------------

    /** 拿当前的本地配方表整理一份「产物 -> 配方」。 */
    public static MaterialsRecipes build(MinecraftClient mc) {
        MaterialsRecipes index = new MaterialsRecipes();
        if (mc.world == null) return index;

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        Set<String> seen = new LinkedHashSet<>();

        for (LocalRecipes.LocalRecipe local : LocalRecipes.recipes(mc)) {
            Recipe recipe = recipeOf(local, context);
            if (recipe == null) continue;

            List<Item> results = resultsOf(local.display(), context);
            if (results.isEmpty()) continue;

            for (Item result : results) {
                String key = result + "|" + recipe.id();
                if (!seen.add(key)) continue;   // 一条配方可能给出多个显示数据，只留一条
                index.byResult.computeIfAbsent(result, ignored -> new ArrayList<>()).add(recipe);
            }
            index.byId.putIfAbsent(recipe.id(), recipe);
        }
        return index;
    }

    @Nullable
    private static Recipe recipeOf(LocalRecipes.LocalRecipe local, ContextParameterMap context) {
        RecipeDisplay display = local.display();

        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            List<Slot> slots = new ArrayList<>();
            for (SlotDisplay slot : shaped.ingredients()) slots.add(slotOf(slot));
            return new Recipe(local.id(), Kind.CRAFTING_TABLE, perCraft(display, context),
                true, Math.max(1, shaped.width()), List.copyOf(slots));
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            List<Slot> slots = new ArrayList<>();
            for (SlotDisplay slot : shapeless.ingredients()) slots.add(slotOf(slot));
            return new Recipe(local.id(), Kind.CRAFTING_TABLE, perCraft(display, context),
                false, 3, List.copyOf(slots));
        }
        if (display instanceof StonecutterRecipeDisplay stonecutter) {
            return new Recipe(local.id(), Kind.STONECUTTER, perCraft(display, context),
                false, 1, List.of(slotOf(stonecutter.input())));
        }
        return null;
    }

    /** 一次出几个。 */
    private static int perCraft(RecipeDisplay display, ContextParameterMap context) {
        for (ItemStack stack : display.result().getStacks(context)) {
            if (!stack.isEmpty()) return Math.max(1, stack.getCount());
        }
        return 1;
    }

    /** 这个配方能做出哪些东西（正常就一种）。 */
    private static List<Item> resultsOf(RecipeDisplay display, ContextParameterMap context) {
        List<Item> results = new ArrayList<>();
        for (ItemStack stack : display.result().getStacks(context)) {
            if (!stack.isEmpty() && !results.contains(stack.getItem())) results.add(stack.getItem());
        }
        return results;
    }

    /** 把一格显示数据变成「标签」或者「一串物品」。 */
    private static Slot slotOf(@Nullable SlotDisplay display) {
        if (display instanceof SlotDisplay.ItemSlotDisplay item) {
            return new Slot(null, List.of(item.item().value()));
        }
        if (display instanceof SlotDisplay.StackSlotDisplay stack) {
            ItemStack value = stack.stack();
            return value.isEmpty() ? Slot.EMPTY : new Slot(null, List.of(value.getItem()));
        }
        if (display instanceof SlotDisplay.TagSlotDisplay tag) {
            return new Slot(tag.tag(), List.of());
        }
        if (display instanceof SlotDisplay.CompositeSlotDisplay composite) {
            List<Item> items = new ArrayList<>();
            TagKey<Item> tag = null;
            for (SlotDisplay inner : composite.contents()) {
                Slot slot = slotOf(inner);
                if (slot.tag() != null && tag == null) tag = slot.tag();
                for (Item item : slot.items()) {
                    if (!items.contains(item)) items.add(item);
                }
            }
            return new Slot(tag, List.copyOf(items));
        }
        return Slot.EMPTY;   // 空格子，或者我们不管的显示类型（燃料、锻造模板之类）
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    /** 能做出这个产物的配方（配方 id 去重，按配方 id 排序的结果已经在 {@link LocalRecipes} 里排好了）。 */
    public List<Recipe> candidates(Item result) {
        return List.copyOf(byResult.getOrDefault(result, List.of()));
    }

    /** 按配方 id 找一条配方（{@code ingredient_preferences} 的键里带的就是它）。 */
    @Nullable
    public Recipe byId(Identifier id) {
        return byId.get(id);
    }

    /** 一共有几条不同的配方（空表说明本地配方表根本没读出来）。 */
    public int size() {
        return byId.size();
    }

    /** 本地配方表里的所有配方（顺序就是配方 id 的顺序）。 */
    public List<Recipe> all() {
        return List.copyOf(byId.values());
    }

    /** 建表 + 挑配方（给一次性调用用）。 */
    public static Decision decide(MinecraftClient mc, MaterialsConfig config, Item result) {
        return build(mc).decide(result, config);
    }

    /** 建表 + 挑配方，只在这个工作站里挑（{@code /pc recipe crafting_table|stonecutter} 用）。 */
    public static Decision decide(MinecraftClient mc, MaterialsConfig config, Item result,
                                  Kind only) {
        return build(mc).decide(result, config, only);
    }

    /**
     * 挑配方 + 把每一格定下来。
     *
     * @return 没配方时 {@code recipe == null && problem == null}（调用方按「原材料」处理）
     */
    public Decision decide(Item result, MaterialsConfig config) {
        return decide(result, config, null);
    }

    /**
     * 挑配方 + 把每一格定下来。
     *
     * @param only 只在某个工作站里挑；null 表示工作台和切石机一起挑（切石机优先）
     * @return 挑不出来（这个工作站没配方）时 {@code recipe == null && problem == null}
     */
    public Decision decide(Item result, MaterialsConfig config, @Nullable Kind only) {
        List<Recipe> all = candidates(result);
        if (all.isEmpty()) return new Decision(null, null, List.of());

        List<Recipe> pool = new ArrayList<>();
        for (Recipe recipe : all) {
            if (only == null || recipe.kind() == only) pool.add(recipe);
        }

        Recipe chosen = null;
        Identifier preferred = config.recipePreference(result);
        if (preferred != null) {
            for (Recipe recipe : pool) {
                if (recipe.id().equals(preferred)) {
                    chosen = recipe;
                    break;
                }
            }
            if (chosen == null) {
                return new Decision(null, new Problem(ProblemKind.BAD_PREFERENCE, null, null,
                    List.of(), preferred), List.of());
            }
        }

        if (chosen == null) {
            if (only != null) {
                if (pool.size() > 1) return new Decision(null, ambiguous(), List.of());
                if (pool.isEmpty()) return new Decision(null, null, List.of());
                chosen = pool.getFirst();
            } else {
                List<Recipe> cutting = new ArrayList<>();
                List<Recipe> crafting = new ArrayList<>();
                for (Recipe recipe : pool) {
                    (recipe.kind() == Kind.STONECUTTER ? cutting : crafting).add(recipe);
                }
                if (cutting.size() == 1) {
                    chosen = cutting.getFirst();
                } else if (cutting.size() > 1) {
                    return new Decision(null, ambiguous(), List.of());
                } else if (crafting.size() == 1) {
                    chosen = crafting.getFirst();
                } else {
                    return new Decision(null, ambiguous(), List.of());
                }
            }
        }

        // 每一格定下来
        List<Item> picked = new ArrayList<>();
        List<Slot> slots = chosen.slots();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.isEmpty()) {
                picked.add(null);
                continue;
            }
            if (slot.tag() != null) {
                // 标签里只有一个物品就直接用它，没什么可决定的
                Item item = decidedTagItem(slot.tag(), config);
                if (item == null) {
                    return new Decision(null, new Problem(ProblemKind.UNDECIDED_TAG, null,
                        slot.tag(), List.of(), null), List.of());
                }
                picked.add(item);
                continue;
            }
            if (slot.items().size() == 1) {
                picked.add(slot.items().getFirst());
                continue;
            }
            // 一串可选的物品：键是「配方 id#第几处」
            String key = chosen.slotKey(i);
            Item item = config.ingredientPreference(key);
            if (item == null || !slot.items().contains(item)) {
                return new Decision(null, new Problem(ProblemKind.UNDECIDED_INGREDIENT, key,
                    null, slot.items(), null), List.of());
            }
            picked.add(item);
        }
        // 注意：空格子对应 null，所以这里不能用 List.copyOf（它不收 null）
        return new Decision(chosen, null, Collections.unmodifiableList(picked));
    }

    private static Problem ambiguous() {
        return new Problem(ProblemKind.AMBIGUOUS, null, null, List.of(), null);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 一个标签里现在有哪些物品（按注册顺序）。 */
    public static List<Item> tagItems(TagKey<Item> tag) {
        List<Item> items = new ArrayList<>();
        try {
            for (RegistryEntry<Item> entry : Registries.ITEM.iterateEntries(tag)) {
                items.add(entry.value());
            }
        } catch (Throwable t) {
            ChatUtils.debug("读标签 " + tag.id() + " 失败：" + t);
        }
        return items;
    }

    /**
     * 这个标签现在用哪个物品。
     *
     * <ul>
     *   <li>{@code tag_preferences} 里写了就用它；</li>
     *   <li>没写、而标签里**只有一个**物品，就直接用那个（没得选，用不着问用户）；</li>
     *   <li>其余情况返回 null —— 调用方按「未决定的标签」报错。</li>
     * </ul>
     */
    @Nullable
    public static Item decidedTagItem(TagKey<Item> tag, MaterialsConfig config) {
        Item preferred = config.tagPreference(tag);
        if (preferred != null) return preferred;

        List<Item> members = tagItems(tag);
        return members.size() == 1 ? members.getFirst() : null;
    }
}
