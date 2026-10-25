package org.six_coin.playerController.client.mine.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pc mine debug_full_inventory} / {@code debug_restock_inventory} 用：
 * 生成「把物品栏摆成某个样子」的那一串管理员指令。
 *
 * <p><b>为什么用 {@code /item replace} 而不是 {@code /give}</b>：{@code /give} 是「塞到第一个
 * 空位」，位置不由我们决定；{@code /item replace entity @s <slot> with <item>} 是**按槽位**
 * 放的，位置精确，而且只动我们点名的那几格 —— 盔甲一格都不碰。
 *
 * <p><b>槽位名</b>（{@code SlotRanges}）：
 * <ul>
 *   <li>快捷栏 1~9 = {@code hotbar.0}~{@code hotbar.8}；</li>
 *   <li>背包 1~27 = {@code inventory.0}~{@code inventory.26}；</li>
 *   <li>副手 = {@code weapon.offhand}。</li>
 * </ul>
 *
 * <p><b>数量</b>：{@code /item replace} 只给 1 个，所以带数量的东西分两步 ——
 * 先 {@code replace} 放 1 个，再 {@code /give} 补差额（{@code /give} 会并到同一种物品的那一叠上）。
 * 前提是物品栏里别处没有同种东西，所以指令顺序是「先把别的格子都摆好 → 最后才给食物」。
 */
public final class DebugInventory {

    private static final String AIR = "minecraft:air";
    private static final String EMPTY_BOX = "minecraft:shulker_box";

    /** 快捷栏第 {@code index} 格（0 开始）。 */
    private static String hotbar(int index) {
        return "hotbar." + index;
    }

    /** 背包第 {@code index} 格（1 开始）。 */
    private static String backpack(int index) {
        return "inventory." + (index - 1);
    }

    private static final String OFFHAND = "weapon.offhand";

    private DebugInventory() {
    }

    // ------------------------------------------------------------------
    // 两套物品栏
    // ------------------------------------------------------------------

    /**
     * 完全符合 {@code debug_chunk_miner} 的物品栏：
     * <ul>
     *   <li>快捷栏 1~5：钻石剑 / 镐 / 斧 / 铲 / 锄（经验修补 + 耐久 III + 效率 V，剑再加锋利 V），耐久全满；</li>
     *   <li>快捷栏 6~8：空；快捷栏 9：熟猪排 64；</li>
     *   <li>背包 1~5：备用剑 / 镐 / 镐 / 铲 / 镐，耐久全满；</li>
     *   <li>背包 6~8：装满圆石（1728）的潜影盒；9~11：装满黏液块（1728）的潜影盒；</li>
     *   <li>背包 12：熔岩桶；13~21：9 个空潜影盒；22~27：空；</li>
     *   <li>副手：不死图腾。盔甲不管。</li>
     * </ul>
     */
    public static List<String> full() {
        List<String> commands = new ArrayList<>();

        // 快捷栏 1~5：五类工具（满耐久）
        commands.add(replace(hotbar(0), tool("sword", 0)));
        commands.add(replace(hotbar(1), tool("pickaxe", 0)));
        commands.add(replace(hotbar(2), tool("axe", 0)));
        commands.add(replace(hotbar(3), tool("shovel", 0)));
        commands.add(replace(hotbar(4), tool("hoe", 0)));

        // 背包 1~5：备用剑 / 镐 / 镐 / 铲 / 镐（满耐久）
        commands.add(replace(backpack(1), tool("sword", 0)));
        commands.add(replace(backpack(2), tool("pickaxe", 0)));
        commands.add(replace(backpack(3), tool("pickaxe", 0)));
        commands.add(replace(backpack(4), tool("shovel", 0)));
        commands.add(replace(backpack(5), tool("pickaxe", 0)));

        // 背包 6~8 圆石盒、9~11 黏液块盒：全满
        for (int i = 6; i <= 8; i++) commands.add(replace(backpack(i), fullBox("minecraft:cobblestone")));
        for (int i = 9; i <= 11; i++) commands.add(replace(backpack(i), fullBox("minecraft:slime_block")));

        // 背包 12：熔岩桶
        commands.add(replace(backpack(12), "minecraft:lava_bucket"));

        // 背包 13~21：9 个空潜影盒
        for (int i = 13; i <= 21; i++) commands.add(replace(backpack(i), EMPTY_BOX));

        // 该空的：快捷栏 6~8、背包 22~27
        for (int i = 5; i <= 7; i++) commands.add(replace(hotbar(i), AIR));
        for (int i = 22; i <= 27; i++) commands.add(replace(backpack(i), AIR));

        // 副手：不死图腾
        commands.add(replace(OFFHAND, "minecraft:totem_of_undying"));

        // 食物放最后（见类注释里「数量」那一段）
        addCounted(commands, hotbar(8), "minecraft:cooked_porkchop", 64);
        return commands;
    }

