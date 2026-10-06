package org.six_coin.playerController.client.waypoint;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

/**
 * 编辑模式下监听下界传送门。
 *
 * <p>规则：当你站在一个「下界传送门方块」里、而且这个方块本身是一个路径点，
 * 那么你被传送走（维度发生变化）之后，就把这个路径点和落地方块连一条 0 长度传送门边。
 *
 * <p>因为传送是服务端做的，客户端只能观察到维度变了，所以这里会先记住传送前所在的路径点。
 */
public final class PortalTracker {

    /** 维度变化之后再等几 tick 才记录，等服务端把落点坐标同步过来。 */
    private static final int ARRIVAL_DELAY_TICKS = 5;

    @Nullable
    private static String lastDimension;
    @Nullable
    private static Waypoint pendingSource;
    private static int arrivalTicks = -1;

    private PortalTracker() {
    }

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            lastDimension = null;
            pendingSource = null;
            arrivalTicks = -1;
            return;
        }

        String dimension = DimensionUtils.current();

        if (lastDimension == null) {
            lastDimension = dimension;
            return;
        }

        if (!dimension.equals(lastDimension)) {
            lastDimension = dimension;
            if (pendingSource != null) {
                arrivalTicks = 0;
            }
        }

        // 传送完成后的等待阶段
        if (arrivalTicks >= 0) {
            arrivalTicks++;
            if (arrivalTicks >= ARRIVAL_DELAY_TICKS) {
                Waypoint source = pendingSource;
                arrivalTicks = -1;
                pendingSource = null;
                if (source != null) {
                    BlockPos arrival = BlockPos.ofFloored(client.player.getEntityPos());
                    // 落点本身也得在下界传送门里，否则可能是死亡重生之类的维度变化
                    if (!insideNetherPortal(client, arrival)) {
                        ChatUtils.debug("维度变化了，但落点 " + arrival.toShortString()
                            + " 不在下界传送门里，不记录传送门边");
                    } else {
                        WaypointManager.get().recordPortalLink(source, dimension, arrival);
                    }
                }
            }
            return;
        }

        if (!WaypointManager.get().isEditMode()) {
            pendingSource = null;
            return;
        }

        // 只有「站在下界传送门方块里 + 这个方块是路径点」才记
        BlockPos here = BlockPos.ofFloored(client.player.getEntityPos());
        if (!insideNetherPortal(client, here)) {
            pendingSource = null;
            return;
        }

        Waypoint waypoint = WaypointManager.get().graph().at(dimension, here);
        if (waypoint == null) {
            pendingSource = null;
            return;
        }

        if (pendingSource == null || pendingSource.id() != waypoint.id()) {
            ChatUtils.debug("检测到站在下界传送门路径点 " + here.toShortString() + " 里，等待传送…");
        }
        pendingSource = waypoint;
    }

    /** 这个方块（或者它上下 1 格）是不是下界传送门方块。 */
    private static boolean insideNetherPortal(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        return client.world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)
            || client.world.getBlockState(pos.up()).isOf(Blocks.NETHER_PORTAL)
            || client.world.getBlockState(pos.down()).isOf(Blocks.NETHER_PORTAL);
    }
}
