package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 把物品栏某一格的东西换到**副手**（容器界面里没有副手格，所以只能等关掉容器之后在
 * 玩家自己的界面里做）。
 *
 * <p>用途：备货最后把不死图腾从背包挪到副手。
 */
public class MineTaskOffhandAction extends Action {

    private final int fromSlot;
    private int step;

    public MineTaskOffhandAction(int fromSlot) {
        this.fromSlot = fromSlot;
    }

    @Override
    public String name() {
        return "把物品栏第 " + fromSlot + " 格换到副手";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (player.containerMenu != player.inventoryMenu) {
            fail("现在开着别的界面，没法换副手");
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            fail("光标上还有物品，先放下");
            return;
        }
    }

    @Override
    protected void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        AbstractContainerMenu handler = player.containerMenu;
        if (handler != player.inventoryMenu) {
            fail("现在开着别的界面，没法换副手");
            return;
        }

        Slot from = InventoryUtils.inventorySlot(handler, player.getInventory(), fromSlot);
        Slot offhand = InventoryUtils.inventorySlot(handler, player.getInventory(),
            MineTaskKit.OFFHAND);
        if (from == null || offhand == null) {
            fail("找不到物品栏第 " + fromSlot + " 格或者副手格");
            return;
        }

        switch (step) {
            case 0 -> {
                if (!offhand.getItem().isEmpty()) {
                    // 副手已经有东西了（多半就是图腾）：不再动它
                    ChatUtils.debug("副手已经有 %s，跳过换手", InventoryUtils.describe(offhand.getItem()));
                    finish();
                    return;
                }
                if (from.getItem().isEmpty()) {
                    fail("物品栏第 " + fromSlot + " 格是空的，没东西可换到副手");
                    return;
                }
                if (!ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP,
                    "拿起第 " + fromSlot + " 格的东西")) {
                    fail("拿不起物品栏第 " + fromSlot + " 格的东西");
                    return;
                }
                step = 1;
            }
            case 1 -> {
                if (handler.getCarried().isEmpty()) return;      // 本地预测还没跟上
                if (!ContainerClicks.click(handler, offhand, 0, ContainerInput.PICKUP,
                    "放进副手")) {
                    fail("放不进副手");
                    return;
                }
                step = 2;
            }
            default -> {
                if (!handler.getCarried().isEmpty()) {
                    // 副手放不下：放回原格，别把东西丢在光标上
                    ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "放回原格");
                    fail("副手放不下 " + InventoryUtils.describe(handler.getCarried()));
                    return;
                }
                if (!offhand.getItem().isEmpty()) {
                    ChatUtils.debug("副手现在是 %s", InventoryUtils.describe(offhand.getItem()));
                }
                finish();
            }
        }
    }

}
