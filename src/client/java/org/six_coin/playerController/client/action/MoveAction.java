package org.six_coin.playerController.client.action;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.feature.NoFall;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 沿着某个轴移动指定格数。
 *
 * <p>移动方式使用 Meteor Flight 的 velocity 模式（见 {@link FlightVelocity}），
 * 速度为 {@link FlightVelocity#SPEED}；移动期间自动开启 NoFall。
 */
public class MoveAction extends Action {

    /** 认为“已经到达”的误差。 */
    private static final double ARRIVE_EPSILON = 0.02;

    /** 连续这么多 tick 没有位移就认为被挡住了。 */
    private static final int MAX_STALL_TICKS = 20;

    private final Direction.Axis axis;
    private final int blocks;

    private double start;
    private double target;
    private int maxTicks;
    private int ticks;
    private int stalledTicks;
    private double lastCoord = Double.NaN;

    public MoveAction(Direction.Axis axis, int blocks) {
        this.axis = axis;
        this.blocks = blocks;
    }

    @Override
    public String name() {
        return "移动 " + axisName(axis) + " 轴 " + blocks + " 格";
    }

    @Override
    protected void onStart() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        float yaw = switch (axis) {
            case X -> blocks > 0 ? -90.0f : 90.0f;
            case Z -> blocks > 0 ? 0.0f : 180.0f;
            case Y -> player.getYaw();
        };
        player.setYaw(yaw);

        start = PlayerUtils.axisValue(axis, player);
        target = start + blocks;
        maxTicks = Math.max(60, (int) Math.ceil(Math.abs(blocks) / FlightVelocity.SPEED) * 4 + 60);
        lastCoord = Double.NaN;
        stalledTicks = 0;
        ticks = 0;

        // 移动期间自动开启 NoFall
        NoFall.acquire();
        FlightVelocity.begin();

        ChatUtils.debug("起点 %s=%.3f，目标 %.3f，速度 %.2f 格/tick，NoFall 已开启",
            axisName(axis), start, target, FlightVelocity.SPEED);
    }

    @Override
    protected void tick() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        double coord = PlayerUtils.axisValue(axis, player);
        double remaining = target - coord;

        if (Math.abs(remaining) <= ARRIVE_EPSILON) {
            FlightVelocity.apply(Vec3d.ZERO);
            ChatUtils.debug("已到达 %s=%.3f", axisName(axis), coord);
            finish();
            return;
        }

        if (ticks >= maxTicks) {
            fail("超时，停留在 " + axisName(axis) + "=" + String.format("%.3f", coord) + "（目标 " + target + "）");
            return;
        }

        // 最后一步用剩余距离，避免冲过头
        double step = Math.min(FlightVelocity.SPEED, Math.abs(remaining)) * Math.signum(remaining);
        FlightVelocity.apply(FlightVelocity.velocityFor(axis, step));

        if (!Double.isNaN(lastCoord) && Math.abs(coord - lastCoord) < 1.0E-4) {
            stalledTicks++;
            if (stalledTicks >= MAX_STALL_TICKS) {
                fail("被挡住，无法继续移动（卡在 " + axisName(axis) + "=" + String.format("%.3f", coord) + "）");
                return;
            }
        } else {
            stalledTicks = 0;
        }
        lastCoord = coord;
        ticks++;
    }

    @Override
    protected void onEnd() {
        FlightVelocity.end();
        NoFall.release();
        ChatUtils.debug("NoFall 已关闭，飞行结束");
    }

    public static String axisName(Direction.Axis axis) {
        return switch (axis) {
            case X -> "x";
            case Y -> "y";
            case Z -> "z";
        };
    }
}
