package org.six_coin.playerController.client.container;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面里那些格子的查询：玩家背包的哪几格、容器的格子、空格、能合并的半叠。
 *
 * <p>玩家背包的 27 个主格在 {@link PlayerInventory} 里的下标是 9..35（0..8 是快捷栏），
 * 在界面里就是「{@code slot.inventory == 玩家背包} 且 {@code slot.getIndex()} 落在 9..35」的那些槽位。
 */
public final class InventoryUtils {

    /** 主背包（27 格，不含快捷栏）在玩家背包里的起始下标。 */
    public static final int MAIN_START = 9;

    /** 主背包的结束下标（不含）。 */
    public static final int MAIN_END = 36;

    /** 整个物品栏（含快捷栏，不含副手）的结束下标（不含）。 */
    public static final int INVENTORY_END = 36;

    private InventoryUtils() {
    }

    /** 玩家背包的 27 个主格（不含快捷栏）在这套界面里的槽位。 */
    public static List<Slot> mainSlots(ScreenHandler handler, PlayerInventory inventory) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isMainSlot(slot, inventory)) result.add(slot);
        }
        return result;
    }

    /** 是不是玩家主背包（不含快捷栏）的槽位。 */
    public static boolean isMainSlot(Slot slot, PlayerInventory inventory) {
        if (slot.inventory != inventory) return false;
        int index = slot.getIndex();
        return index >= MAIN_START && index < MAIN_END;
    }

    /** 主背包里第一个空格，没有就返回 null。 */
    public static Slot firstEmptyMainSlot(ScreenHandler handler, PlayerInventory inventory) {
        for (Slot slot : mainSlots(handler, inventory)) {
            if (slot.isEnabled() && slot.getStack().isEmpty()) return slot;
        }
        return null;
    }

    /**
     * 主背包里能再放下 amount 个这种物品的同类半叠（有的话优先用它，省格子）。
     *
     * @return 找不到返回 null
     */
    public static Slot mergeTarget(ScreenHandler handler, PlayerInventory inventory,
                                   ItemStack stack, int amount) {
        for (Slot slot : mainSlots(handler, inventory)) {
            if (!slot.isEnabled()) continue;
            ItemStack current = slot.getStack();
            if (current.isEmpty()) continue;
            if (!ItemStack.areItemsAndComponentsEqual(current, stack)) continue;
            if (current.getMaxCount() - current.getCount() >= amount) return slot;
        }
        return null;
    }

    /**
     * 整叠往主背包放的时候，下一个该点哪个格子：优先同类半叠（能合并），其次空格。
     *
     * <p>只看主背包 27 格，绝对不会碰快捷栏。
     *
     * @return 找不到能放的格子返回 null
     */
    public static Slot dumpTarget(ScreenHandler handler, PlayerInventory inventory, ItemStack stack) {
        Slot merge = null;
        Slot empty = null;

        for (Slot slot : mainSlots(handler, inventory)) {
            if (!slot.isEnabled() || !slot.canInsert(stack)) continue;
            ItemStack current = slot.getStack();
            if (current.isEmpty()) {
                if (empty == null) empty = slot;
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(current, stack)) continue;
            if (current.getCount() >= current.getMaxCount()) continue;
            if (merge == null) merge = slot;
        }
        return merge != null ? merge : empty;
    }

    /** 容器自己的格子（不是玩家背包的那些）。 */
    public static List<Slot> containerSlots(ScreenHandler handler) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            result.add(slot);
        }
        return result;
    }

    /**
     * 容器里能收下这一叠的格子：空格，或者装着同样东西、还没满的格子。
     *
     * <p>这是「看着像能放」的判断，只有**界面里的格子数据**做依据 —— 真放得下多少，
     * 得由服务端点一下（Shift 点击）才算数。
     *
     * @return 找不到返回 null
     */
    public static Slot acceptTarget(ScreenHandler handler, ItemStack stack) {
        for (Slot slot : containerSlots(handler)) {
            if (!slot.canInsert(stack)) continue;

            ItemStack existing = slot.getStack();
            if (existing.isEmpty()) return slot;
            if (ItemStack.areItemsAndComponentsEqual(existing, stack)
                && existing.getCount() < Math.min(slot.getMaxItemCount(stack), stack.getMaxCount())) {
                return slot;
            }
        }
        return null;
    }

    /** 玩家快捷栏某一格（0~8，也就是背包下标）在这套界面里的槽位。 */
    public static Slot hotbarSlot(ScreenHandler handler, int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) return slot;
        }
        return null;
    }

    /** 日志用：一个槽位的简单描述。 */
    public static String describe(Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) return "#" + slot.id + "（空）";
        return "#" + slot.id + " " + describe(stack);
    }

    /** 日志用：一叠物品的 id、数量；有耐久的把剩余耐久也带上（不然「为什么不合格」看不出来）。 */
    public static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";

        StringBuilder sb = new StringBuilder();
        sb.append(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()))
            .append(" x").append(stack.getCount());
        if (stack.isDamageable() && stack.getMaxDamage() > 0) {
            sb.append("（剩余耐久 ").append(stack.getMaxDamage() - stack.getDamage())
                .append("/").append(stack.getMaxDamage()).append("）");
        }
        return sb.toString();
    }
}
