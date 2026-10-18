package org.six_coin.playerController.client.mine.station;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 把挖掘站相关的方块按部分的颜色画成方块描边（{@code /pc mine complex station show} 开关）。
 *
 * <p>颜色见 {@link MineStationPart}；只画当前维度的，太远的（超过 {@link #MAX_DISTANCE}）不画。
 */
public final class MineStationRenderer {

    private static final float LINE_WIDTH = 2.0f;

    private static final double MAX_DISTANCE = 128.0;

    private MineStationRenderer() {
    }

    public static void register() {
        WorldRenderEvents.END_MAIN.register(MineStationRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MineStationManager manager = MineStationManager.get();
        if (!manager.isShowing()) return;
        if (manager.totalCount() == 0) return;
        if (context.worldState() == null || context.worldState().cameraRenderState == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        try {
            Vec3d camera = context.worldState().cameraRenderState.pos;
            if (camera == null) return;

            MatrixStack matrices = context.matrices();
            VertexConsumer buffer = context.consumers().getBuffer(RenderLayers.lines());
            String dimension = DimensionUtils.current();

            for (MineStationPart part : MineStationPart.values()) {
                for (MinePlace place : manager.positions(part)) {
                    if (!place.dimension().equals(dimension)) continue;

                    BlockPos pos = place.pos();
                    double dx = pos.getX() + 0.5 - camera.x;
                    double dy = pos.getY() + 0.5 - camera.y;
                    double dz = pos.getZ() + 0.5 - camera.z;
                    if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE) continue;

                    VertexRendering.drawOutline(
                        matrices,
                        buffer,
                        VoxelShapes.fullCube(),
                        pos.getX() - camera.x,
                        pos.getY() - camera.y,
                        pos.getZ() - camera.z,
                        part.color(),
                        LINE_WIDTH);
                }
            }
        } catch (Exception e) {
            // 渲染出错就不要拖垮整个游戏
            manager.setShow(false);
            ChatUtils.error("挖掘站高亮渲染出错，已自动关闭: " + e);
        }
    }
}
