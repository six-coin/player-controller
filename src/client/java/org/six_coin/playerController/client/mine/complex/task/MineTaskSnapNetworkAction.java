package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.SnapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 「snap 到最近的路径点」—— 和 {@code /pc snap network} 完全同一套（{@link SnapUtils#findSnap}）。
 *
 * <p>什么时候用：挖矿 / 补货过程中人要移动，但当前位置**既不是路径点、也不在任何边上**
 * （比如刚从潜影盒摆放处、或矿洞深处收工），{@code PathPlanner} 会直接报
 * 「你现在所在的 X 既不是路径点，也不在任何边上」。这时候先 snap 回网络，再重新规划。
 *
 * <p>只飞过去，不记路径点（{@link MoveAction#noRecord()}）。
 */
public class MineTaskSnapNetworkAction extends Action {

    private final List<SnapUtils.Segment> segments = new ArrayList<>();
    private int index;
    @Nullable
    private MoveAction current;

    @Override
    public String name() {
        return "snap 到最近的路径点";
    }

    @Override
    public boolean isMovement() {
        return true;
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos from = PlayerUtils.currentBlockPos();
        if (mc.player == null || mc.level == null || from == null) {
            fail("玩家或世界不存在");
            return;
        }

        SnapUtils.Snap snap = SnapUtils.findSnap(mc, from);
        if (snap == null) {
            fail("附近没有能 snap 过去的路径点（1000 格内没有已加载的路径点，或者都被挡住了）");
            return;
        }

        segments.addAll(snap.plan().segments());
        if (segments.isEmpty()) {
            finish();
            return;
        }
        ChatUtils.info("不在路径点网络上：先 snap 到最近的路径点 " + snap.target().toShortString()
            + "（" + snap.plan().describeSegments() + "）");
    }

    @Override
    protected void tick() {
        if (current != null) {
            current.update();
            if (!current.isFinished()) return;

            MoveAction finished = current;
            current = null;
            finished.cleanup();
            if (finished.failureReason() != null) {
                fail("snap 到路径点的路上失败：" + finished.failureReason());
                return;
            }
        }

        if (index >= segments.size()) {
            finish();
            return;
        }
        SnapUtils.Segment segment = segments.get(index++);
        current = new MoveAction(segment.axis(), segment.blocks()).noRecord();
        current.start();
    }

    @Override
    protected void onEnd() {
        if (current != null) {
            current.cleanup();
            current = null;
        }
    }
}
