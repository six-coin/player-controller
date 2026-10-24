package org.six_coin.playerController.client.mine;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
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

        HOE("锄头", BlockTags.MINEABLE_WITH_HOE, ItemTags.HOES),
        PICKAXE("镐子", BlockTags.MINEABLE_WITH_PICKAXE, ItemTags.PICKAXES),
        AXE("斧头", BlockTags.MINEABLE_WITH_AXE, ItemTags.AXES),
        SHOVEL("铲子", BlockTags.MINEABLE_WITH_SHOVEL, ItemTags.SHOVELS);

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
        BlockState state = block.defaultBlockState();
        for (Kind kind : Kind.values()) {
            if (state.is(kind.blockTag())) return Optional.of(kind);
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
            if (stack.is(kind.itemTag())) return kind;
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
    public static int hotbarSlot(Inventory inventory, Kind kind) {
        ItemStack selected = inventory.getSelectedItem();
        if (selected.is(kind.itemTag()) && remaining(selected) >= MIN_DURABILITY) {
            return inventory.getSelectedSlot();
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(kind.itemTag()) && remaining(stack) >= MIN_DURABILITY) return i;
        }
        return -1;
    }

    /** 还剩多少耐久；不会坏的物品算无限。 */
    public static int remaining(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    // ------------------------------------------------------------------
    // 「秒破」判定
    // ------------------------------------------------------------------

    /**
     * 发包挖（同一 tick 里 START + STOP）要求的最小破坏进度。
     *
     * <p>服务端 {@code ServerPlayerGameMode#handleBlockBreakAction} 的 STOP 分支是
     * {@code getDestroyProgress * (elapsed + 1) >= 0.7}；我们同一 tick 就把 START / STOP 发完，
     * {@code elapsed = 0}，所以门槛就是 <b>0.7</b>。低于这个值的方块发多少包都挖不掉。
     */
    public static final float INSTA_THRESHOLD = 0.7F;

    /**
     * 这个方块在「手上拿着 {@code tool}」的时候能不能被一个 START+STOP 秒掉。
     *
     * @param tool     {@code null} / 空 = 空手
     * @param hardness 这个方块的硬度（{@code state.getDestroySpeed(level, pos)}）
     */
    public static boolean canInstaMine(BlockState state, @Nullable ItemStack tool,
                                       @Nullable LivingEntity player, float hardness) {
        if (hardness < 0.0F) return false;
        return destroyProgress(state, tool, player, hardness) >= INSTA_THRESHOLD;
    }

    /**
     * 复刻原版 {@code Player#getDestroySpeed} + {@code BlockState#getDestroyProgress} 的算法。
     *
     * <p>为什么不直接问游戏：{@code Player#getDestroySpeed} 用的是**当前选中格**的东西，
     * 而这里要算的是「换成另一件工具会怎样」。
     *
     * <p>和原版的两点差异（都是故意的）：
     * <ul>
     *   <li><b>不算「人在空中 ÷ 5」那一项</b>：悬停飞行时服务端记的 onGround 是不是 true 说不准，
     *       而实测挖得动，说明这一项没有生效；算上它会把几乎所有方块都判成「挖不动」；</li>
     *   <li>不算「眼睛泡在水里」那一项（挖掘时本来就不下水）。</li>
     * </ul>
     */
    public static float destroyProgress(BlockState state, @Nullable ItemStack tool,
                                        @Nullable LivingEntity player, float hardness) {
        boolean hasTool = tool != null && !tool.isEmpty();

        float speed = hasTool ? tool.getDestroySpeed(state) : 1.0F;
        if (speed > 1.0F && hasTool) {
            int efficiency = efficiencyLevel(tool);
            if (efficiency > 0) speed += efficiency * efficiency + 1.0F;
        }

        if (player != null) {
            if (MobEffectUtil.hasDigSpeed(player)) {
                speed *= 1.0F + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2F;
            }
            MobEffectInstance fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
            if (fatigue != null) {
                speed *= (float) Math.pow(0.3, fatigue.getAmplifier() + 1);
            }
            speed *= (float) player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        }

        boolean harvest = !state.requiresCorrectToolForDrops()
            || (hasTool && tool.isCorrectToolForDrops(state));
        int divisor = harvest ? 30 : 100;
        return speed / hardness / (float) divisor;
    }

    /** 效率附魔的等级；没有是 0。 */
    public static int efficiencyLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        ItemEnchantments component =
            stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> entry : component.keySet()) {
            if (entry.is(Enchantments.EFFICIENCY)) return component.getLevel(entry);
        }
        return 0;
    }
}
