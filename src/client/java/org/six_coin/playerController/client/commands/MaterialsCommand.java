package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.stock.materials.MaterialTreeBuilder;
import org.six_coin.playerController.client.stock.materials.MaterialsConfig;
import org.six_coin.playerController.client.stock.materials.MaterialsGenerator;
import org.six_coin.playerController.client.stock.materials.MaterialsRecipes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.six_coin.playerController.client.commands.CommandStyle.fillButton;
import static org.six_coin.playerController.client.commands.CommandStyle.gray;
import static org.six_coin.playerController.client.commands.CommandStyle.runButton;

/**
 * {@code /pc materials ...} —— 材料清单的配置，以及生成材料清单。
 *
 * <pre>
 * /pc materials                                                  看这几份配置在哪、各有多少条
 * /pc materials terminal_items                                   列出「到此为止」的物品
 * /pc materials terminal_items add|del &lt;item&gt;
 * /pc materials blacklist                                        列出不要的最终产物（只滤最外层）
 * /pc materials blacklist add|del &lt;item&gt;
 * /pc materials recipe_preferences                               列出所有配方偏好
 * /pc materials recipe_preferences show &lt;result_item&gt;            看这个产物有哪些做法可以选
 * /pc materials recipe_preferences add &lt;result_item&gt; &lt;recipe&gt;
 * /pc materials recipe_preferences del &lt;result_item&gt;
 * /pc materials tag_preferences                                  列出所有标签偏好
 * /pc materials tag_preferences show &lt;tag&gt;                       看这个标签里有哪些物品可以选
 * /pc materials tag_preferences add &lt;tag&gt; &lt;item&gt;
 * /pc materials tag_preferences del &lt;tag&gt;
 * /pc materials ingredient_preferences                           列出所有「一串可选物品」的偏好
 * /pc materials ingredient_preferences show &lt;key&gt;                看这一处有哪些物品可以选
 * /pc materials ingredient_preferences add &lt;key&gt; &lt;item&gt;
 * /pc materials ingredient_preferences del &lt;key&gt;
 * /pc materials replace_materials                                列出所有替换
 * /pc materials replace_materials add &lt;被替换的物品&gt; &lt;换成什么&gt;
 * /pc materials replace_materials del &lt;被替换的物品&gt;
 * </pre>
 *
 * <p>生成材料清单的入口在 {@code /pc stock task <名字> generate_materials_list}（见 {@link StockCommand}），
 * 生成失败的汇总也在这里排版（{@link #generate}）。
 */
public final class MaterialsCommand {

    /** 拼「点了会填什么命令」用的前缀。 */
    private static final String ROOT = "/pc materials ";

    /** 一个列表最多列几行，免得刷屏。 */
    private static final int MAX_LINES = 80;

