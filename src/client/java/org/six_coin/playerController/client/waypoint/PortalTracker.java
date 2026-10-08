package org.six_coin.playerController.client.waypoint;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PortalUtils;

/**
 * 记录「下界传送门边」。
 *
 * <p>触发条件很窄：只有 {@code /pc move <轴>} / {@code /pc move face}（也就是
 * {@link org.six_coin.playerController.client.action.MoveAction}）的<b>终点方块</b>是
 * 「下面垫着黑曜石的下界传送门方块」时，{@link #arm} 才会把这次传送武装起来。
 * 玩家平时自己走进传送门是<b>不会</b>记录的；{@code /pc move to} 也不会记录
 * （它只走图上已经有的边）。
 *
 * <p>武装之后要连着过三道校验才会写边：
 * <ol>
 *   <li>玩家确实站进了终点那个传送门方块，并且那一格现在仍然是
 *       「下界传送门 + 下面是黑曜石」；</li>
 *   <li>玩家被服务端传送走（维度变化），且确实是换了一个维度；</li>
 *   <li>落点方块也必须是「下界传送门 + 下面是黑曜石」。</li>
 * </ol>
 *
 * <p>因为传送是服务端做的，客户端只能观察到维度变了，所以这里会先记住传送前的方块。
 */
public final class PortalTracker {

    /** 维度变化之后再等几 tick 才记录，等服务端把落点坐标同步过来。 */
    private static final int ARRIVAL_DELAY_TICKS = 5;

    /** 武装状态最多留这么久（20 tick = 1 秒；移动速度调得很慢时也够走完）。 */
    private static final int MAX_ARM_TICKS = 20 * 60;

    /** 被武装起来的传送门方块（某次移动的终点）。 */
    @Nullable
    private static String armedDimension;
    @Nullable
    private static BlockPos armedPos;
    private static int armedTicks = -1;

    /** 玩家是不是已经站进那个传送门方块里了。 */
    private static boolean entered;

    @Nullable
    private static String lastDimension;
    private static int arrivalTicks = -1;

    private PortalTracker() {
    }

    /**
     * 一次移动的终点方块是「下面垫着黑曜石的下界传送门」时调用，把这次传送武装起来。
     *
     * <p>只有武装过的传送门才会被记录。
     */
    public static void arm(String dimension, BlockPos pos) {
        armedDimension = dimension;
        armedPos = pos.toImmutable();
        armedTicks = 0;
        entered = false;
        ChatUtils.debug("编辑模式：终点 " + pos.toShortString()
            + " 是下界传送门方块，等你走进去被传走之后记录传送门边");
    }

    /** 移动失败 / 被取消时调用：这次传送不记了。 */
    public static void disarm() {
        armedDimension = null;
        armedPos = null;
        armedTicks = -1;
        entered = false;
    }

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            // 世界暂时不在了（切维度的一瞬间也可能是这样）：武装状态和 lastDimension 都留着，
            // 等世界回来之后再判断维度到底变没变
            return;
        }

        String dimension = DimensionUtils.current();

        if (lastDimension == null) {
            lastDimension = dimension;
            return;
        }

        // 维度变了：如果这次传送被武装过，就进入「等落点坐标」阶段
        if (!dimension.equals(lastDimension)) {
            lastDimension = dimension;
            if (armedPos != null && arrivalTicks < 0) {
                if (entered) {
                    arrivalTicks = 0;
                } else {
                    ChatUtils.debug("维度变了，但没看到你站进终点传送门方块 "
                        + armedPos.toShortString() + "，不记录传送门边");
                    disarm();
                }
            }
        }

        // 传送完成后的等待阶段
        if (arrivalTicks >= 0) {
            arrivalTicks++;
            if (arrivalTicks >= ARRIVAL_DELAY_TICKS) {
                arrivalTicks = -1;
                record(client, dimension);
            }
            return;
        }

        if (armedPos == null) return;

        if (!WaypointManager.get().isEditMode()) {
            disarm();
            return;
        }

        if (armedTicks++ > MAX_ARM_TICKS) {
            ChatUtils.debug("等了太久也没被传送走，不再记录传送门边");
            disarm();
            return;
        }

        if (!dimension.equals(armedDimension)) return;   // 还没被传走

        // 站进终点那个传送门方块里了吗
        BlockPos here = BlockPos.ofFloored(client.player.getEntityPos());
        if (!here.equals(armedPos)) return;

        // 而且那一格现在仍然得是「下界传送门 + 下面是黑曜石」
        if (!PortalUtils.isNetherPortalOnObsidian(armedPos)) {
            ChatUtils.debug("终点 " + armedPos.toShortString()
                + " 已经不是「下面垫着黑曜石的下界传送门方块」，不记录传送门边");
            disarm();
            return;
        }

        if (!entered) {
            entered = true;
            ChatUtils.debug("已站进下界传送门 " + armedPos.toShortString() + "，等待服务端传送…");
        }
    }

    /** 两端都校验通过才写边。 */
    private static void record(MinecraftClient client, String arrivalDimension) {
        String sourceDimension = armedDimension;
        BlockPos sourcePos = armedPos;
        disarm();

        if (sourcePos == null || sourceDimension == null) return;
        if (client.player == null) return;
        if (sourceDimension.equals(arrivalDimension)) return;

        BlockPos arrival = BlockPos.ofFloored(client.player.getEntityPos());

        // 落点也得是「下界传送门 + 下面是黑曜石」，否则可能是死亡重生之类的维度变化
        if (!PortalUtils.isNetherPortalOnObsidian(arrival)) {
            ChatUtils.debug("维度变化了，但落点 " + arrival.toShortString()
                + " 不是「下面垫着黑曜石的下界传送门方块」（该处是 "
                + PortalUtils.blockId(arrival) + "，下方是 " + PortalUtils.blockId(arrival.down())
                + "），不记录传送门边");
            return;
        }

        WaypointGraph graph = WaypointManager.get().graph();
        Waypoint source = graph.at(sourceDimension, sourcePos);
        if (source == null) {
            // 正常流程下移动本身已经把终点记成路径点了，这里兜个底
            source = graph.ensureWaypoint(sourceDimension, sourcePos);
        }

        if (!WaypointManager.get().recordPortalLink(source, arrivalDimension, arrival)) {
            ChatUtils.debug("传送门边已经存在，不重复记录");
        }
    }
}
