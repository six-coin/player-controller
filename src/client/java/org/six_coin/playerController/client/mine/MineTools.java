package org.six_coin.playerController.client.mine;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 「这个方块该用什么工具」的查表和快捷栏找工具。
 *
 * <p>表是从原版那四个 {@code data/minecraft/tags/block/mineable/} 标签推出来的：
 * 锄头 → 镐子 → 斧头 → 铲子，谁先命中就算谁的（一个方块理论上只在一张表里）。
 * 查一次就缓存住，不会每挖一个方块都重新算一遍。
 *
 * <p>另外：剩余耐久少于 {@value #MIN_DURABILITY} 的工具算「不能用」，
 * 手上那件不够了会自动去找快捷栏里另一件同类工具。
 */
public final class MineTools {

    /** 四类工具。枚举的**顺序**就是方块标签的查找顺序（锄头 → 镐子 → 斧头 → 铲子）。 */
    public enum Kind {

        HOE("锄头", BlockTags.HOE_MINEABLE, ItemTags.HOES),
        PICKAXE("镐子", BlockTags.PICKAXE_MINEABLE, ItemTags.PICKAXES),
        AXE("斧头", BlockTags.AXE_MINEABLE, ItemTags.AXES),
        SHOVEL("铲子", BlockTags.SHOVEL_MINEABLE, ItemTags.SHOVELS);

        private final String label;
        private final TagKey<Block> blockTag;
        private final TagKey<Item> itemTag;

        Kind(String label, TagKey<Block> blockTag, TagKey<Item> itemTag) {
            this.label = label;
            this.blockTag = blockTag;
            this.itemTag = itemTag;
        }

        public String label() {
            return label;
        }

        public TagKey<Block> blockTag() {
            return blockTag;
        }

        public TagKey<Item> itemTag() {
            return itemTag;
        }
    }

    /** 方块 -> 该用什么工具（空 = 什么工具都能挖）。 */
    private static final Map<Block, Optional<Kind>> REQUIRED = new HashMap<>();

    private MineTools() {
    }

    /** 重新登录 / 换服务器之后方块标签可能不一样，缓存要清掉。 */
    public static void clearCache() {
        REQUIRED.clear();
    }

    /** 这个方块要哪一类工具；{@code null} = 不在那四张表里，拿什么都能挖。 */
    @Nullable
    public static Kind required(BlockState state) {
        return REQUIRED.computeIfAbsent(state.getBlock(), MineTools::lookup).orElse(null);
    }

    private static Optional<Kind> lookup(Block block) {
        BlockState state = block.getDefaultState();
        for (Kind kind : Kind.values()) {
            if (state.isIn(kind.blockTag())) return Optional.of(kind);
        }
        return Optional.empty();
    }

    /**
     * 剩余耐久少于这个数的工具**不用**（{@code /pc mine simple} 里它会去快捷栏找别的；
     * 一个都没有就算这一类工具没有）。
     */
    public static final int MIN_DURABILITY = 100;

    /** 手上这件东西算哪一类工具；{@code null} = 不是这四类（空手、剑、方块…）或者耐久不够。 */
    @Nullable
    public static Kind held(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (remaining(stack) < MIN_DURABILITY) return null;
        for (Kind kind : Kind.values()) {
            if (stack.isIn(kind.itemTag())) return kind;
        }
        return null;
    }

    /**
     * 快捷栏里哪一格放着一件 {@code kind} 这类工具。
     *
     * <p>手上已经是这一类就不动（返回当前选中格）；否则按快捷栏顺序找第一件。
     * 剩余耐久少于 {@link #MIN_DURABILITY} 的跳过。
     *
     * @return -1 = 快捷栏里没有
     */
    public static int hotbarSlot(PlayerInventory inventory, Kind kind) {
        ItemStack selected = inventory.getSelectedStack();
        if (selected.isIn(kind.itemTag()) && remaining(selected) >= MIN_DURABILITY) {
            return inventory.getSelectedSlot();
        }
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isIn(kind.itemTag()) && remaining(stack) >= MIN_DURABILITY) return i;
        }
        return -1;
    }

    /** 还剩多少耐久；不会坏的物品算无限。 */
    public static int remaining(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }
}
