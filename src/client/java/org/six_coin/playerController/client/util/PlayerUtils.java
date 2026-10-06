package org.six_coin.playerController.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** 玩家相关的计算：朝向、触及距离等。 */
public final class PlayerUtils {

    private PlayerUtils() {
    }

    public static ClientPlayerEntity player() {
        return MinecraftClient.getInstance().player;
    }

    /** 玩家眼睛到方块中心的距离是否在触及范围内。 */
    public static boolean isWithinReach(BlockPos pos) {
        ClientPlayerEntity player = player();
        if (player == null) return false;
        double range = player.getBlockInteractionRange();
        return eyeDistanceTo(pos) <= range;
    }

    public static double eyeDistanceTo(BlockPos pos) {
        ClientPlayerEntity player = player();
        if (player == null) return Double.MAX_VALUE;
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    public static double reach() {
        ClientPlayerEntity player = player();
        return player == null ? 0.0 : player.getBlockInteractionRange();
    }

    /** 看向某个方块中心所需要的 yaw。 */
    public static float yawTo(BlockPos pos) {
        ClientPlayerEntity player = player();
        Vec3d eye = player.getEyePos();
        double dx = pos.getX() + 0.5 - eye.getX();
        double dz = pos.getZ() + 0.5 - eye.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** 看向某个方块中心所需要的 pitch。 */
    public static float pitchTo(BlockPos pos) {
        ClientPlayerEntity player = player();
        Vec3d eye = player.getEyePos();
        double dx = pos.getX() + 0.5 - eye.getX();
        double dy = pos.getY() + 0.5 - eye.getY();
        double dz = pos.getZ() + 0.5 - eye.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    /**
     * 把玩家（客户端）的朝向转到目标方块上。
     *
     * <p>这里直接改玩家本体的 yaw/pitch，玩家能看见控制器在做什么；
     * 后续由 {@code sendMovementPackets} 把新朝向发给服务端。
     */
    public static void lookAt(BlockPos pos) {
        ClientPlayerEntity player = player();
        if (player == null) return;
        player.setYaw(MathHelper.wrapDegrees(yawTo(pos)));
        player.setPitch(MathHelper.clamp(pitchTo(pos), -90.0f, 90.0f));
    }

    /** 玩家眼睛相对方块中心的方向，也就是应该点击的面。 */
    public static Direction facingSide(BlockPos pos) {
        ClientPlayerEntity player = player();
        if (player == null) return Direction.UP;
        return Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(pos)));
    }

    /** 直接修改一个轴的坐标（只用于读取，这里放在工具里方便复用）。 */
    public static double axisValue(Direction.Axis axis, Vec3d pos) {
        return axis.choose(pos.getX(), pos.getY(), pos.getZ());
    }

    public static double axisValue(Direction.Axis axis, ClientPlayerEntity player) {
        return axisValue(axis, player.getEntityPos());
    }
}
