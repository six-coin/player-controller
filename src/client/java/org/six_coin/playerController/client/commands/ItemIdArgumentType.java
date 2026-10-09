package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 一个物品 id 参数：支持 {@code minecraft:oak_planks} 这种带冒号的写法，也支持省略命名空间。
 *
 * <p>原版那几个物品参数类型都要注册表访问，客户端命令用不了，所以自己写一个；
 * 解析时一路读到空格为止（比 brigadier 自带的 unquoted string 宽松，允许冒号）。
 */
public final class ItemIdArgumentType implements ArgumentType<Item> {

    private static final SimpleCommandExceptionType UNKNOWN =
        new SimpleCommandExceptionType(Text.literal("没有这个物品"));

    private static final ItemIdArgumentType INSTANCE = new ItemIdArgumentType();

    public static ItemIdArgumentType itemId() {
        return INSTANCE;
    }

    public static Item getItem(CommandContext<?> context, String name) {
        return context.getArgument(name, Item.class);
    }

    @Override
    public Item parse(StringReader reader) throws CommandSyntaxException {
        String raw = readId(reader);
        Identifier identifier = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (identifier == null) throw UNKNOWN.createWithContext(reader);

        Optional<Item> item = Registries.ITEM.getOptionalValue(identifier);
        if (item.isEmpty() || item.get() == Items.AIR) throw UNKNOWN.createWithContext(reader);
        return item.get();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Identifier id : Registries.ITEM.getIds()) {
            String full = id.toString();
            // 只从开头匹配；没打冒号的话，也可以从冒号后面（路径部分）开始匹配
            boolean match = full.startsWith(remaining)
                || (!remaining.contains(":") && id.getPath().startsWith(remaining));
            if (match) builder.suggest(full);
        }
        return builder.buildFuture();
    }

    private static String readId(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();

        String id = reader.getString().substring(start, reader.getCursor());
        if (id.isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol()
                .createWithContext(reader, "物品 id");
        }
        return id;
    }
}
