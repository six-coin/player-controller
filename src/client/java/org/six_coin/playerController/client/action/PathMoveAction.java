package org.six_coin.playerController.client.action;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.feature.FlightVelocity;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.PortalUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.List;

/**
 * /pc move to：沿着路径点图上的最短路一路飞过去，可以跨传送门。
 *
 * <p>和一段一段调用 {@link MoveAction} 的区别：
 * <ul>
 *   <li>整个过程只开关一次飞行，中途不取消飞行；</li>
 *   <li>同轴连续的几段会合并成一步（1 1 1 → 1 1 2 → 1 1 3 直接走 1 1 1 → 1 1 3）；</li>
 *   <li>遇到传送门边会自己走进去、等服务端传送、切维度后接着走。</li>
 * </ul>
 */
public class PathMoveAction extends Action {

    private enum StepKind {
        /** 同一维度里沿轴走一段。 */
        MOVE,
        /** 下界传送门（记录时是双向的 0 长度边）。 */
        NETHER_PORTAL,
        /** 末地传送门（单向边）。 */
        END_PORTAL
    }

    private record Step(StepKind kind, Waypoint from, Waypoint to, Direction.Axis axis, int blocks) {
    }

    /** 等下界传送门传送的最长时间（原版是 80 tick）。 */
    private static final int MAX_PORTAL_WAIT_TICKS = 400;

    /** 维度变化后再等几 tick，让服务端把落点坐标同步过来。 */
    private static final int ARRIVAL_DELAY_TICKS = 5;

    /** 落点偏差超过这个值就认为传送门对不上了。 */
    private static final int ARRIVAL_TOLERANCE = 2;

    private final List<Waypoint> path;
    private final List<Step> steps = new ArrayList<>();

    private int index;
    private MoveDriver driver;
    private boolean engaged;
    private boolean finishedAll;

    // 传送门过程的状态
    private boolean portalEntered;
    private int portalWaitTicks;
    private int arrivalTicks = -1;

    public PathMoveAction(List<Waypoint> path) {
        this.path = path;
    }

    @Override
    public String name() {
        if (path.isEmpty()) return "沿路径移动";
        Waypoint last = path.get(path.size() - 1);
        return "沿路径移动到 " + DimensionUtils.display(last.dimension()) + " " + last.coordString()
            + "（" + Math.max(0, path.size() - 1) + " 段）";
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

        Waypoint start = path.get(0);
        if (!DimensionUtils.current().equals(start.dimension())) {
            fail("你在 " + DimensionUtils.display(DimensionUtils.current())
                + "，但路径起点在 " + DimensionUtils.display(start.dimension()));
            return;
        }
        BlockPos current = BlockPos.ofFloored(player.getEntityPos());
        if (!current.equals(start.pos())) {
            fail("当前位置 " + current.toShortString() + " 和路径起点 "
                + start.pos().toShortString() + " 不一致");
            return;
        }

        buildSteps();
        if (steps.isEmpty()) {
            ChatUtils.debug("起点和终点相同，不需要移动");
            finish();
            return;
        }

        // 终点如果在末地传送门处，编辑模式下先连一条到末地的单向边。
        // 放在这里是因为路径一旦穿过末地传送门，mc.world 就不是原来的维度了。
        Waypoint last = path.get(path.size() - 1);
        if (last.dimension().equals(DimensionUtils.current())) {
            WaypointManager.get().recordEndPortalLink(last.dimension(), last.pos());
        }

        FlightVelocity.begin();
        engaged = true;

        ChatUtils.debug("开始走路径，共 " + steps.size() + " 步（原始 " + (path.size() - 1)
            + " 段），速度 " + PlayerControllerConfig.getMoveSpeed() + " 格/tick，中途不取消飞行");

        index = 0;
        player.setYaw(yawFor(steps.get(0)));
    }

    private WaypointGraph graph() {
        return WaypointManager.get().graph();
    }

