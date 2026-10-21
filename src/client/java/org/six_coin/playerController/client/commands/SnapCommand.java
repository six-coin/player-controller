package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.SnapUtils;
import org.six_coin.playerController.client.waypoint.WaypointManager;

/**
 * {@code /pc snap ...} —— 把玩家「贴」到某个坐标上（只用沿轴的三段直线飞过去）。
 *
 * <pre>
 * /pc snap absolute &lt;x&gt; &lt;y&gt; &lt;z&gt;   目的地就是 x y z（当前维度）
 * /pc snap relative &lt;dx&gt; &lt;dy&gt; &lt;dz&gt; 目的地是当前方块坐标 + 偏移
 * /pc snap network                   目的地是当前维度里最近的「路径点 / 边上的坐标」
 * </pre>
 *
 * <p>走法和判定都在 {@link SnapUtils} 里：6 种轴顺序挨个试，经过的每一格和它上面一格都得是空气；
 * 目的地必须已经加载；{@code network} 只在 1000 格内找，而且最近的不可达会继续试次近的。
 *
 * <p>执行方式和 {@code /pc move x/y/z <格数>} 一样：每段提交一个 {@link MoveAction}
 * （会先对齐方块中心、速度用 {@code actions.move_speed}）。
 */
public final class SnapCommand {

    private SnapCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("snap")
            .executes(context -> {
                showHelp(context.getSource());
                return 1;
            })
            .then(ClientCommandManager.literal("absolute").then(coords(
                context -> {
                    BlockPos target = new BlockPos(
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"));
                    return snap(context.getSource(), target, "absolute");
                })))
            .then(ClientCommandManager.literal("relative").then(deltas(
                context -> {
                    FabricClientCommandSource source = context.getSource();
                    BlockPos from = PlayerUtils.currentBlockPos();
                    if (from == null) {
                        source.sendError(Text.literal("拿不到玩家位置"));
                        return 0;
                    }

                    // 用 long 加：int 直接加会溢出回绕（比如 dx = 20 亿），绕回玩家附近反而能通过检查
                    long x = (long) from.getX() + IntegerArgumentType.getInteger(context, "dx");
                    long y = (long) from.getY() + IntegerArgumentType.getInteger(context, "dy");
                    long z = (long) from.getZ() + IntegerArgumentType.getInteger(context, "dz");
                    if (Math.abs(x) > SnapUtils.MAX_COORDINATE || Math.abs(y) > SnapUtils.MAX_COORDINATE
                        || Math.abs(z) > SnapUtils.MAX_COORDINATE) {
                        source.sendError(Text.literal("目的地 (" + x + ", " + y + ", " + z
                            + ") 已经超出世界范围（±" + SnapUtils.MAX_COORDINATE + "）了"));
                        return 0;
                    }
                    return snap(source, new BlockPos((int) x, (int) y, (int) z), "relative");
                })))
            .then(ClientCommandManager.literal("network")
                .executes(context -> network(context.getSource())));
    }

