package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.mine.station.MineToolRules;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

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

    /** 一下操作最多等这么久（3 秒）：扔东西 / 挪东西要等服务端确认。 */
    private static final int MAX_RETRY_TICKS = 60;

    /** 没生效的话隔几个 tick 再点一次。 */
    private static final int RESEND_TICKS = 5;

    /** 「等容器关干净」最多等这么多 tick，等不到就直接干（不能卡死）。 */
    private static final int SETTLE_WAIT_TICKS = 40;

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
    /** 当前这一下等了几个 tick（扔东西要等服务端把那一格清掉）。 */
    private int retryTicks;
    /** 等容器关干净等了多少 tick。 */
    private int settleWaitTicks;

    /** 一个操作这一 tick 的结果。 */
    private enum Result {
        /** 完成了，下一个。 */
        DONE,
        /** 还没生效（等服务端），下一 tick 再试。 */
        RETRY,
        /** 出错了（已经 {@code fail} 过）。 */
        FAILED
    }

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

        // 刚关掉容器的话先等一下：服务端那边还没关，点物品栏的包会被丢掉
        // （最多等 SETTLE_WAIT_TICKS，等不到就直接干 —— 宁可点一下被丢，也不能卡着不动）
        if (!ContainerSession.inventorySettled() && ++settleWaitTicks <= SETTLE_WAIT_TICKS) return;

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
            Op op = ops.get(index);
            Result result = apply(handler, player, op);
            if (result == Result.FAILED) return;
            if (result == Result.RETRY) {
                if (++retryTicks > MAX_RETRY_TICKS) {
                    fail(op.note() + " 等了 " + MAX_RETRY_TICKS + " tick 还没生效");
                }
                return;
            }
            retryTicks = 0;
            index++;
        }
        finish();
    }

    // ------------------------------------------------------------------

    private Result apply(AbstractContainerMenu handler, LocalPlayer player, Op op) {
        return switch (op.kind()) {
            case SWAP -> {
                Slot slot = InventoryUtils.inventorySlot(handler, player.getInventory(), op.a());
                if (slot == null) {
                    fail("找不到物品栏第 " + op.a() + " 格");
                    yield Result.FAILED;
                }
                yield ContainerClicks.click(handler, slot, op.b(), ContainerInput.SWAP, op.note())
                    ? Result.DONE : Result.FAILED;
            }
            case MOVE -> move(handler, player, op);
            case THROW -> throwStack(handler, player, op);
        };
    }

    /**
     * 整叠扔掉。
     *
     * <p>点一下不算完：**要等服务端把这一格真的清掉**（客户端预测和服务端之间有一趟往返，
     * 点完立刻去看的话那一格往往还有东西）。所以这里返回 {@link Result#RETRY} 让动作下一 tick
     * 再看，隔几个 tick 还没清掉就再点一次。
     */
    private Result throwStack(AbstractContainerMenu handler, LocalPlayer player, Op op) {
        Slot slot = InventoryUtils.inventorySlot(handler, player.getInventory(), op.a());
        if (slot == null) {
            fail("找不到物品栏第 " + op.a() + " 格");
            return Result.FAILED;
        }
        if (slot.getItem().isEmpty()) return Result.DONE;

        if (retryTicks % RESEND_TICKS == 0
            && !ContainerClicks.click(handler, slot, 1, ContainerInput.THROW, op.note())) {
            return Result.FAILED;
        }
        return Result.RETRY;
    }

    private Result move(AbstractContainerMenu handler, LocalPlayer player, Op op) {
        Slot from = InventoryUtils.inventorySlot(handler, player.getInventory(), op.a());
        Slot to = InventoryUtils.inventorySlot(handler, player.getInventory(), op.b());
        if (from == null || to == null) {
            fail("找不到物品栏第 " + (from == null ? op.a() : op.b()) + " 格");
            return Result.FAILED;
        }
        if (from.getItem().isEmpty()) return Result.DONE;

        ItemStack source = from.getItem();
        ItemStack target = to.getItem();
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, source)) {
            // 目标格被别的东西占着。占位的多半是吸上来的圆石（搭平台、拆平台掉的）——
            // **就地扔掉**，等它真的空了再挪；盒子 / 工具这种值钱的不能扔，直接报错
            if (!junk(target)) {
                fail("要挪到的那一格（第 " + op.b() + " 格）被 " + SingleStageData.idOf(target) + " 占着");
                return Result.FAILED;
            }
            if (retryTicks == 0) {
                ChatUtils.info("第 " + op.b() + " 格被 " + SingleStageData.idOf(target)
                    + " 占着，就地扔掉再挪");
            }
            if (retryTicks % RESEND_TICKS == 0
                && !ContainerClicks.click(handler, to, 1, ContainerInput.THROW,
                "扔掉第 " + op.b() + " 格占位的 " + SingleStageData.idOf(target))) {
                return Result.FAILED;
            }
            return Result.RETRY;
        }

        if (!ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "拿起")) {
            return Result.FAILED;
        }
        if (!ContainerClicks.click(handler, to, 0, ContainerInput.PICKUP, op.note())) {
            return Result.FAILED;
        }
        if (!handler.getCarried().isEmpty()) {
            // 目标格塞不下整叠：剩下的放回原格，别把东西丢在光标上
            if (!ContainerClicks.click(handler, from, 0, ContainerInput.PICKUP, "剩下的放回去")) {
                return Result.FAILED;
            }
        }
        return Result.DONE;
    }

    /**
     * 这一叠是不是「可以就地扔掉」的垃圾。
     *
     * <p>潜影盒和工具都不能扔（扔了就真没了）；别的东西（圆石、泥土、砂砾……）都是挖矿时
     * 顺手吸上来的，占了要用的格子就该扔掉。
     */
    static boolean junk(ItemStack stack) {
        if (ShulkerUtils.isShulkerBox(stack)) return false;
        return MineToolRules.kindOf(stack) == null;
    }
}
