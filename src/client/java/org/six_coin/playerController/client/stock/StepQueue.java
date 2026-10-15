package org.six_coin.playerController.client.stock;

import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;

import java.util.List;

/**
 * 一条线性的步骤队列：一次只跑一步，跑完了自动接下一步；哪一步失败，整条就停在它那里。
 *
 * <p>队列只管进度 —— 现在是谁在跑、下一步是谁、有没有失败；业务都在步骤自己里面。
 * 步骤不负责启动别的步骤，所以「谁接谁」永远只有这一份表（队列里的那个 {@link List}）。
 */
final class StepQueue {

    private final List<Action> steps;

    /** 现在跑到第几个（0 开始；-1 = 还没开始）。 */
    private int index = -1;

    @Nullable
    private Action current;

    /** 失败的那一步（没失败就是 null）。 */
    @Nullable
    private Action failed;

    private boolean done;

    StepQueue(List<Action> steps) {
        this.steps = steps;
    }

    int total() {
        return steps.size();
    }

    /** 现在这一步的编号（1 开始，用来打「第 x/y 步」）。 */
    int number() {
        return Math.min(index + 1, steps.size());
    }

    /** 整条跑完了（全跑完 / 中途失败都算）。 */
    boolean isDone() {
        return done;
    }

    @Nullable
    Action failedStep() {
        return failed;
    }

    /**
     * 推进一步：上一个跑完了就开始下一个，正在跑的推它一 tick。
     *
     * <p>每一步结束都会 {@code cleanup()} 一次（收尾、关界面、恢复状态都在那里）。
     *
     * @return 刚刚**成功**跑完的那一步；其余情况 null
     */
    @Nullable
    Action tick() {
        if (done) return null;

        if (current != null) {
            current.update();
            if (!current.isFinished()) return null;
        } else {
            index++;
            if (index >= steps.size()) {
                done = true;
                return null;
            }
            current = steps.get(index);
            current.start();
            if (!current.isFinished()) return null;
        }

        Action finished = current;
        current = null;
        finished.cleanup();

        if (finished.failureReason() != null) {
            failed = finished;
            done = true;
            return null;
        }
        return finished;
    }
}
