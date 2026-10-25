package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 拆掉摆在世界里的一个潜影盒，并等它**进物品栏**。
 *
 * <p>一律<b>空手快挖</b>（同一 tick 发 START + 挥手 + STOP，见
 * {@link MineBlockAction#fastEmpty(BlockPos)}）：不吃镐子耐久，也不用再从暂存处借镐子。
 * 26.3 服务端 STOP 一到就碎，潜影盒硬度 2 也是瞬间的事。
 *
 * <p>挖之前会拍一张「哪几格有潜影盒」，挖完用 {@link #pickedSlot()} 告诉调用方
 * 盒子落到了哪一格（后面要按位置放回容器）。
 */
public class MineTaskBreakBoxAction extends Action {

    /** 挖掉之后最多等这么久让掉落物进物品栏（5 秒）。 */
    private static final int PICKUP_WAIT_TICKS = 100;

    private final BlockPos pos;
    private final String label;

    private MineBlockAction miner;
    private int boxesBefore;
    /** 挖之前哪几格是潜影盒。 */
    private final Set<Integer> boxSlotsBefore = new LinkedHashSet<>();
    /** 挖下来那个盒子落到了哪一格（还没吸进来是 -1）。 */
    private int pickedSlot = -1;
    private int waitTicks;

    public MineTaskBreakBoxAction(BlockPos pos, String label) {
        this.pos = pos.immutable();
        this.label = label;
    }

    @Override
    public String name() {
        return "拆掉 " + pos.toShortString() + " 的" + label;
    }

    /** 挖下来的盒子现在在哪一格（{@code PlayerInventory} 下标）；没吸进来是 -1。 */
    public int pickedSlot() {
        return pickedSlot;
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        boxesBefore = countBoxes(player);
        boxSlotsBefore.clear();
        boxSlotsBefore.addAll(boxSlots(player));
        miner = MineBlockAction.fastEmpty(pos);
        miner.start();
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        if (miner != null) {
            miner.update();
            if (!miner.isFinished()) return;

            MineBlockAction done = miner;
            miner = null;
            done.cleanup();
            if (done.failureReason() != null) {
                fail("拆" + label + "失败：" + done.failureReason());
                return;
            }
            waitTicks = 0;
            return;
        }

        int now = countBoxes(player);
        if (now > boxesBefore) {
            pickedSlot = findNewBoxSlot(player);
            ChatUtils.debug("%s 已经吸进物品栏了（潜影盒 %d → %d，落在第 %d 格）",
                label, boxesBefore, now, pickedSlot);
            finish();
            return;
        }

        if (++waitTicks > PICKUP_WAIT_TICKS) {
            fail("拆掉 " + label + " 之后等了 5 秒，物品栏里没多出潜影盒（掉到别处了？）");
        }
    }

    @Override
    protected void onEnd() {
        if (miner != null) {
            miner.cleanup();
            miner = null;
        }
    }

    /** 物品栏（含副手）里有几个潜影盒。 */
    private static int countBoxes(LocalPlayer player) {
        int count = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ShulkerUtils.isShulkerBox(stack)) count++;
        }
        if (ShulkerUtils.isShulkerBox(player.getInventory().getItem(MineTaskKit.OFFHAND))) count++;
        return count;
    }

    /** 现在哪几格是潜影盒。 */
    private static Set<Integer> boxSlots(LocalPlayer player) {
        Set<Integer> slots = new LinkedHashSet<>();
        for (int slot = 0; slot <= 35; slot++) {
            if (ShulkerUtils.isShulkerBox(player.getInventory().getItem(slot))) slots.add(slot);
        }
        return slots;
    }

    /**
     * 刚吸进来的那个盒子在哪一格：**挖之前不是潜影盒、现在是的**那一格。
     *
     * <p>潜影盒不能堆叠，所以一次只会多出一格；找不到就返回 -1（调用方按「不知道在哪」处理）。
     */
    private int findNewBoxSlot(LocalPlayer player) {
        for (int slot = 0; slot <= 35; slot++) {
            if (boxSlotsBefore.contains(slot)) continue;
            if (ShulkerUtils.isShulkerBox(player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }
}