    /** 把连续的、同一维度同一个轴向上的段合并成一步；跨维度的那一跳单独成一步。 */
    private void buildSteps() {
        int i = 0;
        while (i < path.size() - 1) {
            Waypoint a = path.get(i);
            Waypoint b = path.get(i + 1);

            if (!a.dimension().equals(b.dimension())) {
                StepKind kind = graph().hasEdge(a.id(), b.id())
                    ? StepKind.NETHER_PORTAL
                    : StepKind.END_PORTAL;
                steps.add(new Step(kind, a, b, null, 0));
                i++;
                continue;
            }

            Direction.Axis axis = WaypointGraph.sharedAxis(a.pos(), b.pos());
            if (axis == null) {
                // 图上的普通边一定是单轴的，理论上到不了这里
                i++;
                continue;
            }

            int j = i + 1;
            while (j + 1 < path.size()
                && path.get(j).dimension().equals(a.dimension())
                && path.get(j + 1).dimension().equals(a.dimension())
                && WaypointGraph.sharedAxis(path.get(j).pos(), path.get(j + 1).pos()) == axis) {
                j++;
            }

            Waypoint end = path.get(j);
            int blocks = WaypointGraph.coord(end.pos(), axis) - WaypointGraph.coord(a.pos(), axis);
            if (blocks != 0) {
                steps.add(new Step(StepKind.MOVE, a, end, axis, blocks));
            }
            i = j;
        }
    }

    private float yawFor(Step step) {
        if (step.kind() != StepKind.MOVE) {
            ClientPlayerEntity player = PlayerUtils.player();
            return player == null ? 0.0f : player.getYaw();
        }
        return switch (step.axis()) {
            case X -> step.blocks() > 0 ? -90.0f : 90.0f;
            case Z -> step.blocks() > 0 ? 0.0f : 180.0f;
            case Y -> PlayerUtils.player() == null ? 0.0f : PlayerUtils.player().getYaw();
        };
    }

    // ------------------------------------------------------------------

    @Override
    protected void tick() {
        if (PlayerUtils.player() == null) {
            fail("玩家不存在");
            return;
        }

        if (index >= steps.size()) {
            if (!finishedAll) {
                finishedAll = true;
                ClientPlayerEntity player = PlayerUtils.player();
                if (player != null) {
                    snapIfNeeded(player, path.get(path.size() - 1).pos());
                }
            }
            finish();
            return;
        }

        Step step = steps.get(index);
        if (step.kind() == StepKind.MOVE) {
            tickMove(step);
        } else {
            tickPortal(step);
        }
    }

