package org.six_coin.playerController.client.waypoint;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/** 把路径点画成红色描边、边画成黄色描边。 */
public final class WaypointRenderer {

    /** 路径点：红色。 */
    private static final int WAYPOINT_COLOR = 0xFFFF3030;

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
        WorldRenderEvents.END_MAIN.register(WaypointRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        WaypointManager manager = WaypointManager.get();
        if (!manager.isShowing()) return;
        if (manager.graph().isEmpty()) return;
        if (context.worldState() == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        try {
            if (context.worldState().cameraRenderState == null) return;
            Vec3d camera = context.worldState().cameraRenderState.pos;
            if (camera == null) return;

            MatrixStack matrices = context.matrices();
            VertexConsumer buffer = context.consumers().getBuffer(RenderLayers.lines());

            WaypointGraph graph = manager.graph();
            int budget = MAX_BOXES;

            // 先画边，再画路径点，这样红点在黄线上面
            for (Edge edge : graph.allEdges()) {
                Waypoint a = graph.get(edge.a());
                Waypoint b = graph.get(edge.b());
                if (a == null || b == null) continue;
                if (!segmentNear(a.pos(), b.pos(), camera)) continue;

                for (BlockPos pos : WaypointGraph.blocksAlong(a.pos(), b.pos())) {
                    if (budget <= 0) break;
                    if (tooFar(pos, camera)) continue;
                    drawBox(matrices, buffer, pos, camera, EDGE_COLOR);
                    budget--;
                }
            }

            for (Waypoint waypoint : graph.allWaypoints()) {
                if (budget <= 0) break;
                if (tooFar(waypoint.pos(), camera)) continue;
                drawBox(matrices, buffer, waypoint.pos(), camera, WAYPOINT_COLOR);
                budget--;
            }
        } catch (Exception e) {
            // 渲染出错就不要拖垮整个游戏
            manager.setShow(false);
            org.six_coin.playerController.client.util.ChatUtils.error("路径点渲染出错，已自动关闭显示: " + e);
        }
    }

    private static boolean tooFar(BlockPos pos, Vec3d camera) {
        double dx = pos.getX() + 0.5 - camera.x;
        double dy = pos.getY() + 0.5 - camera.y;
        double dz = pos.getZ() + 0.5 - camera.z;
        return dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE;
    }

    /** 整条边离摄像机最近的距离是否在范围内（先用它把远处的长边整条跳过）。 */
    private static boolean segmentNear(BlockPos a, BlockPos b, Vec3d camera) {
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

    private static void drawBox(MatrixStack matrices, VertexConsumer buffer, BlockPos pos, Vec3d camera, int color) {
        VertexRendering.drawOutline(
            matrices,
            buffer,
            VoxelShapes.fullCube(),
            pos.getX() - camera.x,
            pos.getY() - camera.y,
            pos.getZ() - camera.z,
            color,
            LINE_WIDTH
        );
    }
}
