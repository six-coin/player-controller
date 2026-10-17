package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.attack.AttackProfile;
import org.six_coin.playerController.client.attack.EntityGroups;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /pc attack ...} 专用的两三个参数类型（实体 id、组 id）和取值枚举。
 *
 * <p>开关名和 {@code toggle|true|false} 都是命令树里的字面量，不走参数类型，
 * 这样 tab 补全和「写错了直接不认」都是白送的。
 */
public final class AttackArguments {

    private AttackArguments() {
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

    /** 没有命名空间就补 {@code minecraft:}。 */
    private static String withNamespace(String raw) {
        return raw.contains(":") ? raw : "minecraft:" + raw;
    }

    // ------------------------------------------------------------------
    // 实体 id
    // ------------------------------------------------------------------

    /**
     * 一个实体 id（{@code minecraft:zombie}）。
     *
     * <p>只检查「像不像一个 id」，不查注册表里有没有 —— 这样手改配置留下的老 id
     * 也还能用 {@code del} 删掉。
     */
    public static final class EntityId implements ArgumentType<Identifier> {

        private static final SimpleCommandExceptionType UNKNOWN =
            new SimpleCommandExceptionType(Text.literal("实体 id 要写成 minecraft:zombie 这样"));

        private static final EntityId INSTANCE = new EntityId();

        public static EntityId entityId() {
            return INSTANCE;
        }

        public static Identifier get(CommandContext<?> context, String name) {
            return context.getArgument(name, Identifier.class);
        }

        @Override
        public Identifier parse(StringReader reader) throws CommandSyntaxException {
            Identifier id = Identifier.tryParse(withNamespace(readToken(reader)));
            if (id == null) throw UNKNOWN.createWithContext(reader);
            return id;
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                                  SuggestionsBuilder builder) {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (EntityType<?> type : EntityGroups.allMobs()) {
                String full = Registries.ENTITY_TYPE.getId(type).toString();
                boolean match = full.startsWith(remaining)
                    || (!remaining.contains(":") && idPath(full).startsWith(remaining));
                if (match) builder.suggest(full);
            }
            return builder.buildFuture();
        }

        private static String idPath(String full) {
            int colon = full.indexOf(':');
            return colon < 0 ? full : full.substring(colon + 1);
        }
    }

    // ------------------------------------------------------------------
    // 组 id
    // ------------------------------------------------------------------

    /** 一个组 id（{@code hostile_mobs}）。 */
    public static final class GroupId implements ArgumentType<EntityGroups.Group> {

        private static final SimpleCommandExceptionType UNKNOWN =
            new SimpleCommandExceptionType(Text.literal("没有这个组（敌对生物是 hostile_mobs 这种）"));

        private static final GroupId INSTANCE = new GroupId();

        public static GroupId groupId() {
            return INSTANCE;
        }

        public static EntityGroups.Group get(CommandContext<?> context, String name) {
            return context.getArgument(name, EntityGroups.Group.class);
        }

        @Override
        public EntityGroups.Group parse(StringReader reader) throws CommandSyntaxException {
            EntityGroups.Group group = EntityGroups.Group.byId(readToken(reader));
            if (group == null) throw UNKNOWN.createWithContext(reader);
            return group;
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
                                                                  SuggestionsBuilder builder) {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (EntityGroups.Group group : EntityGroups.Group.values()) {
                if (group.id().startsWith(remaining)) builder.suggest(group.id());
            }
            return builder.buildFuture();
        }
    }

    // ------------------------------------------------------------------
    // toggle / true / false
    // ------------------------------------------------------------------

    /** 一个开关的新取值。 */
    public enum Toggle {

        TOGGLE("toggle"),
        ON("true"),
        OFF("false");

        private final String id;

        Toggle(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 作用在「默认 / 开 / 关」这种三态上：默认 → 开 → 关 → 默认。 */
        @Nullable
        public Boolean apply(@Nullable Boolean current) {
            return switch (this) {
                case TOGGLE -> AttackProfile.Field.cycle(current);
                case ON -> Boolean.TRUE;
                case OFF -> Boolean.FALSE;
            };
        }

        /** 作用在只有开 / 关两态的开关上。 */
        public boolean apply(boolean current) {
            return switch (this) {
                case TOGGLE -> !current;
                case ON -> true;
                case OFF -> false;
            };
        }
    }
}