    private MaterialsCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("materials")
            .executes(MaterialsCommand::status)
            .then(terminalItems())
            .then(blacklist())
            .then(recipePreferences())
            .then(tagPreferences())
            .then(ingredientPreferences())
            .then(replaceMaterials());
    }

    // ------------------------------------------------------------------
    // 生成材料清单（StockCommand 调的）
    // ------------------------------------------------------------------

    /**
     * 生成材料清单，并把结果报给用户。
     *
     * @return 命令返回值（成功 1、失败 0）
     */
    public static int generate(CommandContext<FabricClientCommandSource> context, String task) {
        FabricClientCommandSource source = context.getSource();
        Path original = StockManager.get().currentOriginalFile(task);

        if (!Files.exists(original)) {
            source.sendError(Text.literal("找不到投影材料表：" + original));
            source.sendFeedback(Text.literal("  在 litematica 里打开这个投影的材料列表，"
                + "用「As Json」那种导出方式导出，改名叫 original.json 放到上面这个位置"));
            return 0;
        }

        MaterialsGenerator.Result result;
        try {
            result = MaterialsGenerator.generate(task);
        } catch (IOException e) {
            source.sendError(Text.literal("生成材料清单失败：" + e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            // 没预料到的错也要说话，不然聊天栏一片空白、只能去翻日志
            source.sendError(Text.literal("生成材料清单时出错了（这算 bug，把日志发我）：" + e));
            return 0;
        }

        if (!result.ok()) {
            sendIssues(source, task, result);
            return 0;
        }

        source.sendFeedback(Text.literal("材料清单已生成：" + result.file()));
        source.sendFeedback(Text.literal("  投影里 " + result.originalKinds() + " 种 / "
            + result.originalTotal() + " 个物品"
            + (result.replacedKinds() > 0 ? "（替换了 " + result.replacedKinds() + " 种）" : "")
            + " -> " + result.roots().size() + " 棵材料树、"
            + result.nodeCount() + " 个节点、最深 " + result.deepest() + " 层"));
        if (!result.skipped().isEmpty()) {
            MutableText line = Text.literal("  黑名单跳过 " + result.skipped().size() + " 种最终产物：")
                .formatted(Formatting.GRAY);
            line.append(joinedItems(result.skipped()));
            source.sendFeedback(line);
        }
        return 1;
    }

    /**
     * 生成失败时的汇总。
     *
     * <p>每一行的 {@code [处理]} 都不一样：「要你选一个」的那几种（多种合成方式、未决定的标签、
     * 未决定的原料）点一下**直接执行**对应的 {@code show}，把可以选的东西列出来；
     * 「只有一条路可走」的（循环、树太大）点一下把 {@code terminal_items add} 填进聊天栏，
     * 回车才真的改配置（改配置这件事不替用户按下去）。
     */
    private static void sendIssues(FabricClientCommandSource source, String task,
                                   MaterialsGenerator.Result result) {
        source.sendError(Text.literal("材料列表生成失败：" + result.issues().size() + " 处"));
        for (MaterialTreeBuilder.Issue issue : result.issues()) {
            MutableText line = Text.literal(" ");
            line.append(issueButton(issue));
            line.append(Text.literal(" "));
            line.append(describeIssue(issue));
            source.sendFeedback(line);
        }
        source.sendFeedback(Text.literal("§7金色的点了填命令（回车确认）、"
            + "§b浅蓝色的点了直接列出可以选什么§7；改完再跑一次 §f/pc stock task " + task
            + " generate_materials_list§7（这次没有写 materials.json）"));
    }

    /** 这一行的 {@code [处理]}。 */
    private static MutableText issueButton(MaterialTreeBuilder.Issue issue) {
        String item = MaterialsConfig.itemId(issue.item());
        return switch (issue.kind()) {
            case LOOP, TOO_BIG -> fillButton("[处理]", ROOT + "terminal_items add " + item,
                "把「" + item + " 到此为止、不再往里追溯」的命令填进聊天栏，回车生效");
            case AMBIGUOUS, BAD_PREFERENCE -> runButton("[处理]",
                ROOT + "recipe_preferences show " + item,
                "直接列出 " + item + " 有哪些配方可以选");
            case UNDECIDED_TAG -> runButton("[处理]",
                ROOT + "tag_preferences show " + MaterialsConfig.tagId(issue.tag()),
                "直接列出 " + MaterialsConfig.tagId(issue.tag()) + " 里有哪些物品可以选");
            case UNDECIDED_INGREDIENT -> runButton("[处理]",
                ROOT + "ingredient_preferences show " + issue.detail(),
                "直接列出 " + issue.detail() + " 这一格有哪些物品可以选");
        };
    }

    /** 一句话说清是什么问题。 */
    private static Text describeIssue(MaterialTreeBuilder.Issue issue) {
        return switch (issue.kind()) {
            case LOOP -> Text.literal("出现合成循环：").append(itemLabel(issue.item()));
            case AMBIGUOUS -> Text.literal("多种合成方式：").append(itemLabel(issue.item()));
            case BAD_PREFERENCE -> Text.literal("偏好不对：").append(itemLabel(issue.item()))
                .append(Text.literal("定成了 " + issue.detail() + "，但这条配方做不出它"));
            case UNDECIDED_TAG -> Text.literal("未决定的标签："
                + MaterialsConfig.tagId(issue.tag()))
                .append(Text.literal("（做 " + MaterialsConfig.itemId(issue.item()) + " 的时候用到）")
                    .formatted(Formatting.GRAY));
            case UNDECIDED_INGREDIENT -> Text.literal("未决定的原料：" + issue.detail() + " 这一格（")
                .append(namesOf(issue.items())).append(Text.literal("）"));
            case TOO_BIG -> Text.literal("材料树太大了（超过 " + MaterialTreeBuilder.MAX_NODES
                + " 个节点，八成是循环）：").append(itemLabel(issue.item()));
        };
    }

    // ------------------------------------------------------------------
    // terminal_items
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> terminalItems() {
        return ClientCommandManager.literal("terminal_items")
            .executes(context -> {
                MaterialsConfig config = MaterialsConfig.load();
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("到此为止的物品（不往里追溯）："
                    + config.terminalItems().size() + " 种"));
                int shown = 0;
                for (String id : config.terminalItems()) {
                    if (shown++ >= MAX_LINES) break;
                    source.sendFeedback(Text.literal("  ").append(idLabel(id)));
                }
                source.sendFeedback(pathLine(MaterialsConfig.terminalFile()));
                return 1;
            })
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                    .executes(context -> {
                        Item item = ItemIdArgumentType.getItem(context, "item_id");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean added = config.addTerminal(item);
                        config.save();
                        context.getSource().sendFeedback(Text.literal(added
                            ? "已加入到此为止：" + MaterialsConfig.itemId(item)
                            : "本来就在里面：" + MaterialsConfig.itemId(item)));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                    .executes(context -> {
                        Item item = ItemIdArgumentType.getItem(context, "item_id");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean removed = config.delTerminal(item);
                        config.save();
                        if (!removed) {
                            context.getSource().sendError(Text.literal("里面没有 "
                                + MaterialsConfig.itemId(item)));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal("已移出："
                            + MaterialsConfig.itemId(item)));
                        return 1;
                    })));
    }

    // ------------------------------------------------------------------
    // blacklist
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> blacklist() {
        return ClientCommandManager.literal("blacklist")
            .executes(context -> {
                MaterialsConfig config = MaterialsConfig.load();
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("不要出现在清单里的最终产物："
                    + config.blacklist().size() + " 种"));
                int shown = 0;
                for (String id : config.blacklist()) {
                    if (shown++ >= MAX_LINES) break;
                    source.sendFeedback(Text.literal("  ").append(idLabel(id)));
                }
                source.sendFeedback(Text.literal("§7  只管最外层：它要是别的产物的材料，照样会追溯"));
                source.sendFeedback(pathLine(MaterialsConfig.blacklistFile()));
                return 1;
            })
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                    .executes(context -> {
                        Item item = ItemIdArgumentType.getItem(context, "item_id");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean added = config.addBlacklist(item);
                        config.save();
                        context.getSource().sendFeedback(Text.literal(added
                            ? "已加入黑名单：" + MaterialsConfig.itemId(item)
                            : "本来就在黑名单里：" + MaterialsConfig.itemId(item)));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                    .executes(context -> {
                        Item item = ItemIdArgumentType.getItem(context, "item_id");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean removed = config.delBlacklist(item);
                        config.save();
                        if (!removed) {
                            context.getSource().sendError(Text.literal("黑名单里没有 "
                                + MaterialsConfig.itemId(item)));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal("已移出黑名单："
                            + MaterialsConfig.itemId(item)));
                        return 1;
                    })));
    }

    // ------------------------------------------------------------------
    // recipe_preferences
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> recipePreferences() {
        return ClientCommandManager.literal("recipe_preferences")
            .executes(context -> {
                MaterialsConfig config = MaterialsConfig.load();
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("配方偏好：" + config.recipePreferences().size() + " 条"));
                int shown = 0;
                for (Map.Entry<String, String> entry : config.recipePreferences().entrySet()) {
                    if (shown++ >= MAX_LINES) break;
                    source.sendFeedback(Text.literal("  ").append(idLabel(entry.getKey()))
                        .append(Text.literal(" -> " + entry.getValue())));
                }
                source.sendFeedback(pathLine(MaterialsConfig.recipeFile()));
                return 1;
            })
            .then(ClientCommandManager.literal("show")
                .then(ClientCommandManager.argument("result_item", ItemIdArgumentType.itemId())
                    .executes(MaterialsCommand::showRecipes)))
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("result_item", ItemIdArgumentType.itemId())
                    .then(ClientCommandManager.argument("recipe_name", MaterialsArguments.Recipe.recipe())
                        .executes(MaterialsCommand::addRecipePreference))))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("result_item", ItemIdArgumentType.itemId())
                    .executes(context -> {
                        Item item = ItemIdArgumentType.getItem(context, "result_item");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean removed = config.delRecipePreference(item);
                        config.save();
                        if (!removed) {
                            context.getSource().sendError(Text.literal("没有 "
                                + MaterialsConfig.itemId(item) + " 的配方偏好"));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal("已删掉 "
                            + MaterialsConfig.itemId(item) + " 的配方偏好"));
                        return 1;
                    })));
    }

    /** {@code recipe_preferences show <产物>}：这个产物有哪些做法，各自的材料和结果。 */
    private static int showRecipes(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Item result = ItemIdArgumentType.getItem(context, "result_item");

        MaterialsRecipes index = index();
        if (index == null) {
            source.sendError(Text.literal("还没有进世界，读不到本地配方表"));
            return 0;
        }

        List<MaterialsRecipes.Recipe> candidates = index.candidates(result);
        source.sendFeedback(Text.literal("合成结果：").append(itemLabel(result)));
        if (candidates.isEmpty()) {
            source.sendError(Text.literal("本地配方表里没有能做出 "
                + MaterialsConfig.itemId(result) + " 的配方"));
            return 0;
        }
        if (candidates.size() == 1) {
            source.sendError(Text.literal("只有一种配方，不用选："
                + candidates.getFirst().id()));
            return 0;
        }

        MaterialsConfig config = MaterialsConfig.load();
        Identifier preferred = config.recipePreference(result);
        source.sendFeedback(Text.literal("可用配方："));

        for (int i = 0; i < candidates.size(); i++) {
            MaterialsRecipes.Recipe recipe = candidates.get(i);
            Identifier id = recipe.id();

            MutableText head = Text.literal("[" + (i + 1) + "] ").formatted(Formatting.GOLD);
            head.append(fillButton("[选择该配方]",
                ROOT + "recipe_preferences add " + MaterialsConfig.itemId(result) + " " + id,
                "把「" + MaterialsConfig.itemId(result) + " 用 " + id + " 这条配方」的命令填进聊天栏，回车生效"));
            head.append(Text.literal(" ").append(gray("(" + id + ")", id.toString())));
            if (id.equals(preferred)) head.append(Text.literal(" §a（当前选择）"));
            source.sendFeedback(head);

            source.sendFeedback(Text.literal(" 结果："));
            source.sendFeedback(Text.literal("  ").append(itemLabel(result))
                .append(Text.literal(" x " + recipe.perCraft())));
            source.sendFeedback(Text.literal(" 材料："));
            for (Text line : materialLines(recipe, config)) {
                source.sendFeedback(Text.literal("  ").append(line));
            }
        }
        return 1;
    }

    /** 一条配方要用的材料（配方里的标签格 / 可选格还没定的会明说）。 */
    private static List<Text> materialLines(MaterialsRecipes.Recipe recipe, MaterialsConfig config) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Text> descriptions = new LinkedHashMap<>();

        for (int i = 0; i < recipe.slots().size(); i++) {
            MaterialsRecipes.Slot slot = recipe.slots().get(i);
            if (slot.isEmpty()) continue;

            if (slot.tag() != null) {
                Item used = MaterialsRecipes.decidedTagItem(slot.tag(), config);
                String tag = MaterialsConfig.tagId(slot.tag());
                String key = used == null ? "tag:" + tag : "item:" + MaterialsConfig.itemId(used);
                counts.merge(key, 1, Integer::sum);
                if (used == null) {
                    descriptions.putIfAbsent(key,
                        Text.literal(tag + "（还没定用哪个物品）").formatted(Formatting.GRAY));
                } else {
                    // 没配偏好也会定下来，说明这个标签里只有一个物品
                    String why = config.tagPreference(slot.tag()) == null
                        ? "（标签里只有这一个物品）" : "";
                    descriptions.putIfAbsent(key, itemLabel(used)
                        .append(Text.literal(" 来自 " + tag + why).formatted(Formatting.GRAY)));
                }
                continue;
            }
            if (slot.items().size() == 1) {
                Item item = slot.items().getFirst();
                String key = "item:" + MaterialsConfig.itemId(item);
                counts.merge(key, 1, Integer::sum);
                descriptions.putIfAbsent(key, itemLabel(item));
                continue;
            }
            String slotKey = recipe.slotKey(i);
            Item used = config.ingredientPreference(slotKey);
            String key = used == null ? "choice:" + slotKey : "item:" + MaterialsConfig.itemId(used);
            counts.merge(key, 1, Integer::sum);
            descriptions.putIfAbsent(key, used == null
                ? Text.literal(slotKey + "：").append(namesOf(slot.items()))
                    .append(Text.literal("（还没定用哪个）")).formatted(Formatting.GRAY)
                : itemLabel(used).append(Text.literal(" 来自 " + slotKey).formatted(Formatting.GRAY)));
        }

        List<Text> lines = new ArrayList<>();
        for (Map.Entry<String, Text> entry : descriptions.entrySet()) {
            lines.add(Text.empty().append(entry.getValue())
                .append(Text.literal(" x " + counts.get(entry.getKey()))));
        }
        return lines;
    }

    private static int addRecipePreference(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Item result = ItemIdArgumentType.getItem(context, "result_item");
        Identifier recipe = MaterialsArguments.Recipe.get(context, "recipe_name");

        MaterialsRecipes index = index();
        if (index != null && index.size() > 0) {
            boolean known = index.candidates(result).stream()
                .anyMatch(candidate -> candidate.id().equals(recipe));
            if (!known) {
                source.sendError(Text.literal(recipe + " 做不出 "
                    + MaterialsConfig.itemId(result) + "（/pc materials recipe_preferences show "
                    + MaterialsConfig.itemId(result) + " 看有哪些）"));
                return 0;
            }
        }

        MaterialsConfig config = MaterialsConfig.load();
        boolean changed = config.addRecipePreference(result, recipe);
        config.save();
        source.sendFeedback(Text.literal(changed
            ? "已把 " + MaterialsConfig.itemId(result) + " 的配方定为 " + recipe
            : "本来就是 " + recipe));
        return 1;
    }

    // ------------------------------------------------------------------
    // tag_preferences
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> tagPreferences() {
        return ClientCommandManager.literal("tag_preferences")
            .executes(context -> {
                MaterialsConfig config = MaterialsConfig.load();
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("标签偏好：" + config.tagPreferences().size() + " 条"));
                int shown = 0;
                for (Map.Entry<String, String> entry : config.tagPreferences().entrySet()) {
                    if (shown++ >= MAX_LINES) break;
                    source.sendFeedback(Text.literal("  " + entry.getKey() + " -> ")
                        .append(idLabel(entry.getValue())));
                }
                source.sendFeedback(pathLine(MaterialsConfig.tagFile()));
                return 1;
            })
            .then(ClientCommandManager.literal("show")
                .then(ClientCommandManager.argument("tag_id", MaterialsArguments.Tag.tag())
                    .executes(MaterialsCommand::showTag)))
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("tag_id", MaterialsArguments.Tag.tag())
                    .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                        .executes(MaterialsCommand::addTagPreference))))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("tag_id", MaterialsArguments.Tag.tag())
                    .executes(context -> {
                        TagKey<Item> tag = MaterialsArguments.Tag.get(context, "tag_id");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean removed = config.delTagPreference(tag);
                        config.save();
                        if (!removed) {
                            context.getSource().sendError(Text.literal("没有 "
                                + MaterialsConfig.tagId(tag) + " 的偏好"));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal("已删掉 "
                            + MaterialsConfig.tagId(tag) + " 的偏好"));
                        return 1;
                    })));
    }

    /** {@code tag_preferences show <标签>}：这个标签里有哪些物品可以选。 */
    private static int showTag(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        TagKey<Item> tag = MaterialsArguments.Tag.get(context, "tag_id");
        String tagId = MaterialsConfig.tagId(tag);

        List<Item> items = MaterialsRecipes.tagItems(tag);
        source.sendFeedback(Text.literal("标签：" + tagId));
        if (items.isEmpty()) {
            source.sendError(Text.literal("这个标签里一个物品都没有（标签名写错了？还是还没进世界？）"));
            return 0;
        }

        MaterialsConfig config = MaterialsConfig.load();
        Item current = config.tagPreference(tag);
        if (items.size() == 1) {
            source.sendFeedback(Text.literal("§7（这个标签里只有一个物品，不配也会直接用它）"));
        }
        source.sendFeedback(Text.literal("可用物品："));

        int shown = 0;
        for (Item item : items) {
            if (shown++ >= MAX_LINES) break;
            MutableText line = Text.literal(" ");
            line.append(fillButton("[选择该物品]",
                ROOT + "tag_preferences add " + tagId + " " + MaterialsConfig.itemId(item),
                "把「" + tagId + " 用 " + MaterialsConfig.itemId(item) + "」的命令填进聊天栏，回车生效"));
            line.append(Text.literal(" ").append(itemLabel(item)));
            if (item == current) {
                line.append(Text.literal(" §a（当前选择）"));
            } else if (items.size() == 1) {
                line.append(Text.literal(" §7（没配也用它）"));
            }
            source.sendFeedback(line);
        }
        if (items.size() > MAX_LINES) {
            source.sendFeedback(Text.literal("  §7…还有 " + (items.size() - MAX_LINES) + " 个"));
        }
        return 1;
    }

    private static int addTagPreference(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        TagKey<Item> tag = MaterialsArguments.Tag.get(context, "tag_id");
        Item item = ItemIdArgumentType.getItem(context, "item_id");

        List<Item> items = MaterialsRecipes.tagItems(tag);
        if (!items.isEmpty() && !items.contains(item)) {
            source.sendError(Text.literal(MaterialsConfig.itemId(item) + " 不在 "
                + MaterialsConfig.tagId(tag) + " 里（/pc materials tag_preferences show "
                + MaterialsConfig.tagId(tag) + " 看有哪些）"));
            return 0;
        }

        MaterialsConfig config = MaterialsConfig.load();
        boolean changed = config.addTagPreference(tag, item);
        config.save();
        source.sendFeedback(Text.literal(changed
            ? "已把 " + MaterialsConfig.tagId(tag) + " 定为 " + MaterialsConfig.itemId(item)
            : "本来就是 " + MaterialsConfig.itemId(item)));
        return 1;
    }

    // ------------------------------------------------------------------
    // ingredient_preferences
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> ingredientPreferences() {
        return ClientCommandManager.literal("ingredient_preferences")
            .executes(context -> {
                MaterialsConfig config = MaterialsConfig.load();
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("「一串可选物品」的偏好："
                    + config.ingredientPreferences().size() + " 条"));
                int shown = 0;
                for (Map.Entry<String, String> entry : config.ingredientPreferences().entrySet()) {
                    if (shown++ >= MAX_LINES) break;
                    source.sendFeedback(Text.literal("  " + entry.getKey() + " -> ")
                        .append(idLabel(entry.getValue())));
                }
                source.sendFeedback(pathLine(MaterialsConfig.ingredientFile()));
                return 1;
            })
            .then(ClientCommandManager.literal("show")
                .then(ClientCommandManager.argument("slot_key", MaterialsArguments.SlotKey.slotKey())
                    .executes(MaterialsCommand::showSlot)))
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("slot_key", MaterialsArguments.SlotKey.slotKey())
                    .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                        .executes(MaterialsCommand::addSlotPreference))))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("slot_key", MaterialsArguments.SlotKey.slotKey())
                    .executes(context -> {
                        String key = MaterialsArguments.SlotKey.get(context, "slot_key");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean removed = config.delIngredientPreference(key);
                        config.save();
                        if (!removed) {
                            context.getSource().sendError(Text.literal("没有 " + key + " 的偏好"));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal("已删掉 " + key + " 的偏好"));
                        return 1;
                    })));
    }

    /** {@code ingredient_preferences show <键>}：这一处有哪些物品可以选。 */
    private static int showSlot(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String key = MaterialsArguments.SlotKey.get(context, "slot_key");

        List<Item> items = choiceItems(key);
        source.sendFeedback(Text.literal("可选材料：" + key));
        if (items.isEmpty()) {
            source.sendError(Text.literal("认不出这个键（这条配方不在本地配方表里，或者这一格没有可选物品）"));
            return 0;
        }

        MaterialsConfig config = MaterialsConfig.load();
        Item current = config.ingredientPreference(key);
        source.sendFeedback(Text.literal("候选物品："));

        for (Item item : items) {
            MutableText line = Text.literal(" ");
            line.append(fillButton("[选择该物品]",
                ROOT + "ingredient_preferences add " + key + " " + MaterialsConfig.itemId(item),
                "把「" + key + " 这一格用 " + MaterialsConfig.itemId(item) + "」的命令填进聊天栏，回车生效"));
            line.append(Text.literal(" ").append(itemLabel(item)));
            if (item == current) line.append(Text.literal(" §a（当前选择）"));
            source.sendFeedback(line);
        }
        return 1;
    }

    private static int addSlotPreference(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String key = MaterialsArguments.SlotKey.get(context, "slot_key");
        Item item = ItemIdArgumentType.getItem(context, "item_id");

        List<Item> items = choiceItems(key);
        if (!items.isEmpty() && !items.contains(item)) {
            source.sendError(Text.literal(MaterialsConfig.itemId(item) + " 不在 " + key
                + " 这一格里（/pc materials ingredient_preferences show " + key + " 看有哪些）"));
            return 0;
        }

        MaterialsConfig config = MaterialsConfig.load();
        boolean changed = config.addIngredientPreference(key, item);
        config.save();
        source.sendFeedback(Text.literal(changed
            ? "已把 " + key + " 定为 " + MaterialsConfig.itemId(item)
            : "本来就是 " + MaterialsConfig.itemId(item)));
        return 1;
    }

    /** 一处「一串可选物品」能放哪些东西（本地配方表里找）。 */
    private static List<Item> choiceItems(String slotKey) {
        Identifier recipeId = MaterialsConfig.recipeOfSlotKey(slotKey);
        String canonical = MaterialsConfig.canonicalIngredientKey(slotKey);
        if (recipeId == null || canonical == null) return List.of();

        MaterialsRecipes index = index();
        if (index == null) return List.of();

        MaterialsRecipes.Recipe recipe = index.byId(recipeId);
        if (recipe == null) return List.of();

        int n = Integer.parseInt(canonical.substring(canonical.lastIndexOf('#') + 1));
        return recipe.choiceItems(n);
    }

    // ------------------------------------------------------------------
    // replace_materials
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> replaceMaterials() {
        return ClientCommandManager.literal("replace_materials")
            .executes(context -> {
                MaterialsConfig config = MaterialsConfig.load();
                FabricClientCommandSource source = context.getSource();
                source.sendFeedback(Text.literal("材料替换：" + config.replaceMaterials().size() + " 条"
                    + "（只作用于 original.json 最外层）"));
                int shown = 0;
                for (Map.Entry<String, String> entry : config.replaceMaterials().entrySet()) {
                    if (shown++ >= MAX_LINES) break;
                    source.sendFeedback(Text.literal("  ").append(idLabel(entry.getKey()))
                        .append(Text.literal(" -> ")).append(idLabel(entry.getValue())));
                }
                source.sendFeedback(pathLine(MaterialsConfig.replaceFile()));
                return 1;
            })
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.argument("replaced_item_id", ItemIdArgumentType.itemId())
                    .then(ClientCommandManager.argument("item_id", ItemIdArgumentType.itemId())
                        .executes(context -> {
                            Item replaced = ItemIdArgumentType.getItem(context, "replaced_item_id");
                            Item replacement = ItemIdArgumentType.getItem(context, "item_id");
                            if (replaced == replacement) {
                                context.getSource().sendError(Text.literal("换成它自己？"));
                                return 0;
                            }
                            MaterialsConfig config = MaterialsConfig.load();
                            boolean changed = config.addReplaceMaterial(replaced, replacement);
                            config.save();
                            context.getSource().sendFeedback(Text.literal(changed
                                ? "已把 " + MaterialsConfig.itemId(replaced) + " 换成 "
                                    + MaterialsConfig.itemId(replacement)
                                : "本来就是这么换的"));
                            return 1;
                        }))))
            .then(ClientCommandManager.literal("del")
                .then(ClientCommandManager.argument("replaced_item_id", ItemIdArgumentType.itemId())
                    .executes(context -> {
                        Item replaced = ItemIdArgumentType.getItem(context, "replaced_item_id");
                        MaterialsConfig config = MaterialsConfig.load();
                        boolean removed = config.delReplaceMaterial(replaced);
                        config.save();
                        if (!removed) {
                            context.getSource().sendError(Text.literal("没有 "
                                + MaterialsConfig.itemId(replaced) + " 的替换"));
                            return 0;
                        }
                        context.getSource().sendFeedback(Text.literal("已删掉 "
                            + MaterialsConfig.itemId(replaced) + " 的替换"));
                        return 1;
                    })));
    }

    // ------------------------------------------------------------------
    // 总览
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MaterialsConfig config = MaterialsConfig.load();

        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7材料清单配置（" + MaterialsConfig.dir() + "）："));
        source.sendFeedback(Text.literal("  terminal_items         " + config.terminalItems().size()
            + " 种（到此为止的物品）"));
        source.sendFeedback(Text.literal("  blacklist              " + config.blacklist().size()
            + " 种（不要的最终产物，只滤最外层）"));
        source.sendFeedback(Text.literal("  recipe_preferences     " + config.recipePreferences().size()
            + " 条（一种产物多种做法时用哪条）"));
        source.sendFeedback(Text.literal("  tag_preferences        " + config.tagPreferences().size()
            + " 条（配方里的标签用哪个物品）"));
        source.sendFeedback(Text.literal("  ingredient_preferences " + config.ingredientPreferences().size()
            + " 条（配方里「一串可选物品」用哪个）"));
        source.sendFeedback(Text.literal("  replace_materials      " + config.replaceMaterials().size()
            + " 条（能拿什么替什么）"));
        source.sendFeedback(Text.literal("§7  /pc stock task <名字> generate_materials_list"
            + " §8- §7把 original.json 处理成 materials.json"));
        source.sendFeedback(Text.literal("§7  六份都有默认值：文件不在的时候按模组自带的默认建一份；"
            + "文件在但读不出来就只报错、不动它"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 「名字 (id)」：名字用当前语言，id 灰色可复制。 */
    private static MutableText itemLabel(Item item) {
        String id = MaterialsConfig.itemId(item);
        return Text.literal(item.getName().getString()).append(gray(" (" + id + ")", id));
    }

    /** 只有一个 id 的时候（配置里认不出的物品）也尽量显示名字。 */
    private static MutableText idLabel(String id) {
        Item item = MaterialsConfig.itemOf(id);
        return item == null ? gray(id, id) : itemLabel(item);
    }

    private static MutableText namesOf(List<Item> items) {
        MutableText text = Text.empty();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) text.append(Text.literal(" / "));
            text.append(Text.literal(items.get(i).getName().getString()));
        }
        return text;
    }

    /** 「名字 (id)、名字 (id)…」；太多就只列前几个。 */
    private static MutableText joinedItems(List<Item> items) {
        MutableText text = Text.empty();
        int max = 8;
        for (int i = 0; i < items.size() && i < max; i++) {
            if (i > 0) text.append(Text.literal("§7、"));
            text.append(itemLabel(items.get(i)));
        }
        if (items.size() > max) text.append(Text.literal("§7 等 " + items.size() + " 种"));
        return text;
    }

    private static Text pathLine(Path file) {
        return Text.literal("  §8文件：" + file);
    }

    /** 本地配方表（没进世界就是 null）。 */
    @Nullable
    private static MaterialsRecipes index() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;
        return MaterialsRecipes.build(mc);
    }
}
