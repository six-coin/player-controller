package org.six_coin.playerController.client.container;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/** 打开容器方块的那套动作：让服务端以为我们看着它 → 右键。 */
public final class ContainerOpener {

    private ContainerOpener() {
    }

    /**
     * 看向这个方块并右键打开它。
     *
     * <p>「看向」只是给服务端发一个转头包，客户端自己的视角不动（见 {@link RotateManager}）。
     *
     * <p>客户端界面要不要挡住由 {@code ScreenSuppressor} 决定（调用方自己 acquire / release）。
     */
    public static void open(Minecraft mc, LocalPlayer player, BlockPos pos) {
        open(mc, player, pos, Owner.STOCK);
    }

    /**
     * 同上，但转头记在 {@code requester} 名下（{@link Owner#priority()} 决定谁抢得过谁）。
     */
    public static void open(Minecraft mc, LocalPlayer player, BlockPos pos, Owner requester) {
        RotateManager.get().rotateTo(requester, pos);

        Direction side = PlayerUtils.facingSide(pos);
        Vec3 hitPos = Vec3.atCenterOf(pos).add(
            side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitPos, side, pos, false);

        boolean wasSneaking = player.isShiftKeyDown();
        player.setShiftKeyDown(false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        player.setShiftKeyDown(wasSneaking);

        ChatUtils.debug("已右键 %s（面 %s，服务端看到的朝向 yaw %.1f pitch %.1f），等容器界面",
            pos.toShortString(), side.getName(), RotateManager.get().yaw(), RotateManager.get().pitch());
    }
}
