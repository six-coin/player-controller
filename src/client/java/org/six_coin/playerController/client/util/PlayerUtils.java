package org.six_coin.playerController.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;

/** 玩家相关的计算：朝向、触及距离、方块中心对齐等。 */
public final class PlayerUtils {

    /** 判断“已经在方块中心”的误差。 */
    private static final double CENTER_EPSILON = 0.001;

    /**
     * 对齐时 y 相对方块底面的偏移。
     *
     * <p>x/z 用 n.5（方块正中心），y 用 n + 这个值：稍微离地一点点，
     * 这样不会因为站在地面边缘或者台阶上而被卡住。
     *
     * <p>注意这个偏移必须小于 1，否则 {@code BlockPos.ofFloored} 会算到上一格去。
     */
    public static final double CENTER_Y_OFFSET = 0.2;

    /** 眼睛相对脚底的高度（原版是 1.62）。 */
    public static final double EYE_HEIGHT = 1.62;

    private PlayerUtils() {
    }

    public static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    /** 玩家所在的方块坐标（取整，和路径点用的是同一套坐标）。 */
    @Nullable
    public static BlockPos currentBlockPos() {
        LocalPlayer player = player();
        return player == null ? null : BlockPos.containing(player.position());
    }

    /** 玩家沿某个轴的坐标。 */
    public static double axisValue(Direction.Axis axis, LocalPlayer player) {
        return axis.choose(player.getX(), player.getY(), player.getZ());
    }

    // ------------------------------------------------------------------
    // 视线
    // ------------------------------------------------------------------

    /** 准星看到的方块，没看到返回 null。 */
    @Nullable
    public static BlockPos lookedAtBlock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        HitResult hit = mc.player.pick(reach(), 0.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 方块中心
    // ------------------------------------------------------------------

    /**
     * 方块中心：x/z 为 n.5，y 为 n + {@link #CENTER_Y_OFFSET}。
     *
     * <p>因为路径点存的是方块坐标，y 用方块自身的高度（也就是站在这块地上时脚的位置）。
     */
    public static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + CENTER_Y_OFFSET, pos.getZ() + 0.5);
    }

    /** 玩家是否已经在所在方块的中心。 */
    public static boolean isAtBlockCenter() {
        LocalPlayer player = player();
        if (player == null) return false;
        BlockPos pos = BlockPos.containing(player.position());
        Vec3 center = blockCenter(pos);
        return Math.abs(player.getX() - center.x) <= CENTER_EPSILON
            && Math.abs(player.getY() - center.y) <= CENTER_EPSILON
            && Math.abs(player.getZ() - center.z) <= CENTER_EPSILON;
    }

    /**
     * 把玩家直接对齐到方块中心。
     *
     * <p>对齐是必须的：路径点存的是整数方块坐标，只有先站到方块中心，
     * 沿轴移动整数格之后才会落在下一个方块的中心。
     *
     * <p><b>不要在这里自己发包</b>：26.3 的服务端一个 tick 只接受一个带位置的移动包，
     * 多发一个就是 {@code Invalid move player packet received} 直接断开。
     * 位置既然改了，原版 {@code LocalPlayer.sendPosition} 下一 tick 自然会把它报上去。
     */
    public static void snapToBlockCenter(BlockPos pos) {
        LocalPlayer player = player();
        if (player == null) return;

        Vec3 center = blockCenter(pos);
        player.setPos(center.x, center.y, center.z);
        player.setDeltaMovement(Vec3.ZERO);
    }

    // ------------------------------------------------------------------
    // 触及范围 / 朝向
    // ------------------------------------------------------------------

    /**
     * 当前生效的触及距离，单位：方块。
     *
     * <p>取配置里的 {@code actions.reach}（默认 4.5）。开容器、挖方块、打怪、
     * 算工作站 / 挖掘站落点用的都是这一个数，不区分看不看得见。
     *
     * <p>注意：这只是本模组自己的判断标准，服务端还有它自己的一套距离检查。
     */
    public static double reach() {
        return PlayerControllerConfig.getReach();
    }

    /** 玩家眼睛到方块中心的距离是否在触及范围内。 */
    public static boolean isWithinReach(BlockPos pos) {
        return eyeDistanceTo(pos) <= reach();
    }

    public static double eyeDistanceTo(BlockPos pos) {
        LocalPlayer player = player();
        if (player == null) return Double.MAX_VALUE;
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    /**
     * 站在 {@code standOn} 上时，眼睛到 {@code target} 中心的距离。
     *
     * <p>工作站用站立点当参照：所有相关方块都要在站立点的触及范围内。
     */
    public static double eyeDistanceFrom(BlockPos standOn, BlockPos target) {
        Vec3 eye = Vec3.atCenterOf(standOn).add(0, EYE_HEIGHT, 0);
        return eye.distanceTo(Vec3.atCenterOf(target));
    }

    /** 站在 {@code standOn} 上够不够得着 {@code target}。 */
    public static boolean isWithinReachFrom(BlockPos standOn, BlockPos target) {
        return eyeDistanceFrom(standOn, target) <= reach();
    }

    /** 看向某个方块中心所需要的 yaw。 */
    public static float yawTo(BlockPos pos) {
        LocalPlayer player = player();
        Vec3 eye = player.getEyePosition();
        double dx = pos.getX() + 0.5 - eye.x;
        double dz = pos.getZ() + 0.5 - eye.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** 看向某个方块中心所需要的 pitch。 */
    public static float pitchTo(BlockPos pos) {
        LocalPlayer player = player();
        Vec3 eye = player.getEyePosition();
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    /** 玩家眼睛相对方块中心的方向，也就是应该点击的面。 */
    public static Direction facingSide(BlockPos pos) {
        LocalPlayer player = player();
        if (player == null) return Direction.UP;
        return Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(pos)));
    }
}
