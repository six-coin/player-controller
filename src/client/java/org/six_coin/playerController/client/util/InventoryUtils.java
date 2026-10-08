package org.six_coin.playerController.client.util;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * 背包 / 容器相关的查询。
 *
 * <p>玩家背包的 27 个主格在 {@link PlayerInventory} 里的下标是 9..35（0..8 是快捷栏），
 * 在界面里就是「{@code slot.inventory == 玩家背包} 且 {@code slot.getIndex()} 落在 9..35」的那些槽位。
 */
public final class InventoryUtils {

    /** 主背包（27 格，不含快捷栏）在玩家背包里的起始下标。 */
    public static final int MAIN_START = 9;

    /** 主背包的结束下标（不含）。 */
    public static final int MAIN_END = 36;

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

    /** 容器自己的格子（不是玩家背包的那些）。 */
    public static List<Slot> containerSlots(ScreenHandler handler) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            result.add(slot);
        }
        return result;
    }

    /** 日志用：一个槽位的简单描述。 */
    public static String describe(Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) return "#" + slot.id + "（空）";
        return "#" + slot.id + " "
            + net.minecraft.registry.Registries.ITEM.getId(stack.getItem())
            + " x" + stack.getCount();
    }
}