    private static void showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7snap：沿轴三段直线贴到某个坐标（先试 6 种轴顺序，取第一种通的）"));
        source.sendFeedback(Text.literal("  §f/pc snap absolute <x> <y> <z>§7 - 去这个坐标（当前维度）"));
        source.sendFeedback(Text.literal("  §f/pc snap relative <dx> <dy> <dz>§7 - 去当前坐标 + 偏移"));
        source.sendFeedback(Text.literal("  §f/pc snap network§7 - 去最近的路径点 / 边上的坐标（"
            + SnapUtils.SEARCH_RADIUS + " 格内、已加载的，最近的不可达就试次近的）"));
        source.sendFeedback(Text.literal("  §7经过的每一格和它上面一格都必须是空气；目的地不在已加载范围内直接拒绝"));
    }

    // ------------------------------------------------------------------
    // 三种入口
    // ------------------------------------------------------------------

    private static int network(FabricClientCommandSource source) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = source.getPlayer();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        BlockPos from = PlayerUtils.currentBlockPos();
        if (from == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }

        // 已经站在路径点体系里（路径点或者边上）就没必要再飞了
        if (WaypointManager.get().playerStartWaypoint() != null) {
            source.sendFeedback(Text.literal("你已经在路径点体系里了（" + from.toShortString() + "）"));
            return 1;
        }

        SnapUtils.Snap snap = SnapUtils.findSnap(mc, from);
        if (snap == null) {
            source.sendError(Text.literal("当前维度（" + DimensionUtils.display(DimensionUtils.current())
                + "）" + SnapUtils.SEARCH_RADIUS + " 格内没有能直线飞过去的路径点 / 边（已加载的都试过了）"));
            return 0;
        }

        if (snap.target().equals(from)) {
            source.sendFeedback(Text.literal("你已经在路径点上 " + from.toShortString() + " 了"));
            return 1;
        }

        submit(snap.plan());
        source.sendFeedback(Text.literal("已提交任务：snap 到 " + DimensionUtils.display(DimensionUtils.current())
            + " " + snap.target().toShortString() + "（network）"));
        source.sendFeedback(Text.literal("  走法 " + snap.plan().describeOrder()
            + "：" + snap.plan().describeSegments()));
        return 1;
    }

    /**
     * 执行一次 snap。
     *
     * @param how 命令形态（只用于聊天栏里说明）
     */
    private static int snap(FabricClientCommandSource source, BlockPos target, String how) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = source.getPlayer();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("没有玩家或世界"));
            return 0;
        }

        BlockPos from = PlayerUtils.currentBlockPos();
        if (from == null) {
            source.sendError(Text.literal("拿不到玩家位置"));
            return 0;
        }
        if (from.equals(target)) {
            source.sendFeedback(Text.literal("你已经站在 " + target.toShortString() + " 了（" + how + "）"));
            return 1;
        }

        // 目的地不在已加载的区块里就直接拒绝：读不到方块、也不该往没加载的地方飞
        if (!SnapUtils.isLoaded(mc, target)) {
            source.sendError(Text.literal("目的地 " + target.toShortString()
                + " 不在当前已加载的区块里（离你 " + format(distanceTo(player, target))
                + " 格），先走近一点再 snap"));
            return 0;
        }

        SnapUtils.Plan plan = SnapUtils.plan(mc, from, target);
        if (plan == null) {
            source.sendError(Text.literal("6 种走法都不通：从 " + from.toShortString() + " 到 "
                + target.toShortString() + "，沿轴走的每一格和它上面一格都得是空气"));
            return 0;
        }

        submit(plan);
        source.sendFeedback(Text.literal("已提交任务：snap 到 " + DimensionUtils.display(DimensionUtils.current())
            + " " + target.toShortString() + "（" + how + "）"));
        source.sendFeedback(Text.literal("  走法 " + plan.describeOrder() + "：" + plan.describeSegments()));
        return 1;
    }

    /** 每段一个 {@link MoveAction}（和 /pc move x|y|z 完全同一套）。 */
    private static void submit(SnapUtils.Plan plan) {
        for (SnapUtils.Segment segment : plan.segments()) {
            ActionManager.get().submit(new MoveAction(segment.axis(), segment.blocks()));
        }
    }

    private static String format(double value) {
        return String.format("%.1f", value);
    }

    private static double distanceTo(ClientPlayerEntity player, BlockPos pos) {
        return player.getEntityPos().distanceTo(Vec3d.ofCenter(pos));
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    private interface SnapExecutor {
        int run(CommandContext<FabricClientCommandSource> context);
    }

    /** {@code x y z} 三个整数（absolute）。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> coords(SnapExecutor executor) {
        return ClientCommandManager.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(executor::run)));
    }

    /** {@code dx dy dz} 三个整数（relative）。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> deltas(SnapExecutor executor) {
        return ClientCommandManager.argument("dx", IntegerArgumentType.integer())
            .then(ClientCommandManager.argument("dy", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("dz", IntegerArgumentType.integer())
                    .executes(executor::run)));
    }
}
