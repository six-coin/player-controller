package org.six_coin.playerController.client.attack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一份攻击配置（{@code config/player-controller/attack/<名字>.json}）。
 *
 * <pre>
 * {
 *   "auto_switch": true,
 *   "default_ignore_named": true,
 *   "default_ignore_passive": true,
 *   "default_ignore_tamed": true,
 *   "default_ignore_baby": false,
 *   "default_critical": false,
 *   "entities": [
 *     { "entitie_id": "minecraft:enderman", "critical": true }
 *   ]
 * }
 * </pre>
 *
 * <p>上面那六个 {@code default_*} / {@code auto_switch} 永远有值；实体里的四个
 * {@code ignore_*} 和 {@code critical} **可以不写**，不写就是「跟着默认走」（显示成「默认」）。
 *
 * <p>只有出现在 {@code entities} 里的生物才会被打。
 */
public final class AttackProfile {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** 建一份新的 profile 时用的文件名。 */
    public static final String DEFAULT_NAME = "default";

    // ------------------------------------------------------------------
    // 可以「默认 / 开 / 关」的那些开关
    // ------------------------------------------------------------------

    /** 一个既能写在实体上、又能写成 profile 默认值的开关。 */
    public enum Field {

        IGNORE_NAMED("ignore_named", "忽略命名"),
        IGNORE_PASSIVE("ignore_passive", "忽略被动"),
        IGNORE_TAMED("ignore_tamed", "忽略驯服"),
        IGNORE_BABY("ignore_baby", "忽略幼体"),
        CRITICAL("critical", "暴击");

        private final String id;
        private final String label;

        Field(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** 写在实体上的键。 */
        public String id() {
            return id;
        }

        /** 写在 profile 上的键（默认值）。 */
        public String defaultId() {
            return "default_" + id;
        }

        public String label() {
            return label;
        }

        /** 这一条实体上写的值；null = 没写，跟着默认走。 */
        @Nullable
        public Boolean get(Rule rule) {
            return switch (this) {
                case IGNORE_NAMED -> rule.ignoreNamed;
                case IGNORE_PASSIVE -> rule.ignorePassive;
                case IGNORE_TAMED -> rule.ignoreTamed;
                case IGNORE_BABY -> rule.ignoreBaby;
                case CRITICAL -> rule.critical;
            };
        }

        public void set(Rule rule, @Nullable Boolean value) {
            switch (this) {
                case IGNORE_NAMED -> rule.ignoreNamed = value;
                case IGNORE_PASSIVE -> rule.ignorePassive = value;
                case IGNORE_TAMED -> rule.ignoreTamed = value;
                case IGNORE_BABY -> rule.ignoreBaby = value;
                case CRITICAL -> rule.critical = value;
            }
        }

        /** profile 上那个默认值。 */
        public boolean getDefault(AttackProfile profile) {
            return switch (this) {
                case IGNORE_NAMED -> profile.defaultIgnoreNamed;
                case IGNORE_PASSIVE -> profile.defaultIgnorePassive;
                case IGNORE_TAMED -> profile.defaultIgnoreTamed;
                case IGNORE_BABY -> profile.defaultIgnoreBaby;
                case CRITICAL -> profile.defaultCritical;
            };
        }

        public void setDefault(AttackProfile profile, boolean value) {
            switch (this) {
                case IGNORE_NAMED -> profile.defaultIgnoreNamed = value;
                case IGNORE_PASSIVE -> profile.defaultIgnorePassive = value;
                case IGNORE_TAMED -> profile.defaultIgnoreTamed = value;
                case IGNORE_BABY -> profile.defaultIgnoreBaby = value;
                case CRITICAL -> profile.defaultCritical = value;
            }
        }

        // --------------------------------------------------------------
        // 「默认 / 开 / 关」这三态怎么表示
        // --------------------------------------------------------------

        /** 显示成「开 / 关 / 默认」。 */
        public static String text(@Nullable Boolean value) {
            if (value == null) return "默认";
            return value ? "开" : "关";
        }

        /** 显示成「开 / 关」（没有「默认」这一态的时候用，例如 auto_switch）。 */
        public static String text(boolean value) {
            return value ? "开" : "关";
        }
    }

    // ------------------------------------------------------------------
    // 一条实体
    // ------------------------------------------------------------------

    /** {@code entities} 里的一条。全部 {@code Boolean} 都可以是 null（= 跟着默认走）。 */
    public static final class Rule {

        @SerializedName("entitie_id")
        private String entitieId;

        @SerializedName("ignore_named")
        private Boolean ignoreNamed;

        @SerializedName("ignore_passive")
        private Boolean ignorePassive;

        @SerializedName("ignore_tamed")
        private Boolean ignoreTamed;

        @SerializedName("ignore_baby")
        private Boolean ignoreBaby;

        @SerializedName("critical")
        private Boolean critical;

        private Rule() {
        }

        public Rule(String entitieId) {
            this.entitieId = entitieId;
        }

        public String entitieId() {
            return entitieId;
        }

