package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「子区块数据」：**物品栏**和**六个位置的潜影盒内容**的缓存。
 *
 * <p>需求里点名要这么缓存：这些判断（够不够、空不空、满没满）一次任务里要问上百遍，
 * 每次都去开箱子 / 遍历一遍太贵，所以开完容器就更新一次缓存，判断全读缓存。
 *
 * <p>缓存的更新时机：
 * <ul>
 *   <li>物品栏：每次要判断之前 {@link #refreshInventory}（读的是本地物品栏，同步的、不花钱）；</li>
 *   <li>潜影盒：放下的时候按物品自带的 {@code container} 组件记一份；
 *       之后每次打开（拿 / 放）完再按界面里的格子重记一份。</li>
 * </ul>
 */
public final class SingleStageData {

    /** 潜影盒一共 27 格。 */
    public static final int BOX_SLOTS = 27;

    /** 物品栏快照，下标和 {@link PlayerInventory#getStack(int)} 一致。 */
    private final ItemStack[] inventory = new ItemStack[41];

    /** 六个位置的盒子里装着什么（按方块坐标）。 */
    private final Map<Long, List<ItemStack>> boxes = new HashMap<>();

    public SingleStageData() {
        for (int i = 0; i < inventory.length; i++) inventory[i] = ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------
    // 物品栏
    // ------------------------------------------------------------------

    /** 把整份物品栏拍一张快照（判断之前调一次）。 */
    public void refreshInventory(PlayerInventory inv) {
        int size = Math.min(inventory.length, inv.size());
        for (int i = 0; i < size; i++) {
            inventory[i] = inv.getStack(i).copy();
        }
        for (int i = size; i < inventory.length; i++) inventory[i] = ItemStack.EMPTY;
    }

    public ItemStack stack(int index) {
        if (index < 0 || index >= inventory.length) return ItemStack.EMPTY;
        return inventory[index];
    }

    public boolean isEmpty(int index) {
        return stack(index).isEmpty();
    }

    /** 这一格是不是某种物品。 */
    public boolean is(int index, Item item) {
        return stack(index).isOf(item);
    }

    /** 整份物品栏里某种物品一共多少个。 */
    public int count(Item item) {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    /** 整份物品栏里 id 在 {@code ids} 里的物品一共多少个。 */
    public int count(Set<String> ids) {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (stack.isEmpty()) continue;
            if (ids.contains(idOf(stack))) total += stack.getCount();
        }
        return total;
    }

    /** 这一格能不能塞进东西（空格）。 */
    public boolean hasRoom(int index) {
        return isEmpty(index);
    }

    // ------------------------------------------------------------------
    // 潜影盒
    // ------------------------------------------------------------------

    /** 某个位置盒子里现在装着什么（没缓存过就是空列表）。 */
    public List<ItemStack> box(BlockPos pos) {
        List<ItemStack> cached = boxes.get(pos.asLong());
        return cached == null ? List.of() : cached;
    }

    /** 缓存一个盒子的内容（放下 / 开完箱之后调）。 */
    public void setBox(BlockPos pos, List<ItemStack> contents) {
        List<ItemStack> copy = new ArrayList<>(contents.size());
        for (ItemStack stack : contents) {
            if (!stack.isEmpty()) copy.add(stack.copy());
        }
        boxes.put(pos.asLong(), copy);
    }

    /** 盒子里有几格有东西。 */
    public int boxUsed(BlockPos pos) {
        return box(pos).size();
    }

    /** 盒子里一共几个物品。 */
    public int boxItems(BlockPos pos) {
        int total = 0;
        for (ItemStack stack : box(pos)) total += stack.getCount();
        return total;
    }

    public boolean boxEmpty(BlockPos pos) {
        return boxUsed(pos) == 0;
    }

    public boolean boxFull(BlockPos pos) {
        return boxUsed(pos) >= BOX_SLOTS;
    }

    /** 盒子里某种物品一共几个。 */
    public int boxCount(BlockPos pos, Item item) {
        int total = 0;
        for (ItemStack stack : box(pos)) {
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    /** 盒子里装的是不是只有这些物品（空盒子也算「只有这些」）。 */
    public boolean boxOnly(BlockPos pos, Set<Item> allowed) {
        for (ItemStack stack : box(pos)) {
            if (!allowed.contains(stack.getItem())) return false;
        }
        return true;
    }

    /** 盒子里有没有 {@code allowed} 以外的东西（返回第一个，用来报错）。 */
    @Nullable
    public ItemStack boxStray(BlockPos pos, Set<Item> allowed) {
        for (ItemStack stack : box(pos)) {
            if (!allowed.contains(stack.getItem())) return stack;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 静态小工具
    // ------------------------------------------------------------------

    public static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "空" : Registries.ITEM.getId(stack.getItem()).toString();
    }

    /** 从物品自带的组件里读潜影盒内容（不用打开就能读）。 */
    public static List<ItemStack> contentsOf(ItemStack box) {
        return ShulkerUtils.contents(box);
    }

    /** 读界面里这个容器的全部非空格子。 */
    public static List<ItemStack> contentsOf(ScreenHandler handler) {
        List<ItemStack> result = new ArrayList<>();
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            if (slot.getStack().isEmpty()) continue;
            result.add(slot.getStack().copy());
        }
        return result;
    }
}
