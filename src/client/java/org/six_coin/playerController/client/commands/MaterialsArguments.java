package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.six_coin.playerController.client.stock.materials.MaterialsConfig;
import org.six_coin.playerController.client.stock.materials.MaterialsRecipes;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /pc material ...} 专用的三个参数类型。
 *
 * <p>都属于「一直读到空格为止」（配方 id、标签、{@code 配方#第几处} 里都有冒号、井号这种
 * brigadier 自带参数类型不认的字符），而且都带自动填充。
 */
public final class MaterialsArguments {

    private MaterialsArguments() {
    }

    /** 一直读到空格为止的那一段。 */
    private static String readToken(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();

        String token = reader.getString().substring(start, reader.getCursor());
        if (token.isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol()
                .createWithContext(reader, "一个值");
        }
        return token;
    }

    // ------------------------------------------------------------------
    // 配方 id
    // ------------------------------------------------------------------

    /** 一条配方的 id（{@code minecraft:mossy_cobblestone_from_moss_block}）。 */
    public static final class Recipe implements ArgumentType<Identifier> {

        private static final SimpleCommandExceptionType UNKNOWN =
            new SimpleCommandExceptionType(Component.literal("配方名要写成 minecraft:配方名 这样"));

        private static final Recipe INSTANCE = new Recipe();

        public static Recipe recipe() {
            return INSTANCE;
        }

        public static Identifier get(CommandContext<?> context, String name) {
            return context.getArgument(name, Identifier.class);
        }

        @Override
        public Identifier parse(StringReader reader) throws CommandSyntaxException {
            String raw = readToken(reader);
            String canonical = MaterialsConfig.canonicalRecipe(raw);
            Identifier id = canonical == null ? null : Identifier.tryParse(canonical);
            if (id == null) throw UNKNOWN.createWithContext(reader);
            return id;
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                                  SuggestionsBuilder builder) {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (Identifier id : candidateIds(context)) {
                if (id.toString().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(id.toString());
                }
            }
            return builder.buildFuture();
        }

        /** 上一个参数（产物）能用的配方；拿不到就一条都不提示。 */
        private static List<Identifier> candidateIds(CommandContext<?> context) {
            Item result = argument(context, "result_item");
            if (result == null) return List.of();

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return List.of();

            return MaterialsRecipes.build(mc).candidates(result).stream()
                .map(MaterialsRecipes.Recipe::id)
                .toList();
        }
    }

    // ------------------------------------------------------------------
    // 物品标签
    // ------------------------------------------------------------------

    /** 一个物品标签（{@code #minecraft:planks}）。 */
    public static final class Tag implements ArgumentType<TagKey<Item>> {

        private static final SimpleCommandExceptionType UNKNOWN =
            new SimpleCommandExceptionType(Component.literal("标签要写成 #minecraft:planks 这样"));

        private static final Tag INSTANCE = new Tag();

        public static Tag tag() {
            return INSTANCE;
        }

        @SuppressWarnings("unchecked")
        public static TagKey<Item> get(CommandContext<?> context, String name) {
            return (TagKey<Item>) context.getArgument(name, TagKey.class);
        }

        @Override
        public TagKey<Item> parse(StringReader reader) throws CommandSyntaxException {
            String raw = readToken(reader);
            TagKey<Item> tag = MaterialsConfig.tagOf(raw);
            if (tag == null) throw UNKNOWN.createWithContext(reader);
            return tag;
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                                  SuggestionsBuilder builder) {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            try {
                for (HolderSet.Named<Item> named : BuiltInRegistries.ITEM.getTags().toList()) {
                    String full = MaterialsConfig.tagId(named.key());
                    if (full.toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(full);
                }
            } catch (Throwable ignored) {
                // 标签表还没加载出来就算了，反正用户也可以手打
            }
            return builder.buildFuture();
        }
    }

    // ------------------------------------------------------------------
    // 「配方#第几处」
    // ------------------------------------------------------------------

    /** {@code ingredient_preferences} 的键：{@code <配方 id>#<第几处>}。 */
    public static final class SlotKey implements ArgumentType<String> {

        private static final SimpleCommandExceptionType UNKNOWN =
            new SimpleCommandExceptionType(Component.literal("要写成 minecraft:torch#0 这样"));

        private static final SlotKey INSTANCE = new SlotKey();

        public static SlotKey slotKey() {
            return INSTANCE;
        }

        public static String get(CommandContext<?> context, String name) {
            return context.getArgument(name, String.class);
        }

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            String raw = readToken(reader);
            String canonical = MaterialsConfig.canonicalIngredientKey(raw);
            if (canonical == null) throw UNKNOWN.createWithContext(reader);
            return canonical;
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                                  SuggestionsBuilder builder) {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return builder.buildFuture();

            MaterialsRecipes index = MaterialsRecipes.build(mc);
            java.util.Set<String> suggested = new java.util.LinkedHashSet<>();
            for (MaterialsRecipes.Recipe recipe : index.all()) {
                for (int i = 0; i < recipe.slots().size(); i++) {
                    if (!recipe.slots().get(i).isChoice()) continue;
                    String key = recipe.slotKey(i);
                    if (key.toLowerCase(Locale.ROOT).startsWith(remaining)) suggested.add(key);
                }
            }
            for (String key : suggested) builder.suggest(key);
            return builder.buildFuture();
        }
    }

    /** 从上下文里悄悄取一个物品参数（没有就算了）。 */
    private static Item argument(CommandContext<?> context, String name) {
        try {
            return context.getArgument(name, Item.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
