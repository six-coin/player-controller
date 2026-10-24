package org.six_coin.playerController.client.waypoint;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 把路径点画成方块描边、边上的每个方块也画描边。
 *
 * <p>普通路径点红色、普通边黄色；和传送门有关的路径点紫色。
 * 只画玩家当前维度里的东西（否则下界的点会叠在主世界同样的坐标上）。
 */
public final class WaypointRenderer {

    /** 普通路径点：红色。 */
    private static final int WAYPOINT_COLOR = 0xFFFF3030;

    /** 和传送门有关的路径点：紫色。 */
    private static final int PORTAL_COLOR = 0xFFB040FF;

    /** 出生点：绿色。 */
    private static final int SPAWN_COLOR = 0xFF40FF40;

    /** 边：黄色。 */
    private static final int EDGE_COLOR = 0xFFFFE030;

    private static final float LINE_WIDTH = 2.0f;

    /** 超过这个距离的方块不画（避免长边把顶点缓冲撑爆）。 */
    private static final double MAX_DISTANCE = 128.0;

    /** 一帧最多画多少个方块描边。 */
    private static final int MAX_BOXES = 4000;

    private WaypointRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(WaypointRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        WaypointManager manager = WaypointManager.get();
        if (!manager.isShowing()) return;
        if (manager.graph().isEmpty()) return;
        if (context.levelState() == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        try {
            if (context.levelState().cameraRenderState == null) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            if (camera == null) return;

            PoseStack matrices = context.poseStack();

            WaypointGraph graph = manager.graph();
            String dimension = DimensionUtils.current();
            int budget = MAX_BOXES;

            // 先画边，再画路径点，这样点在线的上面
            for (WaypointGraph.EdgeEntry entry : graph.allEdges()) {
                Edge edge = entry.edge();
                // 单向边要么是跨维度的传送门（画线没意义），要么是出生点重合（同坐标，退化），都不画
                if (!edge.bi()) continue;
                Waypoint a = graph.get(edge.from());
                Waypoint b = graph.get(edge.to());
                if (a == null || b == null) continue;
                if (!a.dimension().equals(dimension) || !b.dimension().equals(dimension)) continue;
                if (!segmentNear(a.pos(), b.pos(), camera)) continue;

                for (BlockPos pos : WaypointGraph.blocksAlong(a.pos(), b.pos())) {
                    if (budget <= 0) break;
                    if (tooFar(pos, camera)) continue;
                    drawBox(context, matrices, pos, camera, EDGE_COLOR);
                    budget--;
                }
            }

            for (Waypoint waypoint : graph.allWaypoints()) {
                if (budget <= 0) break;
                // 出生点最后单独画，保证和普通路径点重合时绿色在最上面
                if (waypoint.isSpawn()) continue;
                if (!waypoint.dimension().equals(dimension)) continue;
                if (tooFar(waypoint.pos(), camera)) continue;
                drawBox(context, matrices, waypoint.pos(), camera, colorFor(graph, waypoint));
                budget--;
            }

            Waypoint spawn = graph.spawnNode();
            if (budget > 0 && spawn != null
                && spawn.dimension().equals(dimension)
                && !tooFar(spawn.pos(), camera)) {
                drawBox(context, matrices, spawn.pos(), camera, SPAWN_COLOR);
            }
        } catch (Exception e) {
            // 渲染出错就不要拖垮整个游戏
            manager.setShow(false);
            org.six_coin.playerController.client.util.ChatUtils.error("路径点渲染出错，已自动关闭显示: " + e);
        }
    }

    /** 出生点绿色，传送门相关的紫色，其余红色。 */
    private static int colorFor(WaypointGraph graph, Waypoint waypoint) {
        if (waypoint.isSpawn()) return SPAWN_COLOR;
        if (graph.isPortalWaypoint(waypoint.id())) return PORTAL_COLOR;
        return WAYPOINT_COLOR;
    }


    private static boolean tooFar(BlockPos pos, Vec3 camera) {        double dx = pos.getX() + 0.5 - camera.x;
        double dy = pos.getY() + 0.5 - camera.y;
        double dz = pos.getZ() + 0.5 - camera.z;
        return dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE;
    }

    /** 整条边离摄像机最近的距离是否在范围内（先用它把远处的长边整条跳过）。 */
    private static boolean segmentNear(BlockPos a, BlockPos b, Vec3 camera) {
        double ax = a.getX() + 0.5;
        double ay = a.getY() + 0.5;
        double az = a.getZ() + 0.5;
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();

        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = 0.0;
        if (lengthSq > 0.0) {
            t = ((camera.x - ax) * dx + (camera.y - ay) * dy + (camera.z - az) * dz) / lengthSq;
            t = Math.max(0.0, Math.min(1.0, t));
        }

        double cx = camera.x - (ax + t * dx);
        double cy = camera.y - (ay + t * dy);
        double cz = camera.z - (az + t * dz);
        return cx * cx + cy * cy + cz * cz <= MAX_DISTANCE * MAX_DISTANCE;
    }

    private static void drawBox(LevelRenderContext context, PoseStack matrices, BlockPos pos, Vec3 camera, int color) {
        // 26.x 的渲染管线不再直接写顶点缓冲：把矩阵挪到方块位置，交给提交收集器去画描边
        matrices.pushPose();
        matrices.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        context.submitNodeCollector().submitShapeOutline(
            matrices, Shapes.block(), RenderTypes.lines(), color, LINE_WIDTH, false);
        matrices.popPose();
    }
}
