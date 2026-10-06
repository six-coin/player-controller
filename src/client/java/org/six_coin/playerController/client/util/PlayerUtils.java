package org.six_coin.playerController.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** 玩家相关的计算：朝向、触及距离、方块中心对齐等。 */
public final class PlayerUtils {

    /** 判断“已经在方块中心”的误差。 */
    private static final double CENTER_EPSILON = 0.001;

    private PlayerUtils() {
    }

    public static ClientPlayerEntity player() {
        return MinecraftClient.getInstance().player;
    }

    /** 玩家所在的方块坐标（取整，和路径点用的是同一套坐标）。 */
    @Nullable
    public static BlockPos currentBlockPos() {
        ClientPlayerEntity player = player();
        return player == null ? null : BlockPos.ofFloored(player.getEntityPos());
    }

    /** 玩家沿某个轴的坐标。 */
    public static double axisValue(Direction.Axis axis, ClientPlayerEntity player) {
        return axis.choose(player.getX(), player.getY(), player.getZ());
    }

    // ------------------------------------------------------------------
    // 视线
    // ------------------------------------------------------------------

    /** 准星看到的方块，没看到返回 null。 */
    @Nullable
    public static BlockPos lookedAtBlock() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;

        HitResult hit = mc.player.raycast(mc.player.getBlockInteractionRange(), 0.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 方块中心
    // ------------------------------------------------------------------

    /**
     * 方块中心：x/z 为 n.5，y 为 n.0。
     *
     * <p>因为路径点存的是方块坐标，y 用方块自身的高度（也就是站在这块地上时脚的位置）。
     */
    public static Vec3d blockCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** 玩家是否已经在所在方块的中心。 */
    public static boolean isAtBlockCenter() {
        ClientPlayerEntity player = player();
        if (player == null) return false;
        BlockPos pos = BlockPos.ofFloored(player.getEntityPos());
        Vec3d center = blockCenter(pos);
        return Math.abs(player.getX() - center.x) <= CENTER_EPSILON
            && Math.abs(player.getY() - center.y) <= CENTER_EPSILON
            && Math.abs(player.getZ() - center.z) <= CENTER_EPSILON;
    }

    /**
     * 把玩家直接对齐到方块中心，并把新位置同步给服务端。
     *
     * <p>对齐是必须的：路径点存的是整数方块坐标，只有先站到方块中心，
     * 沿轴移动整数格之后才会落在下一个方块的中心。
     */
    public static void snapToBlockCenter(BlockPos pos) {
        ClientPlayerEntity player = player();
        if (player == null) return;

        Vec3d center = blockCenter(pos);
        player.setPosition(center.x, center.y, center.z);
        player.setVelocity(Vec3d.ZERO);

        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                center.x, center.y, center.z, player.isOnGround(), player.horizontalCollision));
        }
    }

    // ------------------------------------------------------------------
    // 触及范围 / 朝向
    // ------------------------------------------------------------------

    /** 玩家眼睛到方块中心的距离是否在触及范围内。 */
    public static boolean isWithinReach(BlockPos pos) {
        ClientPlayerEntity player = player();
        if (player == null) return false;
        return eyeDistanceTo(pos) <= player.getBlockInteractionRange();
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

    /** 把玩家（客户端）的朝向转到目标方块上。 */
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
}
