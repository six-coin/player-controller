package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 在**玩家自己的物品栏**里搬东西：交换两格、把一格挪到另一格、把一格扔掉。
 *
 * <p>不开界面也能点：没有界面的时候原版当前的界面处理器就是 {@code playerScreenHandler}，
 * 服务端那边同样一直是它，所以 {@code clickSlot} 直接就能用（这也是「按数字键换格」的实现方式）。
 *
 * <p>点击是本地预测、立即生效的，所以一串操作在一个 tick 里就能走完。
 */
class SingleStageInventoryAction extends SingleStageStep {

    enum Kind {
        /** 点物品栏某一格、按「数字键」换到某个快捷栏格（交换）。 */
        SWAP,
        /** 把一格的东西挪到另一格（目标格必须空着或者同一种物品）。 */
        MOVE,
        /** 整叠扔掉。 */
        THROW
    }

    /** 一个操作；{@code a} / {@code b} 是 {@code PlayerInventory} 下标。 */
    record Op(Kind kind, int a, int b, String note) {
    }

    static Op swap(int inventoryIndex, int hotbarIndex) {
        return new Op(Kind.SWAP, inventoryIndex, hotbarIndex,
            "把第 " + inventoryIndex + " 格和第 " + (hotbarIndex + 1) + " 快捷栏格交换");
    }

    static Op move(int from, int to) {
        return new Op(Kind.MOVE, from, to, "把第 " + from + " 格挪到第 " + to + " 格");
    }

    static Op throwStack(int index) {
        return new Op(Kind.THROW, index, 0, "扔掉第 " + index + " 格");
    }

    /**
     * 立刻交换两格（不等动作队列）：点物品栏那一格、按 {@code hotbarIndex} 那个数字键。
     *
     * <p>给「挖着挖着工具快坏了，当场换一把」这种并行的小操作使用。
     */
    static boolean swapNow(LocalPlayer player, int inventoryIndex, int hotbarIndex) {
        AbstractContainerMenu handler = player.containerMenu;
        if (handler != player.inventoryMenu) return false;

        Slot slot = InventoryUtils.inventorySlot(handler, player.getInventory(), inventoryIndex);
        if (slot == null) return false;
        return ContainerClicks.click(handler, slot, hotbarIndex, ContainerInput.SWAP,
            "把第 " + inventoryIndex + " 格和第 " + (hotbarIndex + 1) + " 快捷栏格交换");
    }

    private final List<Op> ops;
    private int index;

    SingleStageInventoryAction(SingleStageCtx ctx, List<Op> ops) {
        super(ctx);
        this.ops = new ArrayList<>(ops);
    }

    @Override
    protected String phase() {
        return ops.isEmpty() ? "整理物品栏（空）" : ops.get(0).note();
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        AbstractContainerMenu handler = player.containerMenu;
        if (handler != player.inventoryMenu) {
            fail("现在开着别的界面，没法整理物品栏");
            return;
        }
        // 光标上有东西（多半是上一步容器动作收尾没清干净）：先塞回物品栏，别在这儿卡死
        if (!handler.getCarried().isEmpty()) {
            ChatUtils.debug("光标上还有 " + SingleStageData.idOf(handler.getCarried()) + "，先放回物品栏");
            InventoryUtils.clearCarried(handler, player);
            if (!handler.getCarried().isEmpty()) {
                fail("光标上还有 " + SingleStageData.idOf(handler.getCarried()) + "，放不回去");
                return;
            }
        }

        while (index < ops.size()) {
            Op op = ops.get(index++);
            if (!apply(handler, player, op)) return;
        }
        finish();
    }

    // ------------------------------------------------------------------

    private boolean apply(AbstractContainerMenu handler, LocalPlayer player, Op op) {
        switch (op.kind()) {
            case SWAP -> {
                Slot slot = InventoryUtils.inventorySlot(handler, player.getInventory(), op.a());
                if (slot == null) {
                    fail("找不到物品栏第 " + op.a() + " 格");
                    return false;
                }
                return ContainerClicks.click(handler, slot, op.b(), ContainerInput.SWAP, op.note());
            }
            case MOVE -> {
                return move(handler, player, op);
            }
            case THROW -> {
                Slot slot = InventoryUtils.inventorySlot(handler, player.getInventory(), op.a());
                if (slot == null) {
                    fail("找不到物品栏第 " + op.a() + " 格");
                    return false;
                }
                if (slot.getItem().isEmpty()) return true;
                return ContainerClicks.click(handler, slot, 1, ContainerInput.THROW, op.note());
            }
            default -> {
                return true;
            }
        }
    }

    private boolean move(AbstractContainerMenu handler, LocalPlayer player, Op op) {
        Slot from = InventoryUtils.inventorySlot(handler, player.getInventory(), op.a());
        Slot to = InventoryUtils.inventorySlot(handler, player.getInventory(), op.b());
        if (from == null || to == null) {
            fail("找不到物品栏第 " + (from == null ? op.a() : op.b()) + " 格");
            return false;
        }
        if (from.getItem().isEmpty()) return true;

        ItemStack source = from.getItem();
        ItemStack target = to.getItem();
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, source)) {
            fail("要挪到的那一格（第 " + op.b() + " 格）被 " + SingleStageData.idOf(target) + " 占着");
            return false;
        }

        if (!ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "拿起")) return false;
        if (!ContainerClicks.click(handler, to, 0, ContainerInput.PICKUP, op.note())) return false;
        if (!handler.getCarried().isEmpty()) {
            // 目标格塞不下整叠：剩下的放回原格，别把东西丢在光标上
            if (!ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "剩下的放回去")) {
                return false;
            }
        }
        return true;
    }
}
