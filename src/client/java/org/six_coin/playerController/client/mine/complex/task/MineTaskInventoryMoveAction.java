package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 在**玩家自己的物品栏**里按清单搬东西（不开容器，直接点 {@code player.inventoryMenu} 的格子）。
 *
 * <p>用途：开始挖掘前把「9 个空潜影盒 + 1 个熔岩桶」从玩家摆好的位置挪到
 * {@code debug_chunk_miner} 要求的那几格（背包 13~21、背包 12）。
 *
 * <p>每一趟都是「拿起 → 放进目标格」；目标格必须空着（搬之前物品栏是受控的）。
 * 点完立刻按物品栏核对，对不上就报错，不会静悄悄地把东西丢在光标上。
 */
public class MineTaskInventoryMoveAction extends Action {

    /** 一次搬运：{@code from} / {@code to} 都是 {@code PlayerInventory} 下标。 */
    public record Move(int from, int to, String note) {
    }

    private final List<Move> moves;

    public MineTaskInventoryMoveAction(List<Move> moves) {
        this.moves = List.copyOf(moves);
    }

    @Override
    public String name() {
        return "整理物品栏（" + moves.size() + " 件）";
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        // 刚关掉容器的话先等一下：服务端那边还没关，点物品栏的包会被丢掉
        if (!ContainerSession.inventorySettled()) return;

        AbstractContainerMenu handler = player.containerMenu;
        if (handler != player.inventoryMenu) {
            fail("现在开着别的界面，没法整理物品栏");
            return;
        }
        if (!handler.getCarried().isEmpty()) {
            fail("光标上还有物品，先放下");
            return;
        }

        List<String> problems = new ArrayList<>();
        for (Move move : moves) {
            Slot from = InventoryUtils.inventorySlot(handler, player.getInventory(), move.from());
            Slot to = InventoryUtils.inventorySlot(handler, player.getInventory(), move.to());
            if (from == null || to == null) {
                problems.add("找不到物品栏第 " + (from == null ? move.from() : move.to()) + " 格");
                continue;
            }

            if (from.getItem().isEmpty()) {
                // 已经在目标格了（上一次跑过一半）就不算错
                if (!to.getItem().isEmpty()) continue;
                problems.add("第 " + move.from() + " 格是空的（" + move.note() + "）");
                continue;
            }
            if (!to.getItem().isEmpty()) {
                problems.add("第 " + move.to() + " 格被 " + InventoryUtils.describe(to.getItem())
                    + " 占着（" + move.note() + "）");
                continue;
            }

            if (!ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "拿起" + move.note())) {
                problems.add("拿不起第 " + move.from() + " 格");
                continue;
            }
            if (handler.getCarried().isEmpty()) {
                problems.add("拿起第 " + move.from() + " 格没生效");
                continue;
            }
            if (!ContainerClicks.click(handler, to, 0, ContainerInput.PICKUP,
                "把" + move.note() + "放进第 " + move.to() + " 格")) {
                // 放不进去：放回原格，别丢在光标上
                ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "放回原格");
                problems.add("放不进第 " + move.to() + " 格");
                continue;
            }
            if (!handler.getCarried().isEmpty()) {
                ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "剩下的放回原格");
                problems.add("第 " + move.to() + " 格塞不下整叠（" + move.note() + "）");
            }
        }

        if (!problems.isEmpty()) {
            fail("整理物品栏失败：" + String.join("；", problems));
            return;
        }
        ChatUtils.debug("物品栏整理好了：%d 件都到位了", moves.size());
        finish();
    }
}
