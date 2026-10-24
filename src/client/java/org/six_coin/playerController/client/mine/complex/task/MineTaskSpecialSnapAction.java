package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 挖矿任务专用的 snap：沿轴三段直飞，<b>不检查「目的地加载了没有」，也不检查路通不通</b>。
 *
 * <p>为什么：整个挖掘区上面那一层（{@code y2+1 ~ y2+16}）被 {@code b_inspect_region} 保证全是空气，
 * 而且区块起点都在 {@code y2+1} 这一层上，所以「挖掘区起始点 → 区块起始点」「区块起始点 →
 * 子区块的站立点」这两类移动一定飞得过去。区域可能大到几百格，终点常常不在客户端的加载范围内
 * —— 用普通 snap 会直接被「不在已加载区块里」挡下来，那是错的。
 *
 * <p>走法：先 x、再 z（都在当前高度上横着飞），最后 y。横着那两段飞的是区域顶上那层空气，
 * 竖着那段是沿着已经挖空的子区块上下。
 */
public class MineTaskSpecialSnapAction extends Action {

    private final BlockPos target;

    /** 每一段：轴 + 格数（0 的段不进来）。 */
    private final List<Direction.Axis> axes = new ArrayList<>();
    private final List<Integer> deltas = new ArrayList<>();

    private int index;
    @Nullable
    private MoveAction current;

    public MineTaskSpecialSnapAction(BlockPos target) {
        this.target = target.immutable();
    }

    @Override
    public String name() {
        return "snap 到 " + target.toShortString() + "（不查可达性）";
    }

    @Override
    public boolean isMovement() {
        return true;
    }

    @Override
    protected void onStart() {
        BlockPos from = PlayerUtils.currentBlockPos();
        if (from == null) {
            fail("拿不到玩家位置");
            return;
        }
        if (from.equals(target)) {
            finish();
            return;
        }

        add(Direction.Axis.X, target.getX() - from.getX());
        add(Direction.Axis.Z, target.getZ() - from.getZ());
        add(Direction.Axis.Y, target.getY() - from.getY());

        if (axes.isEmpty()) {
            finish();
            return;
        }
        ChatUtils.debug("特殊 snap：%s → %s（%d 段，不检查可达性）",
            from.toShortString(), target.toShortString(), axes.size());
    }

    private void add(Direction.Axis axis, int delta) {
        if (delta == 0) return;
        axes.add(axis);
        deltas.add(delta);
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

        if (index >= axes.size()) {
            finish();
            return;
        }
        current = new MoveAction(axes.get(index), deltas.get(index)).noRecord();
        index++;
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