    /**
     * 「什么都要补货」的物品栏（用来测补货流程）：
     * <ul>
     *   <li>快捷栏 1~5：五类工具，耐久全满；6~8 空；9：熟猪排 8；</li>
     *   <li>背包 1~5：同样五类工具，但耐久都是 99（damage=99）；</li>
     *   <li>背包 6：半盒圆石，7 / 8：空盒；9：半盒黏液块，10 / 11：空盒；</li>
     *   <li>背包 12：熔岩桶；</li>
     *   <li>背包 13：装满深板岩；14：装满青金石；15：2 组方解石 + 2 组紫水晶块；16~21：空盒；</li>
     *   <li>背包 22~27：空；副手：空。盔甲不管。</li>
     * </ul>
     */
    public static List<String> restock() {
        List<String> commands = new ArrayList<>();

        // 快捷栏 1~5：五类工具（满耐久）
        commands.add(replace(hotbar(0), tool("sword", 0)));
        commands.add(replace(hotbar(1), tool("pickaxe", 0)));
        commands.add(replace(hotbar(2), tool("axe", 0)));
        commands.add(replace(hotbar(3), tool("shovel", 0)));
        commands.add(replace(hotbar(4), tool("hoe", 0)));

        // 背包 1~5：备用剑 / 镐 / 镐 / 铲 / 镐，耐久都是 99
        commands.add(replace(backpack(1), tool("sword", 99)));
        commands.add(replace(backpack(2), tool("pickaxe", 99)));
        commands.add(replace(backpack(3), tool("pickaxe", 99)));
        commands.add(replace(backpack(4), tool("shovel", 99)));
        commands.add(replace(backpack(5), tool("pickaxe", 99)));

        // 背包 6~11：半 / 空 / 空
        commands.add(replace(backpack(6), partBox("minecraft:cobblestone", 13, 32)));
        commands.add(replace(backpack(7), EMPTY_BOX));
        commands.add(replace(backpack(8), EMPTY_BOX));
        commands.add(replace(backpack(9), partBox("minecraft:slime_block", 13, 32)));
        commands.add(replace(backpack(10), EMPTY_BOX));
        commands.add(replace(backpack(11), EMPTY_BOX));

        // 背包 12：熔岩桶
        commands.add(replace(backpack(12), "minecraft:lava_bucket"));

        // 背包 13~15：收集盒（深板岩满 / 青金石满 / 2 组方解石 + 2 组紫水晶块）
        commands.add(replace(backpack(13), fullBox("minecraft:deepslate")));
        commands.add(replace(backpack(14), fullBox("minecraft:lapis_lazuli")));
        commands.add(replace(backpack(15), mixedBox("minecraft:calcite", 2, "minecraft:amethyst_block", 2)));
        for (int i = 16; i <= 21; i++) commands.add(replace(backpack(i), EMPTY_BOX));

        // 该空的：快捷栏 6~8、背包 22~27、副手
        for (int i = 5; i <= 7; i++) commands.add(replace(hotbar(i), AIR));
        for (int i = 22; i <= 27; i++) commands.add(replace(backpack(i), AIR));
        commands.add(replace(OFFHAND, AIR));

        // 食物放最后
        addCounted(commands, hotbar(8), "minecraft:cooked_porkchop", 8);
        return commands;
    }

    // ------------------------------------------------------------------
    // 指令 / 物品字符串
    // ------------------------------------------------------------------

    private static String replace(String slot, String item) {
        return "item replace entity @s " + slot + " with " + item;
    }

    /**
     * 放一个「要 {@code count} 个」的东西：先 replace 放 1 个，再 give 补差额。
     *
     * <p>{@code /give} 会先找「同一种物品还有空位」的那一叠，所以补的会并到刚才那一格上。
     */
    private static void addCounted(List<String> commands, String slot, String itemId, int count) {
        commands.add(replace(slot, itemId));
        if (count > 1) {
            commands.add("give @s " + itemId + " " + (count - 1));
        }
    }

    /** 一把钻石工具：经验修补 + 耐久 III + 效率 V（剑再加锋利 V）；{@code damage > 0} 就带损伤。 */
    private static String tool(String kind, int damage) {
        StringBuilder sb = new StringBuilder("minecraft:diamond_").append(kind).append('[');
        sb.append("minecraft:enchantments={");
        sb.append("\"minecraft:mending\":1");
        sb.append(",\"minecraft:unbreaking\":3");
        sb.append(",\"minecraft:efficiency\":5");
        if ("sword".equals(kind)) sb.append(",\"minecraft:sharpness\":5");
        sb.append('}');
        if (damage > 0) sb.append(",minecraft:damage=").append(damage);
        return sb.append(']').toString();
    }

    /** 一盒装满的东西：27 格 × 64。 */
    private static String fullBox(String itemId) {
        return box(buildBoxSlots(itemId, 27, 64));
    }

    /** 半盒：{@code fullSlots} 格满的 + 最后一格 {@code lastCount} 个。 */
    private static String partBox(String itemId, int fullSlots, int lastCount) {
        StringBuilder slots = buildBoxSlots(itemId, fullSlots, 64);
        slots.append(",{slot:").append(fullSlots)
            .append(",item:{id:\"").append(itemId).append("\",count:").append(lastCount).append("}}");
        return box(slots);
    }

    /** 两种东西各 {@code firstStacks} / {@code secondStacks} 组（每组 64）。 */
    private static String mixedBox(String firstId, int firstStacks, String secondId, int secondStacks) {
        StringBuilder slots = new StringBuilder();
        int slot = 0;
        for (int i = 0; i < firstStacks; i++) {
            appendSlot(slots, slot++, firstId, 64);
        }
        for (int i = 0; i < secondStacks; i++) {
            appendSlot(slots, slot++, secondId, 64);
        }
        return box(slots);
    }

    private static StringBuilder buildBoxSlots(String itemId, int stacks, int count) {
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < stacks; i++) {
            appendSlot(slots, i, itemId, count);
        }
        return slots;
    }

    private static void appendSlot(StringBuilder slots, int slot, String itemId, int count) {
        if (slots.length() > 0) slots.append(',');
        slots.append("{slot:").append(slot)
            .append(",item:{id:\"").append(itemId).append("\",count:").append(count).append("}}");
    }

    private static String box(StringBuilder slots) {
        return "minecraft:shulker_box[minecraft:container=[" + slots + "]]";
    }
}
