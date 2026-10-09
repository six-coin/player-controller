package org.six_coin.playerController.client.station;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作站检查里「不开箱子就能查」的那部分硬性要求。
 *
 * <p>{@code /pc station check} 和 {@code /pc stock task <名字> start} 用的是同一套，
 * 所以放在这里，别在两个地方各写一遍。
 *
 * <p>内容相关的（大箱子空不空、木桶里是不是空潜影盒、空闲格够不够）必须开箱才知道，
 * 那部分在 {@code StationCheckAction} 里做。
 */
public final class StationPrecheck {

    /** 潜影盒摆放处至少要这么多个。 */
    public static final int REQUIRED_PLACEMENTS = 10;

    /** 检查结果：problems 非空就是没通过；hints 只是提示。 */
    public record Result(List<String> problems, List<String> hints) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private StationPrecheck() {
    }

    public static Result run(MinecraftClient mc) {
        List<String> problems = new ArrayList<>();
        List<String> hints = new ArrayList<>();

        if (mc.world == null || mc.player == null) {
            problems.add("没有玩家或世界");
            return new Result(problems, hints);
        }

        StationManager manager = StationManager.get();
        String dimension = DimensionUtils.current();

        // 必须站在站立点上（同一个维度、同一个坐标）
        StationPos stand = manager.single(StationPart.STAND_POINT);
        if (stand == null) {
            problems.add("还没有设置站立点（站到工作站中间，/pc station stand_point set_here）");
            return new Result(problems, hints);
        }
        if (!stand.dimension().equals(dimension)) {
            problems.add("站立点在 " + stand.describe() + "，你现在在 "
                + DimensionUtils.display(dimension) + "，先过去再检查");
            return new Result(problems, hints);
        }
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null || !here.equals(stand.pos())) {
            problems.add("必须站在站立点 " + stand.coordString() + " 才能开始（你现在在 "
                + (here == null ? "?" : here.toShortString()) + "）");
            return new Result(problems, hints);
        }

        WaypointGraph graph = WaypointManager.get().graph();

        // 站立点本身必须是路径点
        Waypoint standNode = graph.at(dimension, stand.pos());
        if (standNode == null) {
            problems.add("站立点 " + stand.coordString()
                + " 本身还不是路径点（站在这里用 /pc w waypoint_add_here 加一个）");
        }

        // 所有提及的方块都要和站立点同维度、且在其触及范围内
        for (StationPart part : StationPart.values()) {
            for (StationPos pos : manager.positions(part)) {
                if (!pos.dimension().equals(stand.dimension())) {
                    problems.add(part.display() + " " + pos.describe() + " 和站立点不在一个维度");
                    continue;
                }
                if (!PlayerUtils.isWithinReachFrom(stand.pos(), pos.pos())) {
                    problems.add(part.display() + " " + pos.coordString() + " 不在站立点的触及范围内（距离 "
                        + format(PlayerUtils.eyeDistanceFrom(stand.pos(), pos.pos())) + " > "
                        + format(PlayerUtils.reach()) + "）");
                }
            }
        }

        // 任务前物品暂存处：一处、大箱子
        StationPos temp = manager.single(StationPart.ITEM_TEMP);
        if (temp == null) {
            problems.add("还没有设置任务前物品暂存处（item_temp）");
        } else {
            BlockState state = mc.world.getBlockState(temp.pos());
            if (!(state.getBlock() instanceof ChestBlock)) {
                problems.add("任务前物品暂存处 " + temp.coordString() + " 不是箱子（现在是 "
                    + ContainerTypes.idOf(state) + "）");
            } else if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
                problems.add("任务前物品暂存处 " + temp.coordString() + " 是单格箱子，必须是大箱子");
            }
        }

        // 空潜影盒提供处：一处、木桶
        StationPos provider = manager.single(StationPart.SHULKER_BOX_PROVIDER);
        if (provider == null) {
            problems.add("还没有设置空潜影盒提供处（shulker_box_provider）");
        } else if (!(mc.world.getBlockState(provider.pos()).getBlock() instanceof BarrelBlock)) {
            problems.add("空潜影盒提供处 " + provider.coordString() + " 不是木桶（现在是 "
                + ContainerTypes.idOf(mc.world.getBlockState(provider.pos())) + "）");
        }

        // 工作台
        StationPos table = manager.single(StationPart.CRAFTING_TABLE);
        if (table == null) {
            problems.add("还没有设置工作台（crafting_table）");
        } else if (!(mc.world.getBlockState(table.pos()).getBlock() instanceof CraftingTableBlock)) {
            problems.add("工作台 " + table.coordString() + " 那里不是工作台方块（现在是 "
                + ContainerTypes.idOf(mc.world.getBlockState(table.pos())) + "）");
        }

        // 切石机：设了就顺便看一眼（需求里的清单没有它，所以只提示不报错）
        StationPos cutter = manager.single(StationPart.STONECUTTER);
        if (cutter != null && !(mc.world.getBlockState(cutter.pos()).getBlock() instanceof StonecutterBlock)) {
            hints.add("切石机 " + cutter.coordString() + " 那里不是切石机方块（现在是 "
                + ContainerTypes.idOf(mc.world.getBlockState(cutter.pos())) + "）");
        }

        // 潜影盒摆放处：数量、上下方块、路径点在上面那一格
        List<StationPos> placements = manager.list(StationPart.SHULKER_BOX_PLACEMENT);
        if (placements.size() < REQUIRED_PLACEMENTS) {
            problems.add("潜影盒摆放处只有 " + placements.size() + " 个，至少要 " + REQUIRED_PLACEMENTS + " 个");
        }
        for (StationPos stationPos : placements) {
            BlockPos pos = stationPos.pos();
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isAir()) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 本身不是空气（现在是 "
                    + ContainerTypes.idOf(state) + "）");
            }
            if (!mc.world.getBlockState(pos.up()).isAir()) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 上面不是空气（那里应该是能站人的地方）");
            }
            if (mc.world.getBlockState(pos.down()).isAir()) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 下面一格是空气（潜影盒没地方放）");
            }

            if (graph.at(stationPos.dimension(), pos) != null) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 本身不该是路径点（路径点应该在它上面那一格）");
            }
            Waypoint above = graph.at(stationPos.dimension(), pos.up());
            if (above == null) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 上面那一格 "
                    + pos.up().toShortString() + " 还不是路径点");
            } else if (standNode != null && graph.shortestPath(standNode.id(), above.id()) == null) {
                problems.add("潜影盒摆放处 " + stationPos.coordString() + " 上面那一格从站立点走不到");
            }
        }

        // 物资存储地 / 最终产物地：都要有、都要是容器（容量和内容在动作里数）
        checkContainerList(mc, manager, StationPart.ITEM_STORAGE, problems);
        checkContainerList(mc, manager, StationPart.ITEM_FINAL, problems);

        return new Result(problems, hints);
    }

    private static void checkContainerList(MinecraftClient mc, StationManager manager,
                                           StationPart part, List<String> problems) {
        List<StationPos> positions = manager.list(part);
        if (positions.isEmpty()) {
            problems.add("还没有设置" + part.display() + "（" + part.id() + "）");
            return;
        }
        for (StationPos stationPos : positions) {
            BlockPos pos = stationPos.pos();
            if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
                problems.add(part.display() + " " + stationPos.coordString() + " 不是容器（现在是 "
                    + ContainerTypes.idOf(mc.world.getBlockState(pos)) + "）");
            }
        }
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }
}
