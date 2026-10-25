package org.six_coin.playerController.client.stock;

import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.six_coin.playerController.client.mixin.ClientPlayerInteractionManagerAccessor;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 用快捷栏指定格里的工具，把某个方块挖掉。
 *
 * <p>需求里点名：**不要模拟**（不要去假装按键），直接发包。这里走的是原版客户端那套挖掘流程 ——
 * {@code attackBlock}（发 START_DESTROY_BLOCK）→ 每 tick {@code updateBlockBreakingProgress}
 * （累进度，够了客户端发 STOP_DESTROY_BLOCK 并把方块设成空气）。
 *
 * <p>挖之前会通过 {@link HotbarManager} 把工具那一格切过去、通过 {@link RotateManager}
 * 让服务端以为我们看着这个方块（客户端视角不动）。
 */
public class MineBlockAction extends Action {

    /** 挖一个方块最多等这么多 tick（潜影盒用钻石镐大概 8 tick）。 */
    private static final int MAX_MINE_TICKS = 200;

    /** 快挖：隔这么多 tick 还没掉就再发一次（26.3 服务端 STOP 直接碎，正常一次就完）。 */
    private static final int FAST_RETRY_TICKS = 3;

    /** 快挖：最多发几次，还挖不掉就报错。 */
    private static final int FAST_MAX_TRIES = 10;

    private final BlockPos pos;
    private final int hotbarIndex;

    /** 快挖：同一 tick 发 START + 挥手 + STOP（不等进度），要求手上是空的。 */
    private final boolean fast;

    private Direction side = Direction.UP;
    private int mineTicks;
    private int fastTries;
    /** 快挖时实际用的那一格快捷栏（-1 = 还没定）。 */
    private int hand = -1;

    /**
     * 挖之前那一格是什么方块。
     *
     * <p>判「挖掉了没有」**不能只看空气**：水里的海草 / 海带挖掉之后那一格变成**水**，
     * 一直不是空气，就会白白等到超时。
     */
    private Block original;

    public MineBlockAction(BlockPos pos, int hotbarIndex) {
        this(pos, hotbarIndex, false);
    }

    private MineBlockAction(BlockPos pos, int hotbarIndex, boolean fast) {
        this.pos = pos.immutable();
        this.hotbarIndex = hotbarIndex;
        this.fast = fast;
    }

    /**
     * 快挖：<b>同一 tick 发 START + 挥手 + STOP</b>，不累进度、不等它自己碎。
     *
     * <p>26.3 服务端改过（用户实测）：STOP 一到就直接把方块打掉，不看进度。所以连强化深板岩
     * 都能空手 2 个/tick，潜影盒这种硬度 2 的更是随便挖。
     *
     * <p>手上的东西必须<b>空</b>（空手挖不吃耐久）；{@code hotbarIndex} 那一格要是空的，
     * 调用方负责。
     */
    public static MineBlockAction fastEmpty(BlockPos pos, int hotbarIndex) {
        return new MineBlockAction(pos, hotbarIndex, true);
    }

    /**
     * 快挖，自己挑一格空的快捷栏当「空手」（优先手上本来就是空的，其次快捷栏 6~8，再其次 0~8）。
     */
    public static MineBlockAction fastEmpty(BlockPos pos) {
        return new MineBlockAction(pos, -1, true);
    }

