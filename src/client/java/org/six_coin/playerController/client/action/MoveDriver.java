package org.six_coin.playerController.client.action;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.feature.DimensionWait;
import org.six_coin.playerController.client.feature.FlightVelocity;

/**
 * 沿单一轴向走一段距离的执行器。
 *
 * <p>每 tick 用 Meteor Flight 的 velocity 模式写入速度（见 {@link FlightVelocity}），
 * 最后一小段用剩余距离，保证正好停在目标坐标上。
 */
final class MoveDriver {

    enum Status {
        MOVING,
        ARRIVED,
        FAILED
    }

    /** 认为“已经到达”的误差。 */
    private static final double ARRIVE_EPSILON = 0.02;

    /** 连续这么多 tick 没有位移就认为被挡住了。 */
    private static final int MAX_STALL_TICKS = 20;

    private final Direction.Axis axis;
    private final double target;
    private final int maxTicks;

    private int ticks;
    private int stalledTicks;
    private double lastCoord = Double.NaN;
    private String failure = "未知原因";

    MoveDriver(Direction.Axis axis, double target, int blocks) {
        this.axis = axis;
        this.target = target;

        double speed = Math.max(PlayerControllerConfig.MIN_MOVE_SPEED, PlayerControllerConfig.getMoveSpeed());
        long expected = (long) Math.ceil(Math.abs(blocks) / speed) * 4L + 60L;
        this.maxTicks = (int) Math.min(Integer.MAX_VALUE, expected);
    }

    Status tick(double coord) {
        // 刚被传送过（换维度）就先原地悬停：服务端那边的落点 / 区块还没同步好，这时候走容易被拉回去
        if (DimensionWait.waiting()) {
            FlightVelocity.apply(Vec3.ZERO);
            return Status.MOVING;
        }

        double remaining = target - coord;

        if (Math.abs(remaining) <= ARRIVE_EPSILON) {
            FlightVelocity.apply(Vec3.ZERO);
            return Status.ARRIVED;
        }

        if (ticks >= maxTicks) {
            failure = "超时（" + ticks + " tick，还差 " + String.format("%.3f", Math.abs(remaining)) + " 格）";
            return Status.FAILED;
        }

        // 速度：往下走的时候受配置里的 move_max_descent_speed_blocks_per_tick 限制（下降太快会摔伤）
        double speed = FlightVelocity.speedFor(axis, remaining);
        double step = Math.min(speed, Math.abs(remaining)) * Math.signum(remaining);
        FlightVelocity.apply(FlightVelocity.velocityFor(axis, step));

        if (!Double.isNaN(lastCoord) && Math.abs(coord - lastCoord) < 1.0E-4) {
            stalledTicks++;
            if (stalledTicks >= MAX_STALL_TICKS) {
                failure = "被挡住，无法继续移动";
                return Status.FAILED;
            }
        } else {
            stalledTicks = 0;
        }

        lastCoord = coord;
        ticks++;
        return Status.MOVING;
    }

    String failure() {
        return failure;
    }

    Direction.Axis axis() {
        return axis;
    }
}
