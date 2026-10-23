package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.six_coin.playerController.client.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 拆平台之前「用熔岩桶烧垃圾」：站到 6 6 6，把服务端那边的朝向转到 yaw 135 / pitch 30，
 * 拿背包 12 的熔岩桶**右键两次**（中间隔 1 tick），然后把桶归位。
 *
 * <p>两次右键是「一放一收」：第一次把岩浆倒到放垃圾的那一格上，把地上的东西烧掉
 * （中间隔 {@value #GAP_TICKS} tick，给岩浆留出烧东西的时间）；
 * 第二次空桶对着那滩岩浆再点一下，把岩浆收回来。所以地上不会留下岩浆。
 *
 * <p><b>为什么要自己拼包</b>：{@code interactItem} 发出去的包带的是**客户端真实**的 yaw / pitch，
 * 服务端会拿它覆盖自己的朝向再去射线 —— 那样倒出来的岩浆就落在玩家屏幕上看着的方向了。
 * 这里直接把假朝向（135 / 30）写进包里，服务端就会按假朝向射线。
 * 客户端自己的视角一动不动。
 */
class SingleStageLavaAction extends SingleStageStep {

    /** 切快捷栏 + 转头包落地要几 tick。 */
    private static final int PREPARE_TICKS = 3;

    /**
     * 两次右键之间隔几 tick。
     *
     * <p>10 tick（0.5 秒）：岩浆要烧掉地上的东西需要时间，1 tick 太短，东西还没烧没就被收走了。
     */
    private static final int GAP_TICKS = 10;

    /** 收完岩浆之后再等几 tick 才归位。 */
    private static final int SETTLE_TICKS = 2;

    private int hotbar = -1;
    private int ticks;
    private boolean hotbarHeld;
    private boolean rotated;

    SingleStageLavaAction(SingleStageCtx ctx) {
        super(ctx);
    }

    @Override
    protected String phase() {
        return "用熔岩桶烧垃圾";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            fail("玩家或世界不存在");
            return;
        }

        ItemStack bucket = player.getInventory().getStack(SingleStageCtx.LAVA_BUCKET);
        if (!bucket.isOf(Items.LAVA_BUCKET)) {
            fail("背包 12 不是熔岩桶（是 " + SingleStageData.idOf(bucket) + "），没法倒岩浆");
            return;
        }

        hotbar = firstFreeHotbar(player);
        if (hotbar < 0) {
            fail("快捷栏 6~8 都占着，没地方放熔岩桶");
            return;
        }

        // 熔岩桶在背包 12（不是快捷栏），先换到快捷栏再切过去
        if (!SingleStageInventoryAction.swapNow(player, SingleStageCtx.LAVA_BUCKET, hotbar)) {
            fail("没法把熔岩桶挪到快捷栏第 " + (hotbar + 1) + " 格");
            return;
        }
        if (!HotbarManager.get().request(Owner.MINE, hotbar)) {
            fail("快捷栏现在不让切（别的功能在用）");
            return;
        }
        hotbarHeld = true;

        if (!RotateManager.get().rotateTo(Owner.MINE, SingleStageCtx.LAVA_YAW,
            SingleStageCtx.LAVA_PITCH)) {
            fail("现在不让转头（" + RotateManager.get().owner() + " 在用），倒不了岩浆");
            return;
        }
        rotated = true;

        ChatUtils.debug("倒岩浆：站在 " + ctx.lavaStandPoint().toShortString() + "，服务端朝向 "
            + SingleStageCtx.LAVA_YAW + " / " + SingleStageCtx.LAVA_PITCH);
    }

    @Override
    protected void tick() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (!rotated) return;

        // 每 tick 重发一次假朝向：下面那个包虽然自己带着角度，但中间别的包也可能把服务端朝向改掉
        RotateManager.get().resend();

        ticks++;
        if (ticks == PREPARE_TICKS) {
            useBucket();
            return;
        }
        if (ticks == PREPARE_TICKS + GAP_TICKS + 1) {
            useBucket();
            return;
        }
        if (ticks == PREPARE_TICKS + GAP_TICKS + 1 + SETTLE_TICKS) {
            restore(player);
            finish();
        }
    }

    @Override
    protected void onEnd() {
        if (rotated) {
            RotateManager.get().release(Owner.MINE);
            rotated = false;
        }
        if (hotbarHeld) {
            HotbarManager.get().release(Owner.MINE);
            hotbarHeld = false;
        }
    }

    // ------------------------------------------------------------------

    /** 用一次手上的桶（包里的 yaw / pitch 写死成假朝向）。 */
    private void useBucket() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) return;

        ItemStack held = player.getInventory().getSelectedStack();
        if (held.isEmpty()) return;

        ChatUtils.debug("倒岩浆：右键一次（手上 " + SingleStageData.idOf(held) + "）");
        ((ClientPlayerInteractionManagerAccessor) mc.interactionManager)
            .playerController$sendSequencedPacket((ClientWorld) mc.world, sequence ->
                new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, sequence,
                    SingleStageCtx.LAVA_YAW, SingleStageCtx.LAVA_PITCH));
        player.swingHand(Hand.MAIN_HAND);
    }

    /** 把桶放回背包 12（放完岩浆它是空桶，收回来就还是熔岩桶）。 */
    private void restore(ClientPlayerEntity player) {
        if (hotbar < 0) return;
        if (!SingleStageInventoryAction.swapNow(player, SingleStageCtx.LAVA_BUCKET, hotbar)) {
            ChatUtils.error("熔岩桶没能放回背包 12，记得自己收一下");
        }
        hotbar = -1;
    }

    private static int firstFreeHotbar(ClientPlayerEntity player) {
        for (int slot = SingleStageCtx.HOTBAR_FREE_1; slot <= SingleStageCtx.HOTBAR_FREE_3; slot++) {
            if (player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        return -1;
    }
}
