package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 把物品栏里某个潜影盒<b>摆到世界里</b>（卸货 / 补充 / 临时摆放处）。
 *
 * <p>摆方块必须拿在手上，所以流程是：把盒子挪到一个空的快捷栏格 → 切过去 →
 * 点目标格自己（凭空放置，和 {@code SingleStagePlaceAction} 一个做法）→ 切回来。
 *
 * <p>目标格必须是空气（摆放处在 check 的时候验过）。
 */
public class MineTaskPlaceBoxAction extends Action {

    /** 一次点击最多等这么多 tick 看它有没有真的放上去。 */
    private static final int MAX_WAIT_TICKS = 10;

    /** 最多点几次。 */
    private static final int MAX_ATTEMPTS = 6;

    private final int invSlot;
    private final BlockPos target;
    private final String label;

    private int hotbarSlot = -1;
    private int step;
    private int waitTicks;
    private int attempts;
    private boolean hotbarHeld;

    public MineTaskPlaceBoxAction(int invSlot, BlockPos target, String label) {
        this.invSlot = invSlot;
        this.target = target.immutable();
        this.label = label;
    }

    @Override
    public String name() {
        return "把" + label + "摆到 " + target.toShortString();
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (!replaceable(mc.level.getBlockState(target))) {
            fail(target.toShortString() + " 已经有 " + BuiltInRegistries.BLOCK.getKey(
                mc.level.getBlockState(target).getBlock()) + " 了（摆放处应该是空的）");
            return;
        }
        if (!(player.getInventory().getItem(invSlot).getItem() instanceof BlockItem)) {
            fail("物品栏第 " + invSlot + " 格里不是潜影盒（是 "
                + InventoryUtils.describe(player.getInventory().getItem(invSlot)) + "）");
            return;
        }
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        switch (step) {
            case 0 -> moveToHotbar(player);
            case 1 -> beginPlace(mc);
            case 2 -> waitPlaced(mc);
            default -> finish();
        }
    }

    @Override
    protected void onEnd() {
        if (hotbarHeld) {
            HotbarManager.get().release(Owner.MINE_TASK);
            RotateManager.get().release(Owner.MINE_TASK);
            hotbarHeld = false;
        }
    }

    // ------------------------------------------------------------------

    private void moveToHotbar(LocalPlayer player) {
        if (invSlot < 9) {
            hotbarSlot = invSlot;
            step = 1;
            return;
        }

        if (player.containerMenu != player.inventoryMenu) {
            fail("现在开着别的界面，没法把盒子挪到快捷栏");
            return;
        }

        int empty = -1;
        for (int slot = 5; slot <= 8; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                empty = slot;
                break;
            }
        }
        if (empty < 0) {
            for (int slot = 0; slot <= 8; slot++) {
                if (player.getInventory().getItem(slot).isEmpty()) {
                    empty = slot;
                    break;
                }
            }
        }
        if (empty < 0) {
            fail("快捷栏没有空格，放不了" + label);
            return;
        }

        Slot from = InventoryUtils.inventorySlot(player.containerMenu, player.getInventory(), invSlot);
        Slot to = InventoryUtils.inventorySlot(player.containerMenu, player.getInventory(), empty);
        if (from == null || to == null) {
            fail("找不到物品栏第 " + invSlot + " 格或者快捷栏第 " + (empty + 1) + " 格");
            return;
        }

        ContainerClicks.click(player.containerMenu, from, 0, ContainerInput.PICKUP, "拿起" + label);
        ContainerClicks.click(player.containerMenu, to, 0, ContainerInput.PICKUP,
            "放进快捷栏第 " + (empty + 1) + " 格");
        if (!player.containerMenu.getCarried().isEmpty()) {
            ContainerClicks.click(player.containerMenu, from, 0, ContainerInput.PICKUP, "放回原格");
            fail("盒子塞不进快捷栏第 " + (empty + 1) + " 格");
            return;
        }

        hotbarSlot = empty;
        step = 1;
    }

    private void beginPlace(Minecraft mc) {
        if (!HotbarManager.get().request(Owner.MINE_TASK, hotbarSlot)) {
            fail("快捷栏第 " + (hotbarSlot + 1) + " 格现在不让切过去（别的功能在用）");
            return;
        }
        hotbarHeld = true;
        tryPlace(mc);
        step = 2;
        waitTicks = 0;
    }

    private void waitPlaced(Minecraft mc) {
        BlockState state = mc.level.getBlockState(target);
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            ChatUtils.debug("%s 摆好了：%s", label, target.toShortString());
            finish();
            return;
        }

        if (++waitTicks <= MAX_WAIT_TICKS) return;

        waitTicks = 0;
        attempts++;
        if (attempts >= MAX_ATTEMPTS) {
            fail("在 " + target.toShortString() + " 摆不上" + label + "（点了 " + attempts + " 次，离眼睛 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(target)) + " 格）");
            return;
        }
        tryPlace(mc);
    }

    /** 这一格现在能不能放方块（空气 / 水 / 熔岩 / 草这类可替换的都算）。 */
    static boolean replaceable(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    private void tryPlace(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        Direction side = Direction.UP;
        Vec3 hitPos = Vec3.atCenterOf(target).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitPos, side, target, false);

        RotateManager.get().rotateTo(Owner.MINE_TASK, hitPos);
        ChatUtils.debug("摆%s：%s（点它自己，手上 %s）", label, target.toShortString(),
            InventoryUtils.describe(player.getInventory().getItem(hotbarSlot)));
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }
}