    private void tickMove(Step step) {
        if (!DimensionUtils.current().equals(step.from().dimension())) {
            // 传送门方块没有碰撞，走到那格会立刻被服务端传走，
            // 可能这一段还没判定「到达」就已经换维度了。
            // 如果后面紧跟着一个传送门步骤，就当成已经到达，交给它处理。
            if (index + 1 < steps.size() && steps.get(index + 1).kind() != StepKind.MOVE) {
                ChatUtils.debug("这一段还没走完就被传送到了 "
                    + DimensionUtils.display(DimensionUtils.current()) + "，直接进入传送门步骤");
                driver = null;
                index++;
                return;
            }
            fail("移动过程中维度变了（应该在 " + DimensionUtils.display(step.from().dimension()) + "）");
            return;
        }

        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        if (driver == null) {
            // 每段开始前精确对齐到起点方块中心，避免浮点误差累积。
            // 注意这里不能 return：这一 tick 必须马上写入速度，否则上一 tick 残留的重力
            // 会让玩家在拐角处往下掉一点。
            snapIfNeeded(player, step.from().pos());
            driver = new MoveDriver(step.axis(),
                PlayerUtils.axisValue(step.axis(), player) + step.blocks(), step.blocks());
            ChatUtils.debug("第 " + (index + 1) + "/" + steps.size() + " 步："
                + step.from().coordString() + " → " + step.to().coordString()
                + "（" + MoveAction.axisName(step.axis()) + " " + step.blocks() + "）");
        }

        double coord = PlayerUtils.axisValue(step.axis(), player);
        switch (driver.tick(coord)) {
            case ARRIVED -> {
                ChatUtils.debug("到达 " + step.to().coordString());
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

    // ------------------------------------------------------------------

    private void tickPortal(Step step) {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        String fromDimension = step.from().dimension();

        // 阶段一：先站进传送门方块里
        if (!portalEntered) {
            portalEntered = true;
            portalWaitTicks = 0;
            driver = null;

            BlockPos source = step.from().pos();
            boolean nether = step.kind() == StepKind.NETHER_PORTAL;

            // 传送可能在上一步结束时就已经触发了（踩进传送门方块会被立刻传走），
            // 这时候 mc.world 已经是目标维度了，再去源维度找传送门方块肯定找不到。
            if (!DimensionUtils.current().equals(fromDimension)) {
                ChatUtils.debug("进入传送门步骤时已经在 "
                    + DimensionUtils.display(DimensionUtils.current())
                    + " 了（上一步结束时就被传送了），直接校验落点");
                arrivalTicks = 0;
                return;
            }

            // 方块本身、下面 1 格、上面 1 格、四周都找一遍。
            // 末地回主世界的祭坛经常是「站在传送门方块上方」或者「站在边上」，
            // 只看自己和下面会找不到。
            BlockPos portalPos = PortalUtils.findNear(source, nether);
            if (portalPos == null) {
                fail("传送门点 " + DimensionUtils.display(fromDimension) + " " + source.toShortString()
                    + " 附近没有" + (nether ? "下界" : "末地") + "传送门方块"
                    + "（该处是 " + PortalUtils.blockId(source)
                    + "，下方是 " + PortalUtils.blockId(source.down()) + "）");
                return;
            }

            if (!portalPos.equals(source)) {
                ChatUtils.debug("从 " + source.toShortString()
                    + " 对齐到传送门方块 " + portalPos.toShortString());
            }
            PlayerUtils.snapToBlockCenter(portalPos);

            ChatUtils.debug("已进入" + (nether ? "下界" : "末地") + "传送门（"
                + DimensionUtils.display(fromDimension) + " " + portalPos.toShortString()
                + "），等待服务端传送…");
        }

        // 阶段二：悬停在传送门里等维度变化
        if (arrivalTicks < 0) {
            FlightVelocity.apply(Vec3d.ZERO);
            portalWaitTicks++;

            if (!DimensionUtils.current().equals(fromDimension)) {
                arrivalTicks = 0;
                ChatUtils.info("已传送到 " + DimensionUtils.display(DimensionUtils.current()));
                return;
            }
            if (portalWaitTicks > MAX_PORTAL_WAIT_TICKS) {
                fail("等待传送超时（" + portalWaitTicks + " tick）");
            }
            return;
        }

        // 阶段三：等落点坐标同步，然后校验并继续
        arrivalTicks++;
        FlightVelocity.apply(Vec3d.ZERO);
        if (arrivalTicks < ARRIVAL_DELAY_TICKS) return;

        String nowDimension = DimensionUtils.current();
        if (!nowDimension.equals(step.to().dimension())) {
            fail("传送后到了 " + DimensionUtils.display(nowDimension)
                + "，期望 " + DimensionUtils.display(step.to().dimension()));
            return;
        }

        ClientPlayerEntity arrived = PlayerUtils.player();
        if (arrived == null) {
            fail("传送后玩家不存在");
            return;
        }

        BlockPos actual = BlockPos.ofFloored(arrived.getEntityPos());
        if (!actual.equals(step.to().pos())) {
            int dx = Math.abs(actual.getX() - step.to().pos().getX());
            int dy = Math.abs(actual.getY() - step.to().pos().getY());
            int dz = Math.abs(actual.getZ() - step.to().pos().getZ());
            if (Math.max(dx, Math.max(dy, dz)) > ARRIVAL_TOLERANCE) {
                if (step.to().isSpawn()) {
                    // 从末地回来必须落在当前出生点，否则直接停下
                    fail("从末地回来没有落在当前出生点（实际 " + actual.toShortString()
                        + "，当前出生点 " + step.to().coordString() + "），已停止任务；"
                        + "用 /pc waypoints spawn add_here <name> 把实际落点登记成出生点再试");
                } else {
                    fail("落点 " + actual.toShortString() + " 和记录的路径点 "
                        + step.to().pos().toShortString() + " 对不上");
                }
                return;
            }
            ChatUtils.debug("落点 " + actual.toShortString() + " 和记录的 "
                + step.to().pos().toShortString() + " 略有偏差，已对齐");
            PlayerUtils.snapToBlockCenter(step.to().pos());
        }

        portalEntered = false;
        arrivalTicks = -1;
        portalWaitTicks = 0;
        driver = null;
        index++;
        ChatUtils.debug("传送完成，继续走剩下的路");
    }

    private static boolean isEndPortal(BlockPos pos) {
        return PortalUtils.isEndPortal(pos);
    }

    private static boolean isNetherPortal(BlockPos pos) {
        return PortalUtils.isNetherPortal(pos);
    }

    // ------------------------------------------------------------------

    @Override
    protected void onEnd() {
        if (engaged) {
            FlightVelocity.end();
            engaged = false;
        }

        WaypointManager manager = WaypointManager.get();
        // 失败 / 被取消时不要记录边，避免写下实际没走通的路
        if (failureReason() != null || !manager.isEditMode()) return;

        for (Step step : steps) {
            if (step.kind() != StepKind.MOVE) continue;
            manager.recordMove(step.from().dimension(), step.from().pos(), step.to().pos());
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
