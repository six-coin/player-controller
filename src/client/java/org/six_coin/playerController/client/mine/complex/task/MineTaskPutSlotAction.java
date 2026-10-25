package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 打开一个容器，把物品栏**指定那一格**的东西放进容器**指定那一格**（按位置，不看是什么）。
 *
 * <p>和 {@link MineTaskTakeAction} 正好相反。补货里拆回来的潜影盒要按位置放回大箱子
 * 第 6~8 / 9~11 格 —— 盒子被吸到哪一格是随机的，所以不能靠 {@code SYNC_TO_CONTAINER}
 * （那个要求东西先在对应的物品栏格）。
 */
public class MineTaskPutSlotAction extends Action {

    private static final int MAX_STEP_WAIT_TICKS = 60;
    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final MineTaskStationData.ContainerInfo container;
    private final int inventorySlot;
    private final int containerSlot;
    private final String label;

    private final ContainerSession session;

    private enum Phase {
        OPENING,
        WORK
    }

    private Phase phase = Phase.OPENING;
    private int totalTicks;
    private int stepWaitTicks;
    private int step;
    /** 放进容器的那一叠（搬完按它更新 station_data）。 */
    @Nullable
    private ItemStack moved;

    public MineTaskPutSlotAction(MineTaskStationData.ContainerInfo container, int inventorySlot,
                                 int containerSlot, String label) {
        this.container = container;
        this.inventorySlot = inventorySlot;
        this.containerSlot = containerSlot;
        this.label = label;
        this.session = new ContainerSession(container.pos()).withOwner(Owner.MINE_TASK);
    }

    @Override
    public String name() {
        return "把物品栏第 " + inventorySlot + " 格的" + label + "放进 "
            + container.part().display() + " #" + container.id() + " 第 " + containerSlot + " 格";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        BlockPos pos = container.pos();
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("离容器 " + pos.toShortString() + " 太远（落点没走到？）");
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            fail("光标上还有物品，先放下");
            return;
        }
        session.open(mc, player);
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("放" + label + "超时（" + totalTicks + " tick）");
            return;
        }

        if (phase == Phase.OPENING) {
            switch (session.awaitOpen(mc)) {
                case READY -> phase = Phase.WORK;
                case TIMEOUT -> fail("打不开 " + container.pos().toShortString() + "（等界面超时）");
                case WAITING -> {
                }
            }
            return;
        }
        if (!session.stillOpen(mc)) {
            fail("容器界面被关掉了（" + container.pos().toShortString() + "）");
            return;
        }

        Slot source = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), inventorySlot);
        Slot target = containerSlotAt();
        if (source == null || target == null) {
            fail("找不到物品栏第 " + inventorySlot + " 格或容器第 " + containerSlot + " 格");
            return;
        }

        if (step == 2) {
            if (source.getItem().isEmpty() && !target.getItem().isEmpty()) {
                ChatUtils.debug("%s 已经放进 %s 第 %d 格了", label,
                    container.part().display(), containerSlot);
                moved = target.getItem().copy();
                finish();
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("把" + label + "放进容器第 " + containerSlot + " 格没生效");
            }
            return;
        }

        // 东西已经在光标上了：直接放进目标格。
        // **这一步必须在「源格是不是空的」之前判断** —— 拿起来之后源格本来就是空的。
        if (step == 1) {
            if (session.handler().getCarried().isEmpty()) {
                if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                    fail("拿起" + label + "失败（光标上什么都没有）");
                }
                return;
            }
            if (!session.click(target, 0, ContainerInput.PICKUP, "把" + label + "放进容器第 "
                + containerSlot + " 格")) {
                fail("点不了容器第 " + containerSlot + " 格");
                return;
            }
            step = 2;
            stepWaitTicks = 0;
            return;
        }

        if (source.getItem().isEmpty()) {
            fail("物品栏第 " + inventorySlot + " 格是空的，没" + label + "可放");
            return;
        }
        if (!target.getItem().isEmpty()) {
            fail("容器第 " + containerSlot + " 格被 " + InventoryUtils.describe(target.getItem())
                + " 占着（应该是空的）");
            return;
        }

        if (!session.click(source, 0, ContainerInput.PICKUP, "拿起" + label)) {
            fail("点不了物品栏第 " + inventorySlot + " 格");
            return;
        }
        step = 1;
        stepWaitTicks = 0;
    }

    @Override
    protected void onEnd() {
        if (session.isOpen()) {
            if (moved != null && failureReason() == null) container.put(moved);
            session.close(Minecraft.getInstance());
        }
    }

    private Slot containerSlotAt() {
        java.util.List<Slot> slots = InventoryUtils.containerSlots(session.handler());
        return containerSlot >= 0 && containerSlot < slots.size() ? slots.get(containerSlot) : null;
    }
}
