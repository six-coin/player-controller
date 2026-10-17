package org.six_coin.playerController.client.mending;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * mending 用到的物品判定：这件东西「要不要修」。
 *
 * <p>要修 = 有耐久、耐久没满、而且带经验修补（等级无所谓，1 级就够）。
 *
 * <p>注意：这个版本的经验修补只修**装备栏**里的东西（主手、副手、四个盔甲格，
 * 见 {@code ExperienceOrbEntity.repairPlayerGears} 用的是
 * {@code EnchantmentHelper.chooseEquipmentWith(REPAIR_WITH_XP, ..)}），
 * 背包 / 末影箱里的东西不挪到副手就永远修不满 —— 所以 mending 才要往副手里换。
 */
public final class MendingItems {

    private MendingItems() {
    }

    /** 这一叠需不需要修：有耐久、耐久没满、带经验修补。 */
    public static boolean needsRepair(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isDamageable()) return false;
        if (stack.getDamage() <= 0) return false;
        return hasMending(stack);
    }

    /** 有没有经验修补。 */
    public static boolean hasMending(ItemStack stack) {
        ItemEnchantmentsComponent enchantments =
            stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> enchantment : enchantments.getEnchantments()) {
            if (enchantment.matchesKey(Enchantments.MENDING)) return true;
        }
        return false;
    }

    /** 还剩多少耐久；不会坏的物品算无限。 */
    public static int remainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }

    /** 缺口有多大（= 已经掉了多少耐久）；不会坏的物品是 0。 */
    public static int damage(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) return 0;
        return stack.getDamage();
    }
}
