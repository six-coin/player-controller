package org.six_coin.playerController.client.mine.complex;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.mine.MineFilter;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.mine.station.MinePlace;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.mine.station.MineStationPart;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.waypoint.Edge;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /pc mine b_inspect_region [task_name]} 的执行体：检查一个任务的挖掘区。
 *
 * <p>分三关，任何一关不过就失败（不写 {@code inspected}）：
 * <ol>
 *   <li><b>入口</b>：{@code (x1, y2+1, z1)}（区域正上方那个角）必须在路径点网络里
 *       —— 要么正好是个路径点，要么压在某条走路边上；而且从安全点能走过去。
 *       安全点见 {@code /pc mine station safe_point}，它本身也必须在网络里。</li>
 *   <li><b>区域里不能有路</b>：{@code x1 y1 z1 ~ x2 y2 z2} 里不能有任何路径点、
 *       也不能有任何边经过（挖掉就把路断了）。</li>
 *   <li><b>方块</b>：把区域按区块（16×16×世界高度 那种，不是子区块）切开，逐个区块扫
 *       {@code y1 ~ y2}：只要出现「不能挖」的方块就失败。能不能挖按当前的过滤器算
 *       （{@link MineFilterConfig}：黑名单模式 = 在黑名单里，白名单模式 = 不在白名单里；
 *       空气不算）。</li>
 * </ol>
 *
 * <p>区块只有加载了才读得到方块，所以第 3 关是<b>等</b>着扫的：每 tick 把还没扫的区块过一遍，
 * 加载了就读、没加载就留着，直到全部扫完（最多等 {@value #MAX_WAIT_TICKS} tick）。
 * 全部通过之后把 {@code inspected} 写成 true 存回 {@code region.json}。
 */
public class MineRegionInspectAction extends Action {

    /** 等区块加载最多等这么久（60 秒）。 */
    private static final int MAX_WAIT_TICKS = 20 * 60;

    /** 每隔这么久报一次进度（5 秒）。 */
    private static final int PROGRESS_TICKS = 20 * 5;

    /** 问题里最多列几个具体位置。 */
    private static final int MAX_REPORTED = 5;

    private final String task;
    private final MineTaskRegion region;
    /** 开跑时取一份过滤器快照：扫到一半改列表不影响这一次。 */
    private final MineFilter filter;

    /** 还没扫过的区块（key = cx << 32 | cz，无符号打包）。 */
    private final List<Long> pending = new ArrayList<>();

    private int ticks;
    private int lastProgress;
    private int chunksDone;

    public MineRegionInspectAction(String task, MineTaskRegion region) {
        this.task = task;
        this.region = region;
        this.filter = MineFilterConfig.get().filter();
    }

    @Override
    public String name() {
        return "检查挖掘区 " + task;
    }

    // ------------------------------------------------------------------
    // 开始：维度 + 路径点（这两关是同步的，很快）
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        if (!region.dimension().equals(DimensionUtils.current())) {
            fail("挖掘区在 " + DimensionUtils.display(region.dimension()) + "，你现在在 "
                + DimensionUtils.display(DimensionUtils.current()) + " —— 要站在挖掘区所在的维度里检查");
            return;
        }

        // region.json 可能是手改过的：先按 a_set_region 那套规则再验一遍，
        // 不然 x1 > x2 这种会让区块清单直接是空的，等于什么都没查就「通过」了
        List<String> invalid = MineTaskRegion.validate(region.dimension(),
            region.min().getX(), region.min().getY(), region.min().getZ(),
            region.max().getX(), region.max().getY(), region.max().getZ(),
            DimensionUtils.buildMaxY(region.dimension()));
        if (!invalid.isEmpty()) {
            ChatUtils.error("挖掘区 " + task + " 的 region.json 不合法（" + invalid.size() + " 条）：");
            for (String problem : invalid) ChatUtils.error("  · " + problem);
            fail("region.json 不合法（" + invalid.size() + " 条，见聊天栏）—— 重新 a_set_region 一次");
            return;
        }

        List<String> problems = checkPath();
        if (!problems.isEmpty()) {
            ChatUtils.error("挖掘区 " + task + " 的路径点检查没通过（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.error("  · " + problem);
            fail("路径点检查没通过（" + problems.size() + " 条，见聊天栏）");
            return;
        }

        // 第 3 关的区块清单：区域盖住的每一个区块（16×16 那种）
        for (int cx = region.min().getX() >> 4; cx <= region.max().getX() >> 4; cx++) {
            for (int cz = region.min().getZ() >> 4; cz <= region.max().getZ() >> 4; cz++) {
                pending.add(pack(cx, cz));
            }
        }

        ChatUtils.info("检查挖掘区 " + task + "：" + region.min().toShortString() + " ~ "
            + region.max().toShortString() + "（" + region.chunkCount() + " 个区块），过滤器 "
            + filter.describe());
        ChatUtils.info("路径点检查通过（入口 " + region.headPos().toShortString()
            + " 走得到安全点，区域里没有路）；开始扫 y " + region.min().getY() + " ~ "
            + region.max().getY() + " 的方块");
    }

    /** 第 1、2 关：入口在不在网络上、安全点走不走得到、区域里有没有路。 */
    private List<String> checkPath() {
        List<String> problems = new ArrayList<>();
        WaypointGraph graph = WaypointManager.get().graph();
        String dimension = region.dimension();
        BlockPos head = region.headPos();

        Set<Integer> headNodes = nodesAt(graph, dimension, head);
        if (headNodes.isEmpty()) {
            problems.add("入口 " + head.toShortString()
                + "（x1, y2+1, z1）既不是路径点，也不在任何边上 —— 不在路径点网络里");
        }

        MinePlace safe = MineStationManager.get().single(MineStationPart.SAFE_POINT);
        Set<Integer> safeNodes = Set.of();
        if (safe == null) {
            problems.add("没有设置安全点（/pc mine station safe_point set …）");
        } else {
            safeNodes = nodesAt(graph, safe.dimension(), safe.pos());
            if (safeNodes.isEmpty()) {
                problems.add("安全点 " + safe.describe() + " 不在路径点网络里");
            }
        }

        // 走的方向：安全点 -> 入口（去干活走的是这个方向）；反方向走不通不算错，只是提一句
        if (!headNodes.isEmpty() && !safeNodes.isEmpty()) {
            Set<Integer> fromSafe = graph.reachableFrom(safeNodes);
            boolean forward = headNodes.stream().anyMatch(fromSafe::contains);
            if (!forward) {
                Set<Integer> fromHead = graph.reachableFrom(headNodes);
                boolean backward = safeNodes.stream().anyMatch(fromHead::contains);
                problems.add("从安全点 " + safe.describe() + " 走不到入口 " + head.toShortString()
                    + (backward ? "（反过来从入口能走到安全点：单向边，方向反了）" : ""));
            }
        }

        // 区域里不能有路径点、也不能有边经过
        List<String> inside = new ArrayList<>();
        int waypoints = 0;
        for (Waypoint waypoint : graph.allWaypoints()) {
            if (waypoint.isSpawn()) continue;                 // 0 号出生点节点和某个路径点重合，不重复算
            if (!waypoint.dimension().equals(dimension)) continue;
            if (!region.contains(waypoint.pos())) continue;

            waypoints++;
            if (inside.size() < MAX_REPORTED) {
                inside.add("路径点 #" + waypoint.id() + " " + waypoint.coordString());
            }
        }

        int edges = 0;
        for (WaypointGraph.EdgeEntry entry : graph.allEdges()) {
            Edge edge = entry.edge();
            Waypoint from = graph.get(edge.from());
            Waypoint to = graph.get(edge.to());
            if (from == null || to == null) continue;
            if (!from.dimension().equals(dimension) || !to.dimension().equals(dimension)) continue;

            BlockPos hit = firstInside(from.pos(), to.pos());
            if (hit == null) continue;

            edges++;
            if (inside.size() < MAX_REPORTED) {
                inside.add("边 #" + entry.id() + " 上的 " + hit.toShortString());
            }
        }

        if (waypoints > 0 || edges > 0) {
            problems.add("挖掘区里有 " + waypoints + " 个路径点、" + edges + " 段边（挖掉就把路断了）："
                + String.join("、", inside)
                + (waypoints + edges > inside.size() ? " ……" : ""));
        }

        return problems;
    }

    /**
     * 这个坐标在路径点网络里对应的节点：正好是路径点就是它；压在某条走路边上就是那条边的两个端点
     * （边中间站得住，两边都能走）；都不在就是空。
     *
     * <p>只查、不建点（{@code check} 那套会顺手建点，这里不动图）。
     */
    private static Set<Integer> nodesAt(WaypointGraph graph, String dimension, BlockPos pos) {
        Waypoint at = graph.at(dimension, pos);
        if (at != null) return Set.of(at.id());

        WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, pos);
        if (edge == null) return Set.of();

        Set<Integer> nodes = new LinkedHashSet<>();
        nodes.add(edge.edge().from());
        nodes.add(edge.edge().to());
        return nodes;
    }

    /** 这条边经过的方块里，第一个落在区域里的；没有返回 null。 */
    @Nullable
    private BlockPos firstInside(BlockPos from, BlockPos to) {
        for (BlockPos pos : WaypointGraph.blocksAlong(from, to)) {
            if (region.contains(pos)) return pos;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 每 tick：扫还没扫的区块
    // ------------------------------------------------------------------

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        ticks++;

        for (int index = 0; index < pending.size(); ) {
            long key = pending.get(index);
            int cx = (int) (key >> 32);
            int cz = (int) key;

            // 没加载就先留着：下一 tick 再看
            if (!mc.level.hasChunk(cx, cz)) {
                index++;
                continue;
            }

            Hit hit = scanChunk(mc.level, cx, cz);
            if (hit != null) {
                fail("区块 [" + cx + ", " + cz + "] 的 y " + region.min().getY() + " ~ "
                    + region.max().getY() + " 里有不能挖的方块：" + hit.blockId()
                    + " @ " + hit.pos().toShortString());
                return;
            }

            pending.remove(index);
            chunksDone++;
        }

        if (pending.isEmpty()) {
            pass();
            return;
        }

        if (ticks - lastProgress >= PROGRESS_TICKS) {
            lastProgress = ticks;
            ChatUtils.info("检查挖掘区 " + task + "：已扫 " + chunksDone + " 个区块，还有 "
                + pending.size() + " 个没加载（等它们加载出来…）");
        }

        if (ticks > MAX_WAIT_TICKS) {
            fail("等了 " + (MAX_WAIT_TICKS / 20) + " 秒还有 " + pending.size()
                + " 个区块没加载，扫不了：" + sample() + "（人挪到这些区块附近再跑一次）");
        }
    }

    /** 扫一个区块在 {@code y1 ~ y2} 范围内的方块，返回第一个不能挖的；没有返回 null。 */
    @Nullable
    private Hit scanChunk(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunk(cx, cz);
        if (chunk == null) return null;

        int minSectionY = level.getMinSectionY();
        int maxSectionY = minSectionY + level.getSectionsCount() - 1;
        int fromY = Math.max(region.min().getY(), minSectionY * 16);
        int toY = Math.min(region.max().getY(), maxSectionY * 16 + 15);
        if (fromY > toY) return null;

        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionY = fromY >> 4; sectionY <= (toY >> 4); sectionY++) {
            int index = level.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sections.length) continue;

            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) continue;

            // 先看调色板：整段都没有「不能挖」的方块就直接跳过，不用一格一格读
            if (!section.maybeHas(this::forbidden)) continue;

            int baseY = sectionY * 16;
            int lo = Math.max(fromY, baseY);
            int hi = Math.min(toY, baseY + 15);

            for (int y = lo; y <= hi; y++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState state = section.getBlockState(lx, y & 15, lz);
                        if (!forbidden(state)) continue;
                        return new Hit(new BlockPos((cx << 4) + lx, y, (cz << 4) + lz),
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    }
                }
            }
        }
        return null;
    }

    /** 这个方块是不是「不能挖」：空气不算；剩下的按过滤器（黑名单 / 白名单）判。 */
    private boolean forbidden(BlockState state) {
        if (state.isAir()) return false;
        return !filter.allows(state.getBlock());
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private void pass() {
        region.setInspected(true);
        try {
            region.save(MineComplexManager.get().currentRegionFile(task));
        } catch (IOException e) {
            fail("写 region.json 失败：" + e.getMessage());
            return;
        }

        output().info("挖掘区检查通过：" + task + "（" + chunksDone + " 个区块都扫过了，"
            + region.min().toShortString() + " ~ " + region.max().toShortString() + "），inspected = true");
        finish();
    }

    /** 还没加载的那几个区块（给失败信息用）。 */
    private String sample() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < pending.size() && i < MAX_REPORTED; i++) {
            long key = pending.get(i);
            list.add("[" + (int) (key >> 32) + ", " + (int) key + "]");
        }
        if (pending.size() > list.size()) list.add("……");
        return String.join("、", list);
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** 扫到的一个「不能挖」的方块。 */
    private record Hit(BlockPos pos, String blockId) {
    }
}
