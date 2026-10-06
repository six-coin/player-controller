package org.six_coin.playerController.client.action;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.WaypointManager;

/**
 * 沿着某个轴移动指定格数。
 *
 * <p>开始之前必须先对齐到玩家所在方块的中心（x/z 为 n.5，y 为 n.0），
 * 移动方式用 Meteor Flight 的 velocity 模式，速度取配置里的 {@code actions.move_speed}。
 *
 * <p>如果处于路径点编辑模式，结束后会把起点和终点记成路径点和一条边；
 * 终点在末地传送门处时还会额外连一条到末地出生平台的单向边。
 */
public class MoveAction extends Action {

    private final Direction.Axis axis;
    private final int blocks;

    private String dimension;
    private BlockPos startBlock;
    private BlockPos endBlock;
    private MoveDriver driver;

    public MoveAction(Direction.Axis axis, int blocks) {
        this.axis = axis;
        this.blocks = blocks;
    }

    @Override
    public String name() {
        return "移动 " + axisName(axis) + " 轴 " + blocks + " 格";
    }

    @Override
    public boolean isMovement() {
        return true;
    }

    @Override
    protected void onStart() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        dimension = DimensionUtils.current();

        // 1. 先把身体转过去（好看，也让服务端朝向合理）
        float yaw = switch (axis) {
            case X -> blocks > 0 ? -90.0f : 90.0f;
            case Z -> blocks > 0 ? 0.0f : 180.0f;
            case Y -> player.getYaw();
        };
        player.setYaw(yaw);

        // 2. 对齐到所在方块的中心
        BlockPos block = BlockPos.ofFloored(player.getEntityPos());
        if (!PlayerUtils.isAtBlockCenter()) {
            ChatUtils.debug("先对齐到方块中心 " + block.toShortString()
                + "（当前位置 " + String.format("%.3f, %.3f, %.3f", player.getX(), player.getY(), player.getZ()) + "）");
            PlayerUtils.snapToBlockCenter(block);
        }
        startBlock = block;
        endBlock = block.offset(axis, blocks);

        // 3. 终点如果在末地传送门处，编辑模式下先连一条到末地的单向边。
        //    放在这里而不是收尾时做，是因为走进去会立刻被传送走，
        //    那时候 mc.world 已经是末地了，查不到主世界的方块。
        WaypointManager.get().recordEndPortalLink(dimension, endBlock);

        // 4. 确定这一段的起止
        double start = PlayerUtils.axisValue(axis, player);
        double target = start + blocks;
        driver = new MoveDriver(axis, target, blocks);

        FlightVelocity.begin();

        ChatUtils.debug("起点 %s=%.3f，目标 %.3f，速度 %.2f 格/tick",
            axisName(axis), start, target, PlayerControllerConfig.getMoveSpeed());
    }

    @Override
    protected void tick() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (driver == null) {
            fail("没有初始化");
            return;
        }

        // 走进末地传送门方块会被立刻传送走，这时按“已经走到”处理
        if (dimension != null && !DimensionUtils.current().equals(dimension)) {
            ChatUtils.debug("移动过程中被传送到了 " + DimensionUtils.display(DimensionUtils.current())
                + "，按已到达处理");
            FlightVelocity.apply(net.minecraft.util.math.Vec3d.ZERO);
            finish();
            return;
        }

        double coord = PlayerUtils.axisValue(axis, player);
        switch (driver.tick(coord)) {
            case ARRIVED -> {
                ChatUtils.debug("已到达 %s=%.3f", axisName(axis), coord);
                finish();
            }
            case FAILED -> fail("移动 " + axisName(axis) + " 轴失败: " + driver.failure()
                + "（停在 " + String.format("%.3f", coord) + "）");
            case MOVING -> {
                // 继续
            }
        }
    }

    @Override
    protected void onEnd() {
        FlightVelocity.end();
        ChatUtils.debug("飞行结束");

        // 失败 / 被取消时不要记录边，避免写下实际没走通的路
        if (failureReason() == null && startBlock != null && dimension != null) {
            WaypointManager.get().recordMove(dimension, startBlock, endBlock);
        }
    }

    public static String axisName(Direction.Axis axis) {
        return switch (axis) {
            case X -> "x";
            case Y -> "y";
            case Z -> "z";
        };
    }
}
