package org.six_coin.playerController.client.container;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面里那些格子的查询：玩家背包的哪几格、容器的格子、空格、能合并的半叠。
 *
 * <p>玩家背包的 27 个主格在 {@link Inventory} 里的下标是 9..35（0..8 是快捷栏），
 * 在界面里就是「{@code slot.container == 玩家背包} 且 {@code slot.getContainerSlot()} 落在 9..35」的那些槽位。
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
    public static List<Slot> mainSlots(AbstractContainerMenu handler, Inventory inventory) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isMainSlot(slot, inventory)) result.add(slot);
        }
        return result;
    }

    /** 是不是玩家主背包（不含快捷栏）的槽位。 */
    public static boolean isMainSlot(Slot slot, Inventory inventory) {
        if (slot.container != inventory) return false;
        int index = slot.getContainerSlot();
        return index >= MAIN_START && index < MAIN_END;
    }

    /** 主背包里第一个空格，没有就返回 null。 */
    public static Slot firstEmptyMainSlot(AbstractContainerMenu handler, Inventory inventory) {
        for (Slot slot : mainSlots(handler, inventory)) {
            if (slot.isActive() && slot.getItem().isEmpty()) return slot;
        }
        return null;
    }

    /**
     * 主背包里能再放下 amount 个这种物品的同类半叠（有的话优先用它，省格子）。
     *
     * @return 找不到返回 null
     */
    public static Slot mergeTarget(AbstractContainerMenu handler, Inventory inventory,
                                   ItemStack stack, int amount) {
        for (Slot slot : mainSlots(handler, inventory)) {
            if (roomFor(slot, stack) >= amount) return slot;
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
    public static Slot dumpTarget(AbstractContainerMenu handler, Inventory inventory, ItemStack stack) {
        for (Slot slot : targetsIn(mainSlots(handler, inventory), stack)) {
            return slot;
        }
        return null;
    }

    /**
     * 能把**这一整叠**一次放下的主背包格子：优先同类半叠（剩下的空位够放下整叠），其次空格。
     *
     * <p>「一次放下」是左键一下点完的意思：半叠空位不够的话点下去只会放进去一部分，
     * 剩下的还留在光标上，所以这里要求空位 ≥ 这一叠的数量。
     *
     * @return 找不到返回 null
     */
    public static Slot wholeTarget(AbstractContainerMenu handler, Inventory inventory, ItemStack stack) {
        Slot empty = null;

        for (Slot slot : mainSlots(handler, inventory)) {
            if (!slot.isActive() || !slot.mayPlace(stack)) continue;
            if (slot.getItem().isEmpty()) {
                if (empty == null) empty = slot;
                continue;
            }
            if (roomFor(slot, stack) >= stack.getCount()) return slot;
        }
        return empty;
    }

    /**
     * 这一格还能再收下几个这种物品（不是同一种、装满了、格子用不了都是 0）。
     *
     * <p>上限取「这一格对这种物品的上限」和「这一叠自己的上限」里小的那个，跟原版合并时算的一样。
     */
    public static int roomFor(Slot slot, ItemStack stack) {
        if (stack.isEmpty() || !slot.isActive() || !slot.mayPlace(stack)) return 0;

        int cap = Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize());
        ItemStack current = slot.getItem();
        if (current.isEmpty()) return cap;
        if (!ItemStack.isSameItemSameComponents(current, stack)) return 0;
        return Math.max(0, cap - current.getCount());
    }

    /**
     * 从这些格子里挑「放这一叠」的目标，顺序是：**先同类半叠**（继续往里合，省得把同一种东西摊成好几格），
     * 再空格（按界面里的先后顺序）。
     */
    public static List<Slot> targetsIn(List<Slot> slots, ItemStack stack) {
        List<Slot> merge = new ArrayList<>();
        List<Slot> empty = new ArrayList<>();

        for (Slot slot : slots) {
            if (!slot.isActive() || !slot.mayPlace(stack)) continue;
            if (slot.getItem().isEmpty()) {
                empty.add(slot);
            } else if (roomFor(slot, stack) > 0) {
                merge.add(slot);
            }
        }

        merge.addAll(empty);
        return merge;
    }

    /** 容器自己的格子（不是玩家背包的那些）。 */
    public static List<Slot> containerSlots(AbstractContainerMenu handler) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory) continue;
            result.add(slot);
        }
        return result;
    }

    /** 玩家快捷栏某一格（0~8，也就是背包下标）在这套界面里的槽位。 */
    public static Slot hotbarSlot(AbstractContainerMenu handler, int index) {
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == index) return slot;
        }
        return null;
    }

    /**
     * 玩家物品栏里第 {@code index} 格在这套界面里的槽位。
     *
     * <p>下标和 {@link Inventory#getStack(int)} 一致：0~8 快捷栏、9~35 主背包、
     * 36~39 盔甲、{@link Inventory#OFF_HAND_SLOT} 副手。
     * 末影箱 / 箱子那类界面里没有盔甲和副手格，那就返回 null。
     */
    public static Slot inventorySlot(AbstractContainerMenu handler, Inventory inventory, int index) {
        for (Slot slot : handler.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == index) return slot;
        }
        return null;
    }

    /** 日志用：一个槽位的简单描述。 */
    public static String describe(Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return "#" + slot.index + "（空）";
        return "#" + slot.index + " " + describe(stack);
    }

    /** 日志用：一叠物品的 id、数量；有耐久的把剩余耐久也带上（不然「为什么不合格」看不出来）。 */
    public static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";

        StringBuilder sb = new StringBuilder();
        sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
            .append(" x").append(stack.getCount());
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            sb.append("（剩余耐久 ").append(stack.getMaxDamage() - stack.getDamageValue())
                .append("/").append(stack.getMaxDamage()).append("）");
        }
        return sb.toString();
    }
}
