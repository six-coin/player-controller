package org.six_coin.playerController.client.mine.station;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mine.MineTools;

/**
 * 工具缓存里「什么算合法工具」。
 *
 * <p>合法的工具：剑 / 镐 / 斧 / 铲 / 锄，钻石或下界合金材质，
 * <b>必须</b>有经验修补（{@code mend}）、耐久 III（{@code unbreaking} 3 级），
 * 稿斧铲锄要有<em>效率 V</em>（{@code efficiency} 5 级），剑要有<em>锋利 V</em>（{@code sharpness} 5 级）。
 * 等级都是「至少」：等级比要求高也算合法。
 *
 * <p>「可用」和「待修复」的分界线是**耐久满不满**（见 {@link #isUsable}）：
 * 只要掉了一点耐久就是「待修复」，不参与「每种至少 2 件可用」的计数。
 *
 * <p>挖掘任务里真正在用的工具（快捷栏 / 背包那几格）不是这个标准：斧头、锄头掉到
 * {@value #AXE_HOE_MIN_DURABILITY} 以下才换，剑 / 镐 / 铲掉到 {@value #TOOL_MIN_DURABILITY}
 * 以下才换（见 {@link #minDurability}）。
 */
public final class MineToolRules {

    /** 五类工具（剑没有方块标签，别的有）。 */
    public enum Kind {

        SWORD("剑", "sword", ItemTags.SWORDS),
        PICKAXE("镐子", "pickaxe", ItemTags.PICKAXES),
        AXE("斧头", "axe", ItemTags.AXES),
        SHOVEL("铲子", "shovel", ItemTags.SHOVELS),
        HOE("锄头", "hoe", ItemTags.HOES);

        private final String label;
        private final String suffix;
        private final TagKey<Item> tag;

        Kind(String label, String suffix, TagKey<Item> tag) {
            this.label = label;
            this.suffix = suffix;
            this.tag = tag;
        }

        public String label() {
            return label;
        }

        public String suffix() {
            return suffix;
        }
    }

    /** 每种至少要几件「可用」的。 */
    public static final int REQUIRED_PER_KIND = 2;

    /** 镐子要用 4 把（快捷栏 1 把 + 背包 3 把备用），缓存里至少得备这么多。 */
    public static final int REQUIRED_PICKAXES = 4;

    /** 剑 / 镐 / 铲：剩这么点耐久就该换了（它们用得多，换得勤）。 */
    public static final int TOOL_MIN_DURABILITY = 200;

    /**
     * 斧头 / 锄头：剩 {@value #AXE_HOE_MIN_DURABILITY} 耐久以上都算能用。
     *
     * <p>这两种用得很少，要求「满耐久」的话每掉一点就得回一趟补货，不值当。
     */
    public static final int AXE_HOE_MIN_DURABILITY = 1200;

    /**
     * 这种工具「还能用」的最低剩余耐久 —— debug 前的检查和补货共用这一套。
     *
     * <p>斧头 / 锄头 {@value #AXE_HOE_MIN_DURABILITY}，其余 {@value #TOOL_MIN_DURABILITY}。
     */
    public static int minDurability(Kind kind) {
        return kind == Kind.AXE || kind == Kind.HOE
            ? AXE_HOE_MIN_DURABILITY : TOOL_MIN_DURABILITY;
    }

    private MineToolRules() {
    }

    /** 这一叠是五类工具里的哪一种；不是工具返回 null。 */
    @Nullable
    public static Kind kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (Kind kind : Kind.values()) {
            if (stack.is(kind.tag)) return kind;
        }
        return null;
    }

    /** 某个附魔的等级；没有是 0。 */
    public static int level(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        ItemEnchantments component =
            stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> entry : component.keySet()) {
            if (entry.is(enchantment)) return component.getLevel(entry);
        }
        return 0;
    }

    /** 材质是不是钻石 / 下界合金。 */
    public static boolean hasRequiredMaterial(ItemStack stack, Kind kind) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!"minecraft".equals(id.getNamespace())) return false;
        String path = id.getPath();
        return path.equals("diamond_" + kind.suffix) || path.equals("netherite_" + kind.suffix);
    }

    /** 合法工具（不看耐久）。 */
    public static boolean isLegal(ItemStack stack) {
        return illegalityReason(stack) == null;
    }

    /**
     * 不合法的原因（给问题列表用）；合法返回 null。
     */
    @Nullable
    public static String illegalityReason(ItemStack stack) {
        Kind kind = kindOf(stack);
        if (kind == null) return "不是剑 / 镐 / 斧 / 铲 / 锄";
        if (!hasRequiredMaterial(stack, kind)) return "材质不是钻石 / 下界合金";

        StringBuilder missing = new StringBuilder();
        if (level(stack, Enchantments.MENDING) < 1) missing.append("没有经验修补");
        if (level(stack, Enchantments.UNBREAKING) < 3) {
            if (missing.length() > 0) missing.append("、");
            missing.append("没有耐久 III");
        }
        if (kind == Kind.SWORD) {
            if (level(stack, Enchantments.SHARPNESS) < 5) {
                if (missing.length() > 0) missing.append("、");
                missing.append("没有锋利 V");
            }
        } else if (level(stack, Enchantments.EFFICIENCY) < 5) {
            if (missing.length() > 0) missing.append("、");
            missing.append("没有效率 V");
        }
        return missing.length() == 0 ? null : missing.toString();
    }

    /**
     * 「可用」= <b>耐久是满的</b>（{@code damage == 0}）。
     *
     * <p>需求里点名：缓存里的工具必须满耐久，不满耐久的都算「待修复」——
     * 待修复的不能算进「每种至少 2 件可用」，要拿去 mend 修。
     */
    public static boolean isUsable(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem() && stack.getDamageValue() == 0;
    }
}
