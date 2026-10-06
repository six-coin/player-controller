package org.six_coin.playerController.client.util;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/** 背包 / 容器相关的查询。 */
public final class InventoryUtils {

    private InventoryUtils() {
    }

    /** 玩家背包（含盔甲、副手）里某种物品的总数。 */
    public static int count(PlayerInventory inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 在当前界面里找到属于“容器”（而不是玩家背包）的第一个装着该物品的槽位。
     *
     * @return 槽位 id，找不到返回 -1
     */
    public static int findContainerSlot(ScreenHandler handler, PlayerInventory playerInventory, Item item) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory == playerInventory) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    /** 玩家背包里是否还有空位。 */
    public static boolean hasEmptySlot(PlayerInventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) return true;
        }
        return false;
    }

    /** 玩家背包还能不能装下这种物品（有空位，或者有没满的同类堆叠）。 */
    public static boolean canAccept(PlayerInventory inventory, Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) return true;
            if (stack.isOf(item) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }
}
