package org.six_coin.playerController.client.mine.complex.task;

import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.mending.MendingManager;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 在补货流程里跑一趟 <b>mending</b>（{@code /pc mend} 那一套）。
 *
 * <p>mending 是个自己按 tick 跑的 manager（不是 {@link Action}），平时它把移动 / 换物品的动作
 * 交给 {@code ActionManager}；现在外层有个补货任务占着，交进去只会排队排到补货结束之后。
 * 所以这里给它设一个「出口」（{@link MendingManager#setSink}）：它要跑的动作落到
 * {@link #pending} 上，由本动作当子动作驱动；本动作只负责等它跑完（{@link MendingManager#stage()} 回到 IDLE）。
 */
public class MineTaskMendAction extends Action {

    /** 一趟 mending 最多这么久（30 分钟）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 60 * 30;

    @Nullable
    private Action pending;
    private int totalTicks;

    @Override
    public String name() {
        return "跑一趟 mending";
    }

    @Override
    protected void onStart() {
        MendingManager manager = MendingManager.get();
        manager.setSink(action -> pending = action);

        String error = manager.start(true);
        if (error != null) {
            manager.setSink(null);
            fail("开不了 mending：" + error);
            return;
        }
        ChatUtils.info("补货：开始跑 mending（工具耐久不满，去站立点修）");
    }

    @Override
    protected void tick() {
        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("mending 超时（" + (MAX_TOTAL_TICKS / 20 / 60) + " 分钟）");
            return;
        }

        if (pending != null) {
            pending.update();
            if (!pending.isFinished()) return;

            Action done = pending;
            pending = null;
            done.cleanup();
            // 失败原因 mending 自己会看到（它每 tick 检查 isFinished / failureReason），不用在这里处理
            return;
        }

        MendingManager manager = MendingManager.get();
        if (manager.isRunning()) return;

        manager.setSink(null);
        if (manager.lastOk()) {
            ChatUtils.info("补货：mending 跑完了（" + manager.lastResult() + "）");
            finish();
        } else {
            fail("mending 中止：" + manager.lastResult());
        }
    }

    @Override
    protected void onEnd() {
        if (pending != null) {
            pending.cleanup();
            pending = null;
        }
        MendingManager manager = MendingManager.get();
        manager.setSink(null);
        if (manager.isRunning() && failureReason() != null) {
            manager.fail("外层补货任务中止");
        }
    }
}
