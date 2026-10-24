package org.six_coin.playerController.client.mine.station;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 把挖掘站相关的方块按部分的颜色画成方块描边（{@code /pc mine station show} 开关）。
 *
 * <p>颜色见 {@link MineStationPart}；只画当前维度的，太远的（超过 {@link #MAX_DISTANCE}）不画。
 */
public final class MineStationRenderer {

    private static final float LINE_WIDTH = 2.0f;

    private static final double MAX_DISTANCE = 128.0;

    private MineStationRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(MineStationRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        MineStationManager manager = MineStationManager.get();
        if (!manager.isShowing()) return;
        if (manager.totalCount() == 0) return;
        if (context.levelState() == null || context.levelState().cameraRenderState == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        try {
            Vec3 camera = context.levelState().cameraRenderState.pos;
            if (camera == null) return;

            PoseStack matrices = context.poseStack();
            String dimension = DimensionUtils.current();

            for (MineStationPart part : MineStationPart.values()) {
                for (MinePlace place : manager.positions(part)) {
                    if (!place.dimension().equals(dimension)) continue;

                    BlockPos pos = place.pos();
                    double dx = pos.getX() + 0.5 - camera.x;
                    double dy = pos.getY() + 0.5 - camera.y;
                    double dz = pos.getZ() + 0.5 - camera.z;
                    if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE) continue;

                    drawOutline(context, matrices, pos, camera, part.color());
                }
            }
        } catch (Exception e) {
            // 渲染出错就不要拖垮整个游戏
            manager.setShow(false);
            ChatUtils.error("挖掘站高亮渲染出错，已自动关闭: " + e);
        }
    }

    /** 画一个方块描边：把矩阵挪到方块位置，交给提交收集器画（26.x 的渲染管线不再直接写顶点缓冲）。 */
    private static void drawOutline(LevelRenderContext context, PoseStack matrices, BlockPos pos, Vec3 camera, int color) {
        matrices.pushPose();
        matrices.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        context.submitNodeCollector().submitShapeOutline(matrices, Shapes.block(), RenderTypes.lines(), color, LINE_WIDTH, false);
        matrices.popPose();
    }
}
