package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.attack.AttackConfig;
import org.six_coin.playerController.client.attack.AttackManager;
import org.six_coin.playerController.client.attack.AttackProfile;
import org.six_coin.playerController.client.attack.AttackProfile.Field;
import org.six_coin.playerController.client.attack.AttackProfile.Rule;
import org.six_coin.playerController.client.attack.AttackProfiles;
import org.six_coin.playerController.client.attack.EntityGroups;
import org.six_coin.playerController.client.config.PlayerControllerConfig;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.six_coin.playerController.client.commands.CommandStyle.fillButton;
import static org.six_coin.playerController.client.commands.CommandStyle.gray;
import static org.six_coin.playerController.client.commands.CommandStyle.runButton;

/**
 * {@code /pc attack ...} —— 攻击（类似 killaura，但按 profile 挑该打谁、怎么打）。
 *
 * <pre>
 * /pc attack                                                       看开关、profile、范围、当前状态
 * /pc attack toggle [true|false]                                   开 / 关（不带值就是切换）
 * /pc attack profile                                               有哪些 profile（可点切换 / 删除）
 * /pc attack profile switch_to &lt;名字&gt;                              换一份
 * /pc attack profile add &lt;名字&gt;                                   新建一份（顺便切过去）
 * /pc attack profile del &lt;名字&gt;                                   删掉一份（正在用的不能删）
 * /pc attack profile current                                       同 current list
 * /pc attack profile current list                                  当前这份的全部设置 + 攻击实体
 * /pc attack profile current auto_switch true|false
 * /pc attack profile current default_critical|default_ignore_named|default_ignore_passive|
 *                              default_ignore_tamed|default_ignore_baby true|false
 * /pc attack profile current entities add &lt;实体 id&gt;
 * /pc attack profile current entities add_batch &lt;组 id&gt;
 * /pc attack profile current entities del &lt;实体 id&gt;
 * /pc attack profile current entities del_batch &lt;组 id&gt;
 * /pc attack profile current entities edit &lt;实体 id&gt;                  看这一只的特殊配置
 * /pc attack profile current entities edit &lt;实体 id&gt; &lt;开关&gt; true|false|default
 * /pc attack profile current entities edit_batch &lt;组 id&gt; &lt;开关&gt; true|false|default
 * /pc attack show_all_mobs                                          所有生物，按组列，批量加 / 删 / 改
 * </pre>
 *
 * <p>改配置的按钮都是「填命令」，回车才生效；只有「看一眼」的按钮（{@code [编辑]}）点了直接执行。
 *
 * <p>只有 {@code /pc attack toggle} 不带值就是切换，别的开关一律明写 {@code true|false}。
 * 实体的那几个开关还多一个 {@code default}（= 这一项不写进 json，跟着 profile 的默认值走），
 * 所以它们有三种状态：默认 / 开 / 关。
 *
 * <p>按钮按当前状态给：只有开关两态的，关着给 {@code [启用]}、开着给 {@code [禁用]}；
 * 有三态的，开 / 关的时候先给 {@code [默认]} 再给另一个，已经在默认态就给开 / 关两个。
 */
public final class AttackCommand {

    /** 拼「点了会填什么命令」用的前缀。 */
    private static final String ROOT = "/pc attack ";

    /** 实体的那些命令的前缀。 */
    private static final String EDIT = ROOT + "profile current entities ";

