package org.six_coin.playerController.client.mine.station;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mine.MineTools;

/**
 * 工具缓存里「什么算合法工具」。
 *
 * <p>合法的工具：剑 / 镐 / 斧 / 铲 / 锄，钻石或下界合金材质，
 * <b>必须</b>有经验修补（{@code mending}）、耐久 III（{@code unbreaking} 3 级），
 * 稿斧铲锄要有<em>效率 V</em>（{@code efficiency} 5 级），剑要有<em>锋利 V</em>（{@code sharpness} 5 级）。
 * 等级都是「至少」：等级比要求高也算合法。
 *
 * <p>「可用」和「待修复」的分界线是剩余耐久 {@value MineTools#MIN_DURABILITY}：
 * 不够的工具还是合法工具，只是算「待修复」，不参与「每种至少 2 件可用」的计数。
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

    private MineToolRules() {
    }

    /** 这一叠是五类工具里的哪一种；不是工具返回 null。 */
    @Nullable
    public static Kind kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (Kind kind : Kind.values()) {
            if (stack.isIn(kind.tag)) return kind;
        }
        return null;
    }

    /** 某个附魔的等级；没有是 0。 */
    public static int level(ItemStack stack, RegistryKey<Enchantment> enchantment) {
        ItemEnchantmentsComponent component =
            stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> entry : component.getEnchantments()) {
            if (entry.matchesKey(enchantment)) return component.getLevel(entry);
        }
        return 0;
    }

    /** 材质是不是钻石 / 下界合金。 */
    public static boolean hasRequiredMaterial(ItemStack stack, Kind kind) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
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

    /** 「可用」= 剩余耐久够（{@link MineTools#MIN_DURABILITY} 以上）。 */
    public static boolean isUsable(ItemStack stack) {
        return MineTools.remaining(stack) >= MineTools.MIN_DURABILITY;
    }
}
