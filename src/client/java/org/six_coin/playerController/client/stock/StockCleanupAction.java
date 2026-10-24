package org.six_coin.playerController.client.stock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.ContainerPutAction;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationPart;
import org.six_coin.playerController.client.stock.station.StationPos;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 备货失败之后的收尾：把物品栏里**除了钻石镐以外**的东西都放回 {@code item_storage}。
 *
 * <p>为什么要做：备货中途失败的时候，物品栏里通常还留着一堆过程性的材料（这一步取多了的、
 * 上一步特意留下给下一步的、合多了的中间产物）。不收拾的话这些东西会一直占着 27 格，
 * 下一次备货一开始就「物品栏满了」，越跑越糟。
 *
 * <p>流程：
 * <ol>
 *   <li>先看看人是不是站在 {@code item_storage} 的触及范围内；不在就按路径图走到站立点；</li>
 *   <li>一个容器一个容器地倒：{@link ContainerPutAction#everythingExcept} 什么都放，
 *       但<b>跳过快捷栏第一格</b>（钻石镐）；容器满了就换下一个；</li>
 *   <li>倒完 / 实在倒不回去就结束，聊天栏说一句结果。</li>
 * </ol>
 *
 * <p>这个动作是 {@link StockTask} 失败收尾时自己提交的，不占命令层的结果输出。
 */
public final class StockCleanupAction extends Action {

    /** 走到站立点最多等多久（这个动作自己数 tick，兜底用）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 300;

    private enum Phase {
        /** 收集中、看要不要先走回站立点。 */
        PREPARE,
        /** 走回站立点。 */
        MOVE,
        /** 一个容器一个容器地倒。 */
        DUMP,
        DONE
    }

    private Phase phase = Phase.PREPARE;

    /** 要倒进去的容器（当前维度的 item_storage，按 id 升序）。 */
    private final List<StationPos> targets = new ArrayList<>();

    /** 现在跑到第几个容器。 */
    private int index;

    /** 正在跑的子动作（走路 / 倒东西）；null = 还没建。 */
    @Nullable
    private Action child;

    /** 成功倒空的容器数 / 没倒干净的容器数。 */
    private int cleared;
    private int skipped;

    private int totalTicks;

    /** 是哪个备货任务（收尾完了提示「重新执行哪个 start_collect」）。 */
    private final String task;

    public StockCleanupAction(String task) {
        this.task = task;
    }

    @Override
    public String name() {
        return "备货失败收尾（把物品栏放回 item_storage）";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        String dimension = DimensionUtils.current();
        for (StationPos pos : StationManager.get().list(StationPart.ITEM_STORAGE)) {
            if (!pos.dimension().equals(dimension)) continue;
            targets.add(pos);
        }
        if (targets.isEmpty()) {
            ChatUtils.info("没有配置物资存储地（item_storage），物品栏里的东西先留着");
            finish();
            return;
        }

        if (!hasSomethingToDump(player)) {
            ChatUtils.debug("物品栏里除了快捷栏第一格没有别的东西，不用收尾");
            finish();
            return;
        }

        // 已经够得着就直捣黄龙；够不着就先走回站立点（所有容器都在站立点的触及范围内）
        if (PlayerUtils.isWithinReach(targets.get(0).pos())) {
            phase = Phase.DUMP;
            return;
        }

        StationPos stand = StationManager.get().single(StationPart.STAND_POINT);
        if (stand == null) {
            ChatUtils.info("够不着物资存储地，又没设站立点，物品栏里的东西先留着");
            finish();
            return;
        }

        Action move = planMove(stand.pos());
        if (move == null) {
            ChatUtils.info("够不着物资存储地，又走不过去，物品栏里的东西先留着");
            finish();
            return;
        }

        ChatUtils.info("够不着物资存储地，先走回站立点 " + stand.coordString());
        child = move;
        child.start();
        phase = Phase.MOVE;
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

        switch (phase) {
            case PREPARE -> {
                // onStart 里就处理完了，走到这儿说明没什么可做的
                finish();
            }
            case MOVE -> tickMove();
            case DUMP -> tickDump();
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        // 半路被取消 / 失败：把子动作也收掉（关界面、解除界面抑制、停飞行）
        if (child != null) {
            Action unfinished = child;
            child = null;
            unfinished.cleanup();
        }

        if (failureReason() != null) return;

        // 收尾完了统一给一句「接下来怎么办」：重新跑一遍 start_collect 就行
        String dumped = (cleared == 0 && skipped == 0)
            ? "物品栏里没有要倒的东西"
            : "倒回去 " + cleared + " 个容器"
                + (skipped > 0 ? "，还有 " + skipped + " 个容器没倒干净" : "")
                + "（快捷栏第一格的钻石镐留着）";
        ChatUtils.info("备货失败收尾：" + dumped
            + "；修好问题后重新执行 /pc stock task " + task + " start_collect 即可");
    }

    // ------------------------------------------------------------------

    private void tickMove() {
        if (child == null) {
            phase = Phase.DUMP;
            return;
        }
        child.update();
        if (!child.isFinished()) return;

        Action done = child;
        child = null;
        done.cleanup();

        if (done.failureReason() != null) {
            ChatUtils.info("走回站立点失败（" + done.failureReason() + "），物品栏里的东西先留着");
            finish();
            return;
        }
        phase = Phase.DUMP;
    }

    private void tickDump() {
        if (index >= targets.size()) {
            phase = Phase.DONE;
            finish();
            return;
        }

        // 已经没有要倒的东西了（上一个容器就倒干净了）：不用再挨个开下去
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !hasSomethingToDump(player)) {
            phase = Phase.DONE;
            finish();
            return;
        }

        if (child == null) {
            StationPos pos = targets.get(index);
            ChatUtils.debug("收尾：把物品栏里除了钻石镐的东西倒进 item_storage #" + pos.id()
                + " " + pos.coordString());
            child = ContainerPutAction.everythingExcept(pos.pos(), ItemList.empty(),
                true, StockTask.PICKAXE_HOTBAR, true);
            child.start();
            return;
        }

        child.update();
        if (!child.isFinished()) return;

        Action done = child;
        child = null;
        done.cleanup();

        if (done instanceof ContainerPutAction put && put.allCleared()) {
            cleared++;
        } else {
            skipped++;
        }
        index++;
    }

    /** 物品栏里除了快捷栏第一格（钻石镐）还有没有别的东西。 */
    private static boolean hasSomethingToDump(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            if (i == StockTask.PICKAXE_HOTBAR) continue;
            if (!player.getInventory().getItem(i).isEmpty()) return true;
        }
        return false;
    }

    /** 从当前位置走到 {@code target} 的路径动作；算不出路就返回 null。 */
    @Nullable
    private static Action planMove(BlockPos target) {
        WaypointGraph graph = WaypointManager.get().graph();
        String dimension = DimensionUtils.current();

        Waypoint to = graph.at(dimension, target);
        if (to == null) {
            WaypointGraph.EdgeEntry edge = graph.edgeAt(dimension, target);
            if (edge == null) return null;
            to = WaypointManager.get().createWaypoint(dimension, target);
        }

        Waypoint from = WaypointManager.get().playerStartWaypoint();
        if (from == null) return null;
        if (from.id() == to.id()) return null;

        List<Waypoint> path = graph.shortestPath(from.id(), to.id());
        if (path == null) return null;
        return new PathMoveAction(path);
    }
}
