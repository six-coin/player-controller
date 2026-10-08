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
 *   <li><b>改过名字的物品</b> —— 无视。</li>
 * </ul>
 *
 * <p>「改过名字」只认 {@code custom_name} 组件（铁砧改名、或者命令里写 custom_name 的那种）。
 * 不能看 {@code item_name}：1.21 起每个物品本来就带着 item_name（石头就是「石头」），
 * 拿它判断的话所有物品都会被当成改过名字。
 *
 * <p>规则用在两个地方：遍历容器格子时的普通物品、以及潜影盒里装的东西。
 *
 * <p>注意：潜影盒自己也是堆叠上限 1 的物品，但遍历的时候它先走「潜影盒」那条规则，
 * 所以不会被这里的「堆叠上限 1」无视掉；不过盒子要是改过名字，还是会被无视。
 */
public final class ItemRules {

    private ItemRules() {
    }

    /** 堆叠上限是 1（工具、盔甲、潜影盒这种）。 */
    public static boolean isUnstackable(ItemStack stack) {
        return stack.getMaxCount() <= 1;
    }

    /** 这个物品被改过名字的话，返回改的名字；没改过返回 null。 */
    @Nullable
    public static String customNameOf(ItemStack stack) {
        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        return custom == null ? null : custom.getString();
    }

    /** 这一叠要不要无视（true = 当没看见）。 */
    public static boolean isIgnored(ItemStack stack) {
        return isUnstackable(stack) || customNameOf(stack) != null;
    }

    /** 给日志用：为什么无视。 */
    public static String ignoreReason(ItemStack stack) {
        String name = customNameOf(stack);
        boolean unstackable = isUnstackable(stack);

        if (name != null && unstackable) return "堆叠上限 1 + 改过名字（" + name + "）";
        if (name != null) return "改过名字（" + name + "）";
        if (unstackable) return "堆叠上限 1";
        return "";
    }
}