    private AttackCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("attack")
            .executes(AttackCommand::status)
            .then(toggle())
            .then(profile())
            .then(ClientCommands.literal("show_all_mobs")
                .executes(AttackCommand::showAllMobs));
    }

    // ------------------------------------------------------------------
    // 总览
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackConfig config = AttackConfig.get();
        AttackProfile profile = config.currentProfile();
        AttackManager manager = AttackManager.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7攻击（文件："
            + AttackConfig.file(Math.max(1, config.loadedWorld())) + "）："));
        source.sendFeedback(Component.literal("  开关：" + (config.enable() ? "§a开" : "§c关")));
        source.sendFeedback(Component.literal("  当前 profile：" + config.profileName()
            + (profile == null ? " §c（读不出来）" : "（" + profile.rules().size() + " 种生物）")));
        if (config.enable()) {
            String state = manager.state().text();
            if (manager.state() == AttackManager.State.ATTACKING && manager.targetName() != null) {
                state = state + " " + manager.targetName();
            }
            source.sendFeedback(Component.literal("  状态：" + state));
        }
        source.sendFeedback(Component.literal("  范围：" + PlayerControllerConfig.getReach()
            + " 格（/pc config actions reach，和挖方块、开容器共用）"));
        source.sendFeedback(Component.literal("§7  §f/pc attack toggle [true|false]§7 - 开 / 关（不带值 = 切换）"));
        source.sendFeedback(Component.literal("§7  §f/pc attack profile§7 - 有哪些 profile、切 / 增 / 删"));
        source.sendFeedback(Component.literal("§7  §f/pc attack profile current list§7 - 改当前这一份"));
        source.sendFeedback(Component.literal("§7  §f/pc attack show_all_mobs§7 - 列所有生物，批量加 / 删 / 改"));
        return 1;
    }

    // ------------------------------------------------------------------
    // toggle
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommands.literal("toggle")
                .executes(context -> setEnable(context, !AttackConfig.get().enable()));

        builder.then(ClientCommands.literal("true")
            .executes(context -> setEnable(context, true)));
        builder.then(ClientCommands.literal("false")
            .executes(context -> setEnable(context, false)));
        return builder;
    }

    private static int setEnable(CommandContext<FabricClientCommandSource> context, boolean value) {
        FabricClientCommandSource source = context.getSource();
        AttackConfig config = AttackConfig.get();
        config.setEnable(value);

        if (!value) {
            source.sendFeedback(Component.literal("攻击已关闭"));
            return 1;
        }

        AttackProfile profile = config.currentProfile();
        if (profile == null) {
            source.sendError(Component.literal("攻击已开启，但 profile「" + config.profileName()
                + "」读不出来：" + AttackProfiles.file(config.profileName())));
            return 0;
        }

        source.sendFeedback(Component.literal("攻击已开启：profile=" + profile.name()
            + "，" + profile.rules().size() + " 种生物"));
        if (profile.isEmpty()) {
            source.sendError(Component.literal("这个 profile 里一个生物都没有，现在不会打任何东西"
                + "（/pc attack show_all_mobs 里点 [添加]）"));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // profile
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> profile() {
        return ClientCommands.literal("profile")
            .executes(AttackCommand::listProfiles)
            .then(ClientCommands.literal("switch_to")
                .then(ClientCommands.argument("name", StringArgumentType.word())
                    .suggests(AttackCommand::suggestProfiles)
                    .executes(AttackCommand::switchProfile)))
            .then(ClientCommands.literal("add")
                .then(ClientCommands.argument("name", StringArgumentType.word())
                    .executes(AttackCommand::addProfile)))
            .then(ClientCommands.literal("del")
                .then(ClientCommands.argument("name", StringArgumentType.word())
                    .suggests(AttackCommand::suggestProfiles)
                    .executes(AttackCommand::delProfile)))
            .then(current());
    }

    private static CompletableFuture<Suggestions> suggestProfiles(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : AttackProfiles.list()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static int listProfiles(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String current = AttackConfig.get().profileName();
        List<String> names = AttackProfiles.list();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7攻击 profile（" + AttackProfiles.dir()
            + "）：" + names.size() + " 份"));
        for (String name : names) {
            MutableComponent line = Component.literal(" ");
            line.append(fillButton("[切换]", ROOT + "profile switch_to " + name,
                "把「切到 " + name + "」的命令填进聊天栏，回车生效"));
            if (!name.equals(current)) {
                line.append(Component.literal(" "));
                line.append(fillButton("[删除]", ROOT + "profile del " + name,
                    "把「删掉 " + name + "」的命令填进聊天栏，回车生效"));
            }
            line.append(Component.literal(" " + name));
            if (name.equals(current)) line.append(Component.literal(" §a（当前）"));
            source.sendFeedback(line);
        }
        if (names.isEmpty()) {
            source.sendFeedback(Component.literal("§7  还没有，用 /pc attack profile add <名字> 建一份"));
        }
        source.sendFeedback(Component.literal("§7  §f/pc attack profile add <名字>§7 - 新建一份（顺便切过去）"));
        return 1;
    }

    private static int switchProfile(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        AttackConfig config = AttackConfig.get();

        if (!config.switchProfile(name)) {
            source.sendError(Component.literal("没有这个 profile：" + name + "（"
                + AttackProfiles.file(name) + "）"));
            return 0;
        }
        source.sendFeedback(Component.literal("已切换到 profile：" + name));
        return 1;
    }

    private static int addProfile(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");

        if (!AttackProfiles.isValidName(name)) {
            source.sendError(Component.literal("名字只能用字母、数字、下划线、减号（1~64 个字符），"
                + "因为它同时是文件名"));
            return 0;
        }
        if (AttackProfiles.exists(name)) {
            source.sendError(Component.literal("已经有 " + name + " 了（" + AttackProfiles.file(name) + "）"));
            return 0;
        }

        AttackProfile created = AttackProfile.create(name);
        if (created == null) return 0;

        AttackConfig.get().switchProfile(name);
        source.sendFeedback(Component.literal("已按默认值新建 profile：" + name + "（已切过去）"));
        source.sendFeedback(Component.literal("§7  默认值：自动切换开、忽略命名 / 被动 / 驯服开、"
            + "忽略幼体 / 暴击关，实体一个都没有"));
        return 1;
    }

    private static int delProfile(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        AttackConfig config = AttackConfig.get();

        if (name.equals(config.profileName())) {
            source.sendError(Component.literal("「" + name + "」正在用，先 switch_to 到别的再删"));
            return 0;
        }
        if (!AttackProfiles.delete(name)) {
            source.sendError(Component.literal("没有这个 profile：" + name));
            return 0;
        }
        source.sendFeedback(Component.literal("已删除 profile：" + name));
        return 1;
    }

    // ------------------------------------------------------------------
    // profile current
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> current() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommands.literal("current")
                .executes(AttackCommand::currentList)
                .then(ClientCommands.literal("list")
                    .executes(AttackCommand::currentList))
                .then(autoSwitch());

        for (Field field : Field.values()) {
            builder.then(defaultFlag(field));
        }
        builder.then(entities());
        return builder;
    }

    /** 当前这份的全部设置 + 攻击实体。 */
    private static int currentList(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7当前 profile：" + profile.name()
            + "（" + AttackProfiles.file(profile.name()) + "）"));
        source.sendFeedback(switchLine(ROOT + "profile current auto_switch",
            "自动切换物品", profile.autoSwitch()));
        for (Field field : Field.values()) {
            source.sendFeedback(switchLine(ROOT + "profile current " + field.defaultId(),
                "默认" + field.label(), field.getDefault(profile)));
        }

        source.sendFeedback(Component.literal("攻击实体：" + profile.rules().size() + " 种"));
        if (profile.isEmpty()) {
            source.sendFeedback(Component.literal("§7  还没有（/pc attack show_all_mobs 里点 [添加]）"));
        }
        for (Rule rule : profile.rules()) {
            MutableComponent line = Component.literal(" ");
            line.append(fillButton("[删除]", EDIT + "del " + rule.entitieId(),
                "把「把 " + rule.entitieId() + " 移出列表」的命令填进聊天栏，回车生效"));
            line.append(Component.literal(" "));
            line.append(runButton("[编辑]", EDIT + "edit " + rule.entitieId(),
                "直接显示 " + rule.entitieId() + " 的特殊配置"));
            line.append(Component.literal(" "));
            line.append(entityLabel(rule.entitieId()));
            source.sendFeedback(line);

            if (rule.hasSetting()) {
                source.sendFeedback(Component.literal("  特殊策略："));
                for (Field field : rule.settings()) {
                    source.sendFeedback(Component.literal("   " + field.label() + "："
                        + Field.text(field.get(rule))));
                }
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // profile current auto_switch / default_*
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> autoSwitch() {
        return ClientCommands.literal("auto_switch")
            .then(ClientCommands.literal("true")
                .executes(context -> setAutoSwitch(context, true)))
            .then(ClientCommands.literal("false")
                .executes(context -> setAutoSwitch(context, false)));
    }

    private static int setAutoSwitch(CommandContext<FabricClientCommandSource> context, boolean value) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        boolean before = profile.autoSwitch();
        profile.autoSwitch(value);
        profile.save();

        source.sendFeedback(Component.literal("自动切换物品：" + Field.text(before) + " -> "
            + Field.text(value)));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> defaultFlag(Field field) {
        return ClientCommands.literal(field.defaultId())
            .then(ClientCommands.literal("true")
                .executes(context -> setDefault(context, field, true)))
            .then(ClientCommands.literal("false")
                .executes(context -> setDefault(context, field, false)));
    }

    private static int setDefault(CommandContext<FabricClientCommandSource> context, Field field,
                                  boolean value) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        boolean before = field.getDefault(profile);
        field.setDefault(profile, value);
        profile.save();

        source.sendFeedback(Component.literal("默认" + field.label() + "：" + Field.text(before)
            + " -> " + Field.text(value)));
        return 1;
    }

    // ------------------------------------------------------------------
    // profile current entities
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> entities() {
        return ClientCommands.literal("entities")
            .then(ClientCommands.literal("add")
                .then(ClientCommands.argument("entitie_id", AttackArguments.EntityId.entityId())
                    .executes(AttackCommand::addEntity)))
            .then(ClientCommands.literal("add_batch")
                .then(ClientCommands.argument("group_id", AttackArguments.GroupId.groupId())
                    .executes(AttackCommand::addBatch)))
            .then(ClientCommands.literal("del")
                .then(ClientCommands.argument("entitie_id", AttackArguments.EntityId.entityId())
                    .executes(AttackCommand::delEntity)))
            .then(ClientCommands.literal("del_batch")
                .then(ClientCommands.argument("group_id", AttackArguments.GroupId.groupId())
                    .executes(AttackCommand::delBatch)))
            .then(edit())
            .then(editBatch());
    }

    /** {@code edit <实体> [开关 [true|false|default]]}。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> edit() {
        RequiredArgumentBuilder<FabricClientCommandSource, Identifier> withId =
            ClientCommands.argument("entitie_id", AttackArguments.EntityId.entityId())
                .executes(AttackCommand::showEntity);

        for (Field field : Field.values()) {
            LiteralArgumentBuilder<FabricClientCommandSource> node =
                ClientCommands.literal(field.id());
            for (AttackArguments.Value value : AttackArguments.Value.all()) {
                node.then(ClientCommands.literal(value.id())
                    .executes(context -> editEntity(context, field, value.value())));
            }
            withId.then(node);
        }
        return ClientCommands.literal("edit").then(withId);
    }

    /** {@code edit_batch <组> <开关> <true|false|default>}。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> editBatch() {
        RequiredArgumentBuilder<FabricClientCommandSource, EntityGroups.Group> withGroup =
            ClientCommands.argument("group_id", AttackArguments.GroupId.groupId());

        for (Field field : Field.values()) {
            LiteralArgumentBuilder<FabricClientCommandSource> node =
                ClientCommands.literal(field.id());
            for (AttackArguments.Value value : AttackArguments.Value.all()) {
                node.then(ClientCommands.literal(value.id())
                    .executes(context -> editBatch(context, field, value.value())));
            }
            withGroup.then(node);
        }
        return ClientCommands.literal("edit_batch").then(withGroup);
    }

    // ------------------------------------------------------------------
    // entities 的具体操作
    // ------------------------------------------------------------------

    private static int addEntity(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        Identifier id = AttackArguments.EntityId.get(context, "entitie_id");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            source.sendError(Component.literal("没有这个实体：" + id));
            return 0;
        }
        EntityGroups.Group group = EntityGroups.groupOf(type);
        if (group == null) {
            source.sendError(Component.literal(id + " 不是活物，打不了（还是说还没进世界？）"));
            return 0;
        }
        if (!profile.addEntity(type)) {
            source.sendFeedback(Component.literal("本来就在列表里：" + id));
            return 1;
        }
        profile.save();

        source.sendFeedback(Component.literal("已加入攻击实体：").append(entityLabel(id.toString()))
            .append(Component.literal("（组：" + group.label() + "）")));
        return 1;
    }

    private static int addBatch(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        EntityGroups.Group group = AttackArguments.GroupId.get(context, "group_id");
        List<EntityType<?>> mobs = group.mobs();
        if (mobs.isEmpty()) {
            source.sendError(Component.literal("「" + group.label() + "」里一个生物都没有"));
            return 0;
        }

        int added = 0;
        for (EntityType<?> type : mobs) {
            if (profile.addEntity(type)) added++;
        }
        profile.save();

        source.sendFeedback(Component.literal("已把「" + group.label() + "」加进攻击实体：" + added
            + " 种（本来就有 " + (mobs.size() - added) + " 种）"));
        return 1;
    }

    private static int delEntity(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        Identifier id = AttackArguments.EntityId.get(context, "entitie_id");
        if (!profile.delEntity(id.toString())) {
            source.sendError(Component.literal("列表里本来就没有 " + id));
            return 0;
        }
        profile.save();
        source.sendFeedback(Component.literal("已移出攻击实体：" + id));
        return 1;
    }

    private static int delBatch(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        EntityGroups.Group group = AttackArguments.GroupId.get(context, "group_id");
        List<EntityType<?>> mobs = group.mobs();
        if (mobs.isEmpty()) {
            source.sendError(Component.literal("「" + group.label() + "」里一个生物都没有"));
            return 0;
        }

        int removed = 0;
        for (EntityType<?> type : mobs) {
            if (profile.delEntity(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())) removed++;
        }
        if (removed == 0) {
            source.sendError(Component.literal("「" + group.label() + "」里的生物一个都不在列表里，没东西可删"));
            return 0;
        }
        profile.save();

        source.sendFeedback(Component.literal("已从攻击实体里移出「" + group.label() + "」的 " + removed
            + " 种（组里还有 " + (mobs.size() - removed) + " 种本来就不在列表里，跳过）"));
        return 1;
    }

    /** {@code entities edit <实体>}：这一只的特殊配置。 */
    private static int showEntity(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        Identifier id = AttackArguments.EntityId.get(context, "entitie_id");
        Rule rule = profile.rule(id.toString());
        if (rule == null) {
            source.sendError(Component.literal("这个 profile 里没有 " + id + "（先用 entities add 加进来）"));
            return 0;
        }

        source.sendFeedback(Component.literal("实体：").append(entityLabel(rule.entitieId())));
        for (Field field : Field.values()) {
            source.sendFeedback(threeStateLine(EDIT + "edit " + rule.entitieId() + " " + field.id(),
                field.label(), field.get(rule)));
        }
        source.sendFeedback(Component.literal("§7  「默认」= 实体上不写这一项，跟着 profile 的默认值走"));
        return 1;
    }

    private static int editEntity(CommandContext<FabricClientCommandSource> context, Field field,
                                  Boolean value) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        Identifier id = AttackArguments.EntityId.get(context, "entitie_id");
        Rule rule = profile.rule(id.toString());
        if (rule == null) {
            source.sendError(Component.literal("这个 profile 里没有 " + id + "（先用 entities add 加进来）"));
            return 0;
        }

        Boolean before = field.get(rule);
        field.set(rule, value);
        profile.save();

        source.sendFeedback(Component.literal(id + " " + field.label() + "：" + Field.text(before)
            + " -> " + Field.text(value)));
        return 1;
    }

    private static int editBatch(CommandContext<FabricClientCommandSource> context, Field field,
                                 Boolean value) {
        FabricClientCommandSource source = context.getSource();
        AttackProfile profile = profileOrError(source);
        if (profile == null) return 0;

        EntityGroups.Group group = AttackArguments.GroupId.get(context, "group_id");
        List<EntityType<?>> mobs = group.mobs();
        if (mobs.isEmpty()) {
            source.sendError(Component.literal("「" + group.label() + "」里一个生物都没有"));
            return 0;
        }

        int changed = 0;
        int skipped = 0;
        for (EntityType<?> type : mobs) {
            Rule rule = profile.rule(type);
            if (rule == null) {
                skipped++;
                continue;
            }
            field.set(rule, value);
            changed++;
        }
        if (changed == 0) {
            source.sendError(Component.literal("「" + group.label() + "」里的生物一个都不在列表里，没东西可改"));
            return 0;
        }
        profile.save();

        source.sendFeedback(Component.literal("已把「" + group.label() + "」里 " + changed + " 种生物的"
            + field.label() + "设成「" + Field.text(value) + "」"
            + "（组里还有 " + skipped + " 种不在列表里，跳过）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // show_all_mobs
    // ------------------------------------------------------------------

    private static int showAllMobs(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        if (Minecraft.getInstance().level == null) {
            source.sendError(Component.literal("还没进世界，读不到实体注册表"));
            return 0;
        }

        AttackProfile profile = AttackConfig.get().currentProfile();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7所有生物（"
            + EntityGroups.allMobs().size() + " 种）；金色按钮点了是填命令，回车才生效"));
        if (profile == null) {
            source.sendError(Component.literal("当前 profile 读不出来，下面一律按「不在列表里」显示"));
        }

        for (EntityGroups.Group group : EntityGroups.Group.values()) {
            List<EntityType<?>> mobs = group.mobs();
            if (mobs.isEmpty()) continue;

            MutableComponent head = Component.empty();
            head.append(fillButton("[批量添加]", EDIT + "add_batch " + group.id(),
                "把「" + group.label() + "」整组加进攻击实体"));
            head.append(Component.literal(" "));
            head.append(fillButton("[批量删除]", EDIT + "del_batch " + group.id(),
                "把「" + group.label() + "」整组移出攻击实体"));
            head.append(Component.literal(" "));
            head.append(fillButton("[批量编辑]", EDIT + "edit_batch " + group.id() + " ",
                "把「批量改这一组」的命令填进聊天栏，后面再补开关和 true/false/default"));
            head.append(Component.literal(" " + group.label() + " (" + group.id() + ")"));
            source.sendFeedback(head);

            for (EntityType<?> type : mobs) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                boolean inList = profile != null && profile.rule(id) != null;

                MutableComponent line = Component.literal(" ");
                if (inList) {
                    line.append(fillButton("[删除]", EDIT + "del " + id,
                        "把「把 " + id + " 移出列表」的命令填进聊天栏，回车生效"));
                } else {
                    line.append(fillButton("[添加]", EDIT + "add " + id,
                        "把「把 " + id + " 加进列表」的命令填进聊天栏，回车生效"));
                }
                line.append(Component.literal(" ")).append(entityLabel(id));
                source.sendFeedback(line);
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 「[启用] 自动切换物品：关」这种只有开 / 关的配置项：
     * 现在是关就给 {@code [启用]}，是开就给 {@code [禁用]}。
     */
    private static MutableComponent switchLine(String command, String label, boolean value) {
        MutableComponent line = Component.literal(" ");
        if (value) {
            line.append(fillButton("[禁用]", command + " false",
                "把「关掉" + label + "」的命令填进聊天栏，回车生效"));
        } else {
            line.append(fillButton("[启用]", command + " true",
                "把「打开" + label + "」的命令填进聊天栏，回车生效"));
        }
        line.append(Component.literal(" " + label + "：" + Field.text(value)));
        return line;
    }

    /**
     * 「[默认] [禁用] 忽略命名：开」这种有默认 / 开 / 关三态的配置项。
     *
     * <p>已经是开 / 关的时候，前面还给一个 {@code [默认]}（回到跟随默认值）；
     * 已经在默认态的时候不给 {@code [默认]}（没意义），只给开 / 关两个。
     */
    private static MutableComponent threeStateLine(String command, String label,
                                             @Nullable Boolean value) {
        MutableComponent line = Component.literal(" ");

        if (value != null) {
            line.append(fillButton("[默认]", command + " default",
                "把「" + label + " 回到跟着默认值走」的命令填进聊天栏，回车生效"));
            line.append(Component.literal(" "));
        }

        if (value == null || !value) {
            line.append(fillButton("[启用]", command + " true",
                "把「打开" + label + "」的命令填进聊天栏，回车生效"));
        }
        if (value == null || value) {
            if (value == null) line.append(Component.literal(" "));
            line.append(fillButton("[禁用]", command + " false",
                "把「关掉" + label + "」的命令填进聊天栏，回车生效"));
        }

        line.append(Component.literal(" " + label + "：" + Field.text(value)));
        return line;
    }

    /** 当前 profile；读不出来就报错并返回 null。 */
    @Nullable
    private static AttackProfile profileOrError(FabricClientCommandSource source) {
        AttackConfig config = AttackConfig.get();
        AttackProfile profile = config.currentProfile();
        if (profile == null) {
            source.sendError(Component.literal("当前 profile「" + config.profileName()
                + "」读不出来：" + AttackProfiles.file(config.profileName())));
        }
        return profile;
    }

    /** 「僵尸 (minecraft:zombie)」：名字用当前语言，id 灰色可复制。 */
    private static MutableComponent entityLabel(String entitieId) {
        Identifier identifier = Identifier.tryParse(entitieId);
        EntityType<?> type = identifier == null
            ? null
            : BuiltInRegistries.ENTITY_TYPE.getOptional(identifier).orElse(null);
        if (type == null) return gray(entitieId, entitieId);

        return Component.literal(type.getDescription().getString())
            .append(gray(" (" + entitieId + ")", entitieId));
    }
}