        /** 有没有显式设过东西（没有的话就没有「特殊策略」那一段）。 */
        public boolean hasSetting() {
            return ignoreNamed != null || ignorePassive != null
                || ignoreTamed != null || ignoreBaby != null || critical != null;
        }

        /** 显式设过的那些开关。 */
        public List<Field> settings() {
            List<Field> fields = new ArrayList<>();
            for (Field field : Field.values()) {
                if (field.get(this) != null) fields.add(field);
            }
            return fields;
        }
    }

    // ------------------------------------------------------------------
    // 文件本体
    // ------------------------------------------------------------------

    @SerializedName("auto_switch")
    private boolean autoSwitch = true;

    @SerializedName("default_ignore_named")
    private boolean defaultIgnoreNamed = true;

    @SerializedName("default_ignore_passive")
    private boolean defaultIgnorePassive = true;

    @SerializedName("default_ignore_tamed")
    private boolean defaultIgnoreTamed = true;

    @SerializedName("default_ignore_baby")
    private boolean defaultIgnoreBaby = false;

    @SerializedName("default_critical")
    private boolean defaultCritical = false;

    @SerializedName("entities")
    private List<Rule> entities = new ArrayList<>();

    /** 文件名（不含 .json）。 */
    private transient String name;

    private AttackProfile() {
    }

    // ------------------------------------------------------------------
    // 读 / 写
    // ------------------------------------------------------------------

    /**
     * 读一份 profile。
     *
     * @return 读不出来（文件坏了）返回 null —— 不拿默认值顶上去
     */
    @Nullable
    public static AttackProfile load(String name) {
        Path file = AttackProfiles.file(name);
        if (!Files.exists(file)) {
            return create(name);
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            AttackProfile profile = GSON.fromJson(reader, AttackProfile.class);
            if (profile == null) {
                ChatUtils.error("攻击配置 " + file + " 是空的，这一份用不了");
                return null;
            }
            profile.name = name;
            profile.normalize();
            ChatUtils.debug("已读取攻击配置：" + file + "（" + profile.entities.size() + " 种生物）");
            return profile;
        } catch (Exception e) {
            ChatUtils.error("读攻击配置 " + file + " 失败：" + e.getMessage()
                + "（这一份用不了，也不改它）");
            return null;
        }
    }

    /** 照默认值新建一份（文件在的时候什么都不做）。 */
    @Nullable
    public static AttackProfile create(String name) {
        AttackProfile profile = new AttackProfile();
        profile.name = name;
        if (!profile.save()) return null;
        ChatUtils.debug("已按默认值创建攻击配置："
            + AttackProfiles.file(name));
        return profile;
    }

    /** 写回文件。 */
    public boolean save() {
        Path file = AttackProfiles.file(name);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            return true;
        } catch (IOException e) {
            ChatUtils.error("保存攻击配置 " + file + " 失败：" + e.getMessage());
            return false;
        }
    }

    /** 兜一下手写 / 老文件的破地方。 */
    private void normalize() {
        if (entities == null) entities = new ArrayList<>();
        entities.removeIf(rule -> rule == null || rule.entitieId == null || rule.entitieId.isBlank());
        for (Rule rule : entities) {
            String id = rule.entitieId.trim();
            rule.entitieId = id.contains(":") ? id : "minecraft:" + id;
        }
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    /** 文件名（不含 .json）。 */
    public String name() {
        return name == null ? DEFAULT_NAME : name;
    }

    public boolean autoSwitch() {
        return autoSwitch;
    }

    public void autoSwitch(boolean value) {
        autoSwitch = value;
    }

    /** 所有实体（只读副本）。 */
    public List<Rule> rules() {
        return Collections.unmodifiableList(new ArrayList<>(entities));
    }

    public boolean isEmpty() {
        return entities.isEmpty();
    }

    /** 这个实体类型的那一条；没写在里面就返回 null（= 不打）。 */
    @Nullable
    public Rule rule(EntityType<?> type) {
        return rule(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    /** 按 id 找那一条；没写在里面就返回 null。 */
    @Nullable
    public Rule rule(String entitieId) {
        String id = entitieId.contains(":") ? entitieId : "minecraft:" + entitieId;
        for (Rule rule : entities) {
            if (id.equals(rule.entitieId)) return rule;
        }
        return null;
    }

    /** 这一条实际生效的值：自己写过就用自己写的，没写就用 profile 的默认。 */
    public boolean effective(Field field, Rule rule) {
        Boolean value = rule == null ? null : field.get(rule);
        return value == null ? field.getDefault(this) : value;
    }

    // ------------------------------------------------------------------
    // 改（改完调用方自己 save()）
    // ------------------------------------------------------------------

    public boolean addEntity(EntityType<?> type) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        if (rule(id) != null) return false;
        entities.add(new Rule(id));
        return true;
    }

    public boolean delEntity(String entitieId) {
        String id = entitieId.contains(":") ? entitieId : "minecraft:" + entitieId;
        return entities.removeIf(rule -> id.equals(rule.entitieId));
    }
}
