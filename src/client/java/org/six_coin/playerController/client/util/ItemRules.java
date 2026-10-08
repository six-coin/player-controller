package org.six_coin.playerController.client.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * 「这一叠东西要不要当没看见」的规则。
 *
 * <p>两条：
 * <ul>
 *   <li><b>堆叠上限是 1 的物品</b>（工具、盔甲、床……）—— 无视；</li>
 *   <li><b>命名过的物品</b>（有 custom_name 或者 item_name 组件）—— 无视。</li>
 * </ul>
 *
 * <p>规则用在两个地方：遍历容器格子时的普通物品、以及潜影盒里装的东西。
 *
 * <p>注意：潜影盒自己也是堆叠上限 1 的物品，但遍历的时候它先走「潜影盒」那条规则，
 * 所以不会被这里的「堆叠上限 1」无视掉；不过盒子要是被命名过，还是会被无视（命名规则适用所有物品）。
 */
public final class ItemRules {

    private ItemRules() {
    }

    /** 堆叠上限是 1（工具、盔甲、潜影盒这种）。 */
    public static boolean isUnstackable(ItemStack stack) {
        return stack.getMaxCount() <= 1;
    }

    /** 命名过的物品的名字，没名字返回 null（custom_name 优先，其次 item_name）。 */
    @Nullable
    public static String nameOf(ItemStack stack) {
        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (custom != null) return custom.getString();

        Text itemName = stack.get(DataComponentTypes.ITEM_NAME);
        if (itemName != null) return itemName.getString();

        return null;
    }

    /** 名字是从哪个组件来的（日志里说清楚，免得误判看不出来）。 */
    public static String nameSource(ItemStack stack) {
        if (stack.get(DataComponentTypes.CUSTOM_NAME) != null) return "custom_name";
        if (stack.get(DataComponentTypes.ITEM_NAME) != null) return "item_name";
        return "";
    }

    /** 这一叠要不要无视（true = 当没看见）。 */
    public static boolean isIgnored(ItemStack stack) {
        return isUnstackable(stack) || nameOf(stack) != null;
    }

    /** 给日志用：为什么无视。 */
    public static String ignoreReason(ItemStack stack) {
        String name = nameOf(stack);
        boolean unstackable = isUnstackable(stack);

        if (name != null && unstackable) {
            return "堆叠上限 1 + 有名字（" + name + "，" + nameSource(stack) + "）";
        }
        if (name != null) return "有名字（" + name + "，" + nameSource(stack) + "）";
        if (unstackable) return "堆叠上限 1";
        return "";
    }
}
