package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/** 打开容器方块的那套动作：看向它 → 同步朝向 → 右键。 */
public final class ContainerOpener {

    private ContainerOpener() {
    }

    /**
     * 看向这个方块并右键打开它。
     *
     * <p>客户端界面要不要挡住由 {@code ScreenSuppressor} 决定（调用方自己 acquire / release）。
     */
    public static void open(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
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

        ChatUtils.debug("已右键 %s（面 %s，yaw %.1f pitch %.1f），等容器界面",
            pos.toShortString(), side.asString(), yaw, pitch);
    }
}
