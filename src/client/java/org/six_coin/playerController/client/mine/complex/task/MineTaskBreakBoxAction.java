package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

/**
 * 拆掉摆在世界里的一个潜影盒，并等它**进物品栏**。
 *
 * <p>用哪一格拆由调用方给：补货第一步已经把工具都放进 inventory_temp 了，所以那边会先从
 * 暂存处把镐子借出来（借不到才空手）。
 */
public class MineTaskBreakBoxAction extends Action {

    /** 挖掉之后最多等这么久让掉落物进物品栏（5 秒）。 */
    private static final int PICKUP_WAIT_TICKS = 100;

    private final BlockPos pos;
    private final int hotbarIndex;
    private final String label;

    @Nullable
    private MineBlockAction miner;
    private int boxesBefore;
    private int waitTicks;

    public MineTaskBreakBoxAction(BlockPos pos, String label) {
        this(pos, 0, label);
    }

    /**
     * @param hotbarIndex 用快捷栏第几格拆（补货里是「从物品栏暂存处借来的镐子」那一格）
     */
    public MineTaskBreakBoxAction(BlockPos pos, int hotbarIndex, String label) {
        this.pos = pos.immutable();
        this.hotbarIndex = hotbarIndex;
        this.label = label;
    }

    @Override
    public String name() {
        return "拆掉 " + pos.toShortString() + " 的" + label;
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
        miner = new MineBlockAction(pos, hotbarIndex);
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
            ChatUtils.debug("%s 已经吸进物品栏了（潜影盒 %d → %d）", label, boxesBefore, now);
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
}
