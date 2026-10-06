package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.ItemRequest;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 打开一个容器，并把指定物品按数量拿进背包。
 *
 * <p>流程：转向方块 → 右键打开 → 每 tick 点一次槽位（shift 点击整叠搬走）
 * → 到量/搬不动了就关掉界面。
 */
public class ContainerAction extends Action {

    /** 等待服务端打开界面的最长时间。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;
    /** 整个搬运过程的最长时间。 */
    private static final int MAX_TRANSFER_TICKS = 20 * 60;
    /** 连续这么多 tick 没有拿到任何东西就放弃。 */
    private static final int MAX_NO_PROGRESS_TICKS = 40;

    private enum Phase {
        OPENING,
        TRANSFERRING,
        DONE
    }

    private static final class Need {
        final ItemRequest request;
        int remaining;

        Need(ItemRequest request, int remaining) {
            this.request = request;
            this.remaining = remaining;
        }
    }

    private final BlockPos pos;
    private final List<Need> needs = new ArrayList<>();
    private final List<String> shortfalls = new ArrayList<>();

    private Phase phase = Phase.OPENING;
    private ScreenHandler handler;
    private int waitTicks;
    private int transferTicks;
    private int noProgressTicks;
    private int lastTotal;

    public ContainerAction(BlockPos pos, List<ItemRequest> requests) {
        this.pos = pos.toImmutable();
        for (ItemRequest request : requests) {
            needs.add(new Need(request, request.count()));
        }
    }

    @Override
    public String name() {
        return "从容器 " + pos.toShortString() + " 取 " + describeRequests();
    }

    private String describeRequests() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < needs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(needs.get(i).request.displayName());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!PlayerUtils.isWithinReach(pos)) {
            fail("方块 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }

        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            fail(pos.toShortString() + " 不是容器（没有物品栏）");
            return;
        }

        // 只取“差多少”，背包里已经够的就不动了
        PlayerInventory inventory = player.getInventory();
        for (Need need : needs) {
            int have = InventoryUtils.count(inventory, need.request.item());
            need.remaining = Math.max(0, need.request.count() - have);
            if (need.remaining < need.request.count()) {
                ChatUtils.debug("背包里已有 %s x%d，还需要取 %d 个",
                    need.request.id(), have, need.remaining);
            }
        }

        if (remainingTotal() == 0) {
            ChatUtils.debug("背包里已经满足全部需求，无需打开容器");
            phase = Phase.DONE;
            finish();
            return;
        }

        openContainer();
    }

    private void openContainer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        PlayerUtils.lookAt(pos);
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        // 先把朝向同步给服务端，再发交互包
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround(), player.horizontalCollision));

        Direction side = PlayerUtils.facingSide(pos);
        Vec3d hitPos = Vec3d.ofCenter(pos).add(
            side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitPos, side, pos, false);

        boolean wasSneaking = player.isSneaking();
        player.setSneaking(false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        player.swingHand(Hand.MAIN_HAND);
        player.setSneaking(wasSneaking);

        ChatUtils.debug("右键 %s（面 %s，yaw %.1f pitch %.1f），等待容器界面",
            pos.toShortString(), side.asString(), yaw, pitch);
        phase = Phase.OPENING;
    }

    // ------------------------------------------------------------------

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(mc, player);
            case TRANSFERRING -> tickTransferring(mc, player);
            case DONE -> finish();
        }
    }

    private void tickOpening(MinecraftClient mc, ClientPlayerEntity player) {
        waitTicks++;
        ScreenHandler current = player.currentScreenHandler;
        if (current != null && current != player.playerScreenHandler) {
            handler = current;
            phase = Phase.TRANSFERRING;
            noProgressTicks = 0;
            lastTotal = totalHave(player.getInventory());
            ChatUtils.debug("容器界面已打开（syncId=%d，共 %d 个槽位）",
                handler.syncId, handler.slots.size());
            return;
        }
        if (waitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("等待容器界面超时（" + waitTicks + " tick）");
        }
    }

    private void tickTransferring(MinecraftClient mc, ClientPlayerEntity player) {
        if (mc.player.currentScreenHandler != handler) {
            fail("容器界面被关闭了");
            return;
        }

        transferTicks++;
        if (transferTicks > MAX_TRANSFER_TICKS) {
            fail("搬运超时");
            return;
        }

        if (remainingTotal() == 0) {
            ChatUtils.debug("需要的物品都拿到了");
            phase = Phase.DONE;
            return;
        }

        Need need = firstUnsatisfied(player.getInventory());
        if (need == null) {
            ChatUtils.debug("需求已满足");
            phase = Phase.DONE;
            return;
        }

        int slot = InventoryUtils.findContainerSlot(handler, player.getInventory(), need.request.item());
        if (slot < 0) {
            // 容器里没有（或已经被搬空了）
            shortfalls.add(need.request.id() + " 还差 " + need.remaining + " 个（容器里没有更多了）");
            ChatUtils.error("容器里没有更多的 " + need.request.id() + " 了");
            need.remaining = 0;
            if (remainingTotal() == 0) {
                phase = Phase.DONE;
            }
            return;
        }

        if (!InventoryUtils.canAccept(player.getInventory(), need.request.item())) {
            fail("背包已满，无法继续拿取");
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);
        ChatUtils.debug("搬走槽位 %d 的 %s", slot, need.request.id());

        int total = totalHave(player.getInventory());
        if (total <= lastTotal) {
            noProgressTicks++;
            if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
                fail("连续 " + noProgressTicks + " tick 没有拿到任何物品（背包可能已满）");
                return;
            }
        } else {
            noProgressTicks = 0;
        }
        lastTotal = total;
    }

    private Need firstUnsatisfied(PlayerInventory inventory) {
        for (Need need : needs) {
            if (need.remaining <= 0) continue;
            int have = InventoryUtils.count(inventory, need.request.item());
            need.remaining = Math.max(0, need.request.count() - have);
            if (need.remaining > 0) return need;
        }
        return null;
    }

    private int remainingTotal() {
        int total = 0;
        for (Need need : needs) total += Math.max(0, need.remaining);
        return total;
    }

    private int totalHave(PlayerInventory inventory) {
        int total = 0;
        for (Need need : needs) {
            total += InventoryUtils.count(inventory, need.request.item());
        }
        return total;
    }

    // ------------------------------------------------------------------

    @Override
    protected void onEnd() {
        closeScreen();

        for (Need need : needs) {
            ChatUtils.debug("　%s：目标 %d", need.request.id(), need.request.count());
        }
        if (!shortfalls.isEmpty()) {
            for (String shortfall : shortfalls) {
                ChatUtils.error("未满足: " + shortfall);
            }
        }
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;

        if (mc.currentScreen instanceof HandledScreen<?> screen && handler.equals(screen.getScreenHandler())) {
            // 走原版关闭流程：发 CloseHandledScreenC2SPacket 并移除界面
            screen.close();
        } else {
            mc.player.closeHandledScreen();
            if (mc.currentScreen instanceof HandledScreen<?>) {
                mc.setScreen(null);
            }
        }
        ChatUtils.debug("已关闭容器界面");
    }
}
