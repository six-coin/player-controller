package org.six_coin.playerController.client.util;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;

/**
 * 潜影盒的判断和内容读取。
 *
 * <p>潜影盒物品本身没有专门的类，原版（{@code ShulkerBoxSlot}）也是用
 * 「BlockItem + ShulkerBoxBlock」判断的；盒子里装的东西在
 * {@link DataComponentTypes#CONTAINER} 这个数据组件里。
 */
public final class ShulkerUtils {

    private ShulkerUtils() {
    }

    /** 这一叠是不是潜影盒。 */
    public static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty()
            && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** 潜影盒里装着的东西（不是潜影盒、或者空盒子就返回空列表）。 */
    public static List<ItemStack> contents(ItemStack stack) {
        List<ItemStack> result = new ArrayList<>();
        if (!isShulkerBox(stack)) return result;

        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return result;

        for (ItemStack inner : container.iterateNonEmpty()) {
            if (!inner.isEmpty()) result.add(inner.copy());
        }
        return result;
    }

    /**
     * 盒子里真正要看的那些东西：堆叠上限 1 的、命名过的都当没看见（见 {@link ItemRules}）。
     */
    public static List<ItemStack> consideredContents(ItemStack shulker) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack inner : contents(shulker)) {
            if (ItemRules.isIgnored(inner)) continue;
            result.add(inner);
        }
        return result;
    }

    /** 盒子里有没有 item_list 还要的东西（只看要看的那些）。 */
    public static boolean containsWanted(ItemStack shulker, ItemList itemList) {
        for (ItemStack inner : consideredContents(shulker)) {
            if (itemList.wants(inner.getItem())) return true;
        }
        return false;
    }

    /**
     * 这个盒子是不是「只装一种 item_list 还要的东西、而且**装满一整盒**」。
     *
     * <p>潜影盒固定 27 格，所以「满」= 27 × 那种物品的堆叠上限（64 上限的物品就是 1728 个）。
     * 备货第一阶段阶段3 的「整盒直挪」用它挑盒子：要的量够一整盒、仓库里又正好有整盒的话，
     * 就不用拆开重装了。
     */
    public static boolean isFullOfWanted(ItemStack shulker, ItemList itemList) {
        List<ItemStack> contents = consideredContents(shulker);
        if (contents.isEmpty()) return false;

        Item first = contents.get(0).getItem();
        if (!itemList.wants(first)) return false;

        int total = 0;
        for (ItemStack inner : contents) {
            if (!inner.isOf(first)) return false;
            total += inner.getCount();
        }
        return total >= 27 * Math.max(1, contents.get(0).getMaxCount());
    }

    /** 盒子内容的一行描述，给日志用（被无视的会标出来）。 */
    public static String describeContents(ItemStack shulker) {
        List<ItemStack> contents = contents(shulker);
        if (contents.isEmpty()) return "（空盒子）";

        StringBuilder sb = new StringBuilder();
        for (ItemStack inner : contents) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Registries.ITEM.getId(inner.getItem())).append(" x").append(inner.getCount());
            if (ItemRules.isIgnored(inner)) {
                sb.append("（无视：").append(ItemRules.ignoreReason(inner)).append("）");
            }
        }
        return sb.toString();
    }
}
