package org.six_coin.playerController.client.action;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.feature.NoFall;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.List;

/**
 * /pc move to：沿着路径点图上的最短路一路飞过去。
 *
 * <p>和一段一段调用 {@link MoveAction} 的区别：
 * <ul>
 *   <li>整个过程只开关一次飞行和 NoFall，中途不取消飞行；</li>
 *   <li>同轴连续的几段会合并成一步（1 1 1 → 1 1 2 → 1 1 3 直接走 1 1 1 → 1 1 3）。</li>
 * </ul>
 */
public class PathMoveAction extends Action {

    /** 一段同轴移动。 */
    private record Segment(Direction.Axis axis, int blocks, BlockPos from, BlockPos to) {
    }

    private final List<BlockPos> path;
    private final List<Segment> segments = new ArrayList<>();

    private int index;
    private MoveDriver driver;
    private boolean engaged;
    private boolean finishedAll;

    public PathMoveAction(List<BlockPos> path) {
        this.path = path;
    }

    @Override
    public String name() {
        if (path.isEmpty()) return "沿路径移动";
        BlockPos last = path.get(path.size() - 1);
        return "沿路径移动到 " + last.toShortString() + "（" + Math.max(0, path.size() - 1) + " 段）";
    }

    @Override
    public boolean isMovement() {
        return true;
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }
        if (path.size() < 2) {
            fail("路径太短");
            return;
        }

        BlockPos current = BlockPos.ofFloored(player.getEntityPos());
        if (!current.equals(path.get(0))) {
            fail("当前位置 " + current.toShortString() + " 和路径起点 "
                + path.get(0).toShortString() + " 不一致");
            return;
        }

        buildSegments();
        if (segments.isEmpty()) {
            ChatUtils.debug("起点和终点相同，不需要移动");
            finish();
            return;
        }

        NoFall.acquire();
        FlightVelocity.begin();
        engaged = true;

        ChatUtils.debug("开始走路径，共 " + segments.size() + " 步（原始 " + (path.size() - 1)
            + " 段），速度 " + PlayerControllerConfig.getMoveSpeed() + " 格/tick，中途不取消飞行");

        index = 0;
        player.setYaw(yawFor(segments.get(0)));
    }

    /** 把连续的、同一个轴向上的段合并成一步。 */
    private void buildSegments() {
        List<BlockPos> merged = new ArrayList<>();
        merged.add(path.get(0));

        int i = 1;
        while (i < path.size()) {
            Direction.Axis axis = WaypointGraph.sharedAxis(path.get(i - 1), path.get(i));
            if (axis == null) {
                // 理论上不会发生：图上的边一定是单轴的
                merged.add(path.get(i));
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < path.size() && WaypointGraph.sharedAxis(path.get(j), path.get(j + 1)) == axis) {
                j++;
            }
            merged.add(path.get(j));
            i = j + 1;
        }

        for (int k = 0; k + 1 < merged.size(); k++) {
            BlockPos from = merged.get(k);
            BlockPos to = merged.get(k + 1);
            Direction.Axis axis = WaypointGraph.sharedAxis(from, to);
            if (axis == null) continue;
            int blocks = WaypointGraph.coord(to, axis) - WaypointGraph.coord(from, axis);
            if (blocks == 0) continue;
            segments.add(new Segment(axis, blocks, from, to));
        }
    }

    private float yawFor(Segment segment) {
        return switch (segment.axis()) {
            case X -> segment.blocks() > 0 ? -90.0f : 90.0f;
            case Z -> segment.blocks() > 0 ? 0.0f : 180.0f;
            case Y -> PlayerUtils.player() == null ? 0.0f : PlayerUtils.player().getYaw();
        };
    }

    // ------------------------------------------------------------------

    @Override
    protected void tick() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        if (index >= segments.size()) {
            if (!finishedAll) {
                // 全部走完，把最后一点浮点误差抹掉，精确停在终点方块中心
                finishedAll = true;
                snapIfNeeded(player, path.get(path.size() - 1));
            }
            finish();
            return;
        }

        Segment segment = segments.get(index);

        if (driver == null) {
            // 每段开始前精确对齐到起点方块中心，避免浮点误差累积。
            // 注意这里不能 return：这一 tick 必须马上写入速度，否则上一 tick 残留的重力
            // 会让玩家在拐角处往下掉一点。
            snapIfNeeded(player, segment.from());
            driver = new MoveDriver(segment.axis(), PlayerUtils.axisValue(segment.axis(), player) + segment.blocks(), segment.blocks());
            ChatUtils.debug("第 " + (index + 1) + "/" + segments.size() + " 步："
                + segment.from().toShortString() + " → " + segment.to().toShortString()
                + "（" + MoveAction.axisName(segment.axis()) + " " + segment.blocks() + "）");
        }

        double coord = PlayerUtils.axisValue(segment.axis(), player);
        switch (driver.tick(coord)) {
            case ARRIVED -> {
                ChatUtils.debug("到达 " + segment.to().toShortString());
                driver = null;
                index++;
            }
            case FAILED -> fail("第 " + (index + 1) + " 步失败: " + driver.failure()
                + "（停在 " + String.format("%.3f", coord) + "）");
            case MOVING -> {
                // 继续
            }
        }
    }

    @Override
    protected void onEnd() {
        if (engaged) {
            FlightVelocity.end();
            NoFall.release();
            engaged = false;
        }

        WaypointManager manager = WaypointManager.get();
        // 失败 / 被取消时不要记录边，避免写下实际没走通的路
        if (failureReason() == null && manager.isEditMode()) {
            for (Segment segment : segments) {
                manager.recordMove(segment.from(), segment.to());
            }
        }
    }

    /** 玩家偏离方块中心时，直接对齐过去。 */
    private static void snapIfNeeded(ClientPlayerEntity player, BlockPos pos) {
        Vec3d center = PlayerUtils.blockCenter(pos);
        double dx = player.getX() - center.x;
        double dy = player.getY() - center.y;
        double dz = player.getZ() - center.z;
        if (dx * dx + dy * dy + dz * dz > 1.0E-6) {
            PlayerUtils.snapToBlockCenter(pos);
        }
    }
}