    @Override
    public String name() {
        return (fast ? "快挖 " : "挖掉 ") + pos.toShortString()
            + (fast ? "（空手）" : "（用快捷栏第 " + (hotbarIndex + 1) + " 格）");
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (mc.gameMode == null) {
            fail("拿不到 interactionManager");
            return;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            ChatUtils.debug(pos.toShortString() + " 本来就是空气，不用挖");
            finish();
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("方块 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }

        int slot = fast ? handSlot(player) : hotbarIndex;
        if (slot < 0) {
            fail("快捷栏 1~9 都占着，没法空手挖 " + pos.toShortString()
                + "（挖潜影盒要用空手）");
            return;
        }
        if (!HotbarManager.get().request(Owner.STOCK, slot)) {
            fail("快捷栏第 " + (slot + 1) + " 格现在不让切过去（别的功能在用）");
            return;
        }

        ItemStack tool = player.getInventory().getItem(slot);
        side = PlayerUtils.facingSide(pos);
        RotateManager.get().rotateTo(Owner.STOCK, pos);

        original = state.getBlock();
        ChatUtils.debug("%s %s（%s，面 %s，手上 %s）", fast ? "快挖" : "开始挖",
            pos.toShortString(), BuiltInRegistries.BLOCK.getKey(state.getBlock()), side.getName(), describe(tool));

        if (fast) {
            hand = slot;
            breakNow(mc, player, pos);
            fastTries = 1;
            return;
        }
        if (!mc.gameMode.startDestroyBlock(pos, side)) {
            fail("挖不动 " + pos.toShortString() + "（够不着？）");
        }
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (broken(state)) {
            ChatUtils.debug(pos.toShortString() + " 已经挖掉了（用了 " + mineTicks + " tick，现在是 "
                + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）");
            finish();
            return;
        }

        if (fast) {
            // 服务端没认（延迟 / 打偏了）：隔几 tick 再发一次，别一直傻等
            if (++mineTicks % FAST_RETRY_TICKS != 0) return;
            if (++fastTries > FAST_MAX_TRIES) {
                fail("快挖 " + pos.toShortString() + " 失败（发了 " + fastTries + " 次，还是 "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）");
                return;
            }
            ChatUtils.debug("快挖 %s 没碎，再发一次（第 %d 次）", pos.toShortString(), fastTries);
            // 这中间 mine simple 可能把快捷栏抢回去了，重新切一次再发
            if (hand >= 0) HotbarManager.get().request(Owner.STOCK, hand);
            breakNow(mc, mc.player, pos);
            return;
        }

        if (++mineTicks > MAX_MINE_TICKS) {
            fail("挖 " + pos.toShortString() + " 超时（" + mineTicks + " tick，还是 "
                + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）");
            return;
        }

        mc.gameMode.continueDestroyBlock(pos, side);
    }

    @Override
    protected void onEnd() {
        HotbarManager.get().release(Owner.STOCK);
        RotateManager.get().release(Owner.STOCK);

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.level == null) return;
        if (!broken(mc.level.getBlockState(pos))) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    /**
     * 快挖时用哪一格当「空手」。
     *
     * <p>指定了格子（{@code hotbarIndex >= 0}）就只用那一格，不是空的就返回 -1
     * （空手挖必须真空手，拿着东西会掉耐久）；没指定就优先「手上本来就是空的」，
     * 其次快捷栏 6~8，再其次 0~8 里第一个空的。
     *
     * <p>快捷栏真的塞满了（卸完货物品栏是满的）就退一步用一格**不会掉耐久**的东西
     * （方块、食物之类）—— 服务端 STOP 直接碎，拿着什么不影响，只有会掉耐久的工具不能用。
     */
    private int handSlot(LocalPlayer player) {
        if (hotbarIndex >= 0) {
            return player.getInventory().getItem(hotbarIndex).isEmpty() ? hotbarIndex : -1;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).isEmpty()) return selected;
        for (int slot = 5; slot <= 7; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && !stack.isDamageableItem()) return slot;
        }
        return -1;
    }

    /**
     * 这一格现在算不算「已经挖掉了」。
     *
     * <p>不是空气也算：挖水里的海草 / 海带之后那一格是**水**（不是空气），
     * 只要「已经不是原来那个方块」就算挖掉了。
     */
    private boolean broken(BlockState state) {
        if (state.isAir()) return true;
        return original != null && !state.is(original);
    }

    /**
     * **立刻**发包打掉一个方块：START → 挥手 → STOP，不等它真的没了。
     *
     * <p>和 {@link MineBlockAction} 那套「按原版流程一点点累进度」不一样，这个是 nuker 的打法
     * （和 {@code MineManager} 里的一样）：一次把一堆方块全发出去，之后再统一按世界方块核对。
     * 用原版的 {@code sendSequencedPacket} 带 sequence 号。
     */
    public static void breakNow(Minecraft mc, LocalPlayer player, BlockPos pos) {
        if (mc.level == null || mc.gameMode == null) return;
        Direction side = PlayerUtils.facingSide(pos);
        ClientLevel level = mc.level;
        ClientPlayerInteractionManagerAccessor accessor =
            (ClientPlayerInteractionManagerAccessor) mc.gameMode;

        accessor.playerController$startPrediction(level, sequence ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side,
                sequence));
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        accessor.playerController$startPrediction(level, sequence ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, side,
                sequence));
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空手";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }
}
