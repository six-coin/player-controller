package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.SnapUtils;

import java.util.List;

/**
 * 内部用的 snap：和 {@code /pc snap absolute} 完全同一套走法（6 种轴顺序，取第一种通的），
 * 一段一个 {@link MoveAction}。
 *
 * <p>不算任务失败之外的任何东西：路不通就直接报错停下（这个功能要求全程飞得通，
 * 走不通说明挖出来的空间不对，继续硬走只会卡住）。
 */
class SingleStageSnapAction extends SingleStageStep {

    private final BlockPos target;
    private List<SnapUtils.Segment> segments = List.of();
    private int index;
    @Nullable
    private MoveAction current;

    SingleStageSnapAction(SingleStageCtx ctx, BlockPos target) {
        super(ctx);
        this.target = target.immutable();
    }

    @Override
    protected String phase() {
        return "snap 到 " + target.toShortString();
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos from = PlayerUtils.currentBlockPos();
        if (from == null || mc.level == null) {
            fail("拿不到玩家位置");
            return;
        }
        if (from.equals(target)) {
            finish();
            return;
        }
        if (!SnapUtils.isLoaded(mc, target)) {
            fail("目的地 " + target.toShortString() + " 不在已加载的区块里");
            return;
        }

        SnapUtils.Plan plan = SnapUtils.plan(mc, from, target);
        if (plan == null) {
            fail("从 " + from.toShortString() + " 到 " + target.toShortString()
                + " 没有沿轴走通的路（经过的每一格和它上面一格都得是空气）");
            return;
        }
        segments = plan.segments();
        if (segments.isEmpty()) {
            finish();
        }
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
                fail("snap 到 " + target.toShortString() + " 的路上失败：" + finished.failureReason());
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
