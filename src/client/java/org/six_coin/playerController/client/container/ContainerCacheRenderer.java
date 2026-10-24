package org.six_coin.playerController.client.container;

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
 * 把缓存里的容器画成紫色方块描边（{@code /pc container cache show} 开关）。
 *
 * <p>只画当前维度里的，太远的（超过 {@link #MAX_DISTANCE}）不画。
 */
public final class ContainerCacheRenderer {

    /** 紫色（和传送门路径点一个紫）。 */
    private static final int COLOR = 0xFFB040FF;

    private static final float LINE_WIDTH = 2.0f;

    /** 超过这个距离的方块不画。 */
    private static final double MAX_DISTANCE = 128.0;

    private ContainerCacheRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(ContainerCacheRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        ContainerCacheManager manager = ContainerCacheManager.get();
        if (!manager.isShowing()) return;
        if (manager.size() == 0) return;
        if (context.levelState() == null || context.levelState().cameraRenderState == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        try {
            Vec3 camera = context.levelState().cameraRenderState.pos;
            if (camera == null) return;

            PoseStack matrices = context.poseStack();
            String dimension = DimensionUtils.current();

            for (CachedContainer container : manager.all()) {
                if (!container.dimension().equals(dimension)) continue;

                BlockPos pos = container.pos();
                double dx = pos.getX() + 0.5 - camera.x;
                double dy = pos.getY() + 0.5 - camera.y;
                double dz = pos.getZ() + 0.5 - camera.z;
                if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE) continue;

                drawOutline(context, matrices, pos, camera, COLOR);
            }
        } catch (Exception e) {
            // 渲染出错就不要拖垮整个游戏
            manager.setShow(false);
            ChatUtils.error("容器缓存高亮渲染出错，已自动关闭: " + e);
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
