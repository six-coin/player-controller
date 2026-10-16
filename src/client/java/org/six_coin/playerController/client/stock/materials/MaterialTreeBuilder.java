package org.six_coin.playerController.client.stock.materials;

import net.minecraft.item.Item;
import net.minecraft.registry.tag.TagKey;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从「投影要这些东西」递归追溯成材料树。
 *
 * <p>每一层的规则：
 * <ol>
 *   <li>已经到 {@value #MAX_DEPTH} 层 → 报「出现合成循环」，这一支到此为止；
 *       （铁块 → 铁锭 → 铁块这种无限循环就靠它兜住，用户得往 {@code terminal_items} 里加东西）</li>
 *   <li>在 {@code terminal_items} 里 → 原材料；</li>
 *   <li>本地配方表里没有能做出它的配方 → 原材料；</li>
 *   <li>挑配方：切石机配方唯一就用切石机，否则工作台配方唯一就用工作台，
 *       都不唯一（或有冲突）→ 报「多种合成方式」，让用户写偏好；</li>
 *   <li>配方里有标签格 / 一串可选物品格而用户没定过 → 报「未决定的标签」/「未决定的原料」。</li>
 * </ol>
 *
 * <p>数量：这一层要合的次数是 {@code ceil(需求 ÷ 单次产量)}，
 * 每一格用掉 {@code 合几次 × 这一格用几个}，再继续往下追。所以
 * 「99 个泥砖墙 → 99 个泥砖 → 100 个压实泥巴（一次出 4 个，凑 25 次）→ 100 个泥 + 100 个小麦」。
 *
 * <p>所有问题都**先记下来、等整棵树走完再一起报**（{@link #issues()}），
 * 不会因为一处出错就看不见别处的问题。
 *
 * <p>另外有两个兜底，免得循环把游戏卡死（循环要是带分支，光靠层数会长出天文数字的节点）：
 * 同一条链上同一个物品第二次出现就判循环（报的也是那个物品，比数到 50 层更准），
 * 整棵树超过 {@value #MAX_NODES} 个节点就停止再往下追。
 */
public final class MaterialTreeBuilder {

    /** 最多追多少层；到了就认为踩上循环了。 */
    public static final int MAX_DEPTH = 50;

    /** 整棵树最多几个节点；超了就停止再往下追（八成是循环）。 */
    public static final int MAX_NODES = 20000;

    /** 一个问题。 */
    public record Issue(Kind kind, Item item, @Nullable TagKey<Item> tag,
                        @Nullable String detail, List<Item> items) {

        public enum Kind {
            /** 追得太深，或者同一条链上又出现了同一个物品：循环。 */
            LOOP,
            /** 有多种合成方式。 */
            AMBIGUOUS,
            /** 偏好里写的配方做不出这个产物。 */
            BAD_PREFERENCE,
            /** 配方里有标签物品，没定过用哪个。 */
            UNDECIDED_TAG,
            /** 配方里有一串可选的物品，没定过用哪个。 */
            UNDECIDED_INGREDIENT,
            /** 树太大了，停止再往下追（八成是循环）。 */
            TOO_BIG
        }
    }

    /** 追溯结果。 */
    public record Result(List<MaterialTreeNode> roots, List<Issue> issues) {
    }

    private final MaterialsRecipes index;
    private final MaterialsConfig config;

    /** 去重过的问题（同一种问题只报一次）。 */
    private final Map<String, Issue> issues = new LinkedHashMap<>();

    /** 当前这条链上已经追过的物品（判循环用）。 */
    private final List<Item> path = new ArrayList<>();

    private int nodes = 0;
    private int deepest = 0;
    private boolean tooBig = false;

    private MaterialTreeBuilder(MaterialsRecipes index, MaterialsConfig config) {
        this.index = index;
        this.config = config;
    }

    /** 把一批「要多少」追溯成树。 */
    public static Result build(Map<Item, Integer> wanted, MaterialsRecipes index,
                               MaterialsConfig config) {
        MaterialTreeBuilder builder = new MaterialTreeBuilder(index, config);

        List<MaterialTreeNode> roots = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
            roots.add(builder.node(entry.getKey(), entry.getValue(), 0));
        }

        ChatUtils.debug("材料追溯：最终产物 " + wanted.size() + " 种，节点 " + builder.nodes
            + " 个，最深 " + builder.deepest + " 层，问题 " + builder.issues.size() + " 处");
        return new Result(List.copyOf(roots), List.copyOf(builder.issues.values()));
    }

    // ------------------------------------------------------------------

    private MaterialTreeNode node(Item item, int need, int depth) {
        nodes++;
        deepest = Math.max(deepest, depth + 1);

        if (need <= 0) return new MaterialTreeNode(item, 0, null, List.of());
        if (path.contains(item)) {
            // 同一条链上又绕回来了：铁块 → 铁锭 → 铁块
            report(new Issue(Issue.Kind.LOOP, item, null, null, List.of()));
            return new MaterialTreeNode(item, need, null, List.of());
        }
        if (depth >= MAX_DEPTH || nodes > MAX_NODES) {
            if (nodes > MAX_NODES && !tooBig) {
                tooBig = true;
                report(new Issue(Issue.Kind.TOO_BIG, item, null, null, List.of()));
            } else {
                report(new Issue(Issue.Kind.LOOP, item, null, null, List.of()));
            }
            return new MaterialTreeNode(item, need, null, List.of());
        }
        if (config.isTerminal(item)) {
            return new MaterialTreeNode(item, need, null, List.of());
        }

        MaterialsRecipes.Decision decision = index.decide(item, config);
        if (decision.problem() != null) {
            report(issueOf(decision.problem(), item));
            return new MaterialTreeNode(item, need, null, List.of());
        }
        if (decision.recipe() == null) {
            return new MaterialTreeNode(item, need, null, List.of());
        }

        MaterialsRecipes.Recipe recipe = decision.recipe();
        int crafts = (need + recipe.perCraft() - 1) / recipe.perCraft();

        // 同一格材料用了好几次就合并成「这一层一共要几个」
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        for (Item picked : decision.picked()) {
            if (picked != null) wanted.merge(picked, 1, Integer::sum);
        }

        List<MaterialTreeNode> subs = new ArrayList<>();
        path.add(item);
        try {
            for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
                subs.add(node(entry.getKey(), crafts * entry.getValue(), depth + 1));
            }
        } finally {
            path.remove(path.size() - 1);
        }
        return new MaterialTreeNode(item, need, recipe, List.copyOf(subs));
    }

    private static Issue issueOf(MaterialsRecipes.Problem problem, Item item) {
        return switch (problem.kind()) {
            case AMBIGUOUS -> new Issue(Issue.Kind.AMBIGUOUS, item, null, null, List.of());
            case BAD_PREFERENCE -> new Issue(Issue.Kind.BAD_PREFERENCE, item, null,
                problem.recipe() == null ? null : problem.recipe().toString(), List.of());
            case UNDECIDED_TAG -> new Issue(Issue.Kind.UNDECIDED_TAG, item, problem.tag(), null,
                List.of());
            case UNDECIDED_INGREDIENT -> new Issue(Issue.Kind.UNDECIDED_INGREDIENT, item, null,
                problem.slotKey(), problem.items());
        };
    }

    /** 记一个问题；同一种（同一个物品 / 同一个标签 / 同一处）只留一条。 */
    private void report(Issue issue) {
        String tag = issue.tag() == null ? "" : issue.tag().id().toString();
        String key = issue.kind() + "|" + MaterialsConfig.itemId(issue.item()) + "|" + tag
            + "|" + (issue.detail() == null ? "" : issue.detail());
        issues.putIfAbsent(key, issue);
    }
}
