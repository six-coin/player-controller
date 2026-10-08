package org.six_coin.playerController.client.util;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
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

    /** 盒子里有没有 item_list 还要的东西。 */
    public static boolean containsWanted(ItemStack shulker, ItemList itemList) {
        for (ItemStack inner : contents(shulker)) {
            if (itemList.wants(inner.getItem())) return true;
        }
        return false;
    }

    /** 盒子内容的一行描述，给日志用。 */
    public static String describeContents(ItemStack shulker) {
        List<ItemStack> contents = contents(shulker);
        if (contents.isEmpty()) return "（空盒子）";

        StringBuilder sb = new StringBuilder();
        for (ItemStack inner : contents) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Registries.ITEM.getId(inner.getItem())).append(" x").append(inner.getCount());
        }
        return sb.toString();
    }
}
