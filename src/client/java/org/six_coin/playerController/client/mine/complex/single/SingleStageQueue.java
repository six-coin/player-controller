package org.six_coin.playerController.client.mine.complex.single;

import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一个动作内部的步骤队列：同步步骤（{@code Runnable}）和子动作（{@link Action}）混排，
 * 一次跑一个，跑完自动接下一个；哪一步失败，整条就停在它那里。
 *
 * <p>为什么不用 {@code stock} 包里的 {@code StepQueue}：那个是包内可见的，而且不带「跑完之后做点什么」的回调。
 * 这里多一个 {@code after}（拿到刚跑完的子动作，读它的结果），阶段里要读「这个盒子满没满」就靠它。
 *
 * <p>同步步骤里可以继续 {@link #sync} / {@link #child} —— 后加进去的会**排在当前位置之前**，
 * 也就是「插到队首」；这是循环（一层一层往下挖）能写成一条链的关键。
 */
final class SingleStageQueue {

    /** 一个步骤：要么是同步的 {@code body}，要么是产出一个子动作的 {@code factory}。 */
    private record Job(String label, @Nullable Supplier<Action> factory,
                       @Nullable Consumer<Action> after, @Nullable Runnable body) {

        static Job sync(String label, Runnable body) {
            return new Job(label, null, null, body);
        }

        static Job child(String label, Supplier<Action> factory, @Nullable Consumer<Action> after) {
            return new Job(label, factory, after, null);
        }
    }

    private final Deque<Job> queue = new ArrayDeque<>();
    private final List<Job> pending = new ArrayList<>();

    private boolean executing;
    private boolean done;
    @Nullable
    private Action current;
    @Nullable
    private Consumer<Action> afterCurrent;
    private String currentLabel = "";
    @Nullable
    private Action failed;

    // ------------------------------------------------------------------

    void sync(String label, Runnable body) {
        push(Job.sync(label, body));
    }

    void child(String label, Supplier<Action> factory) {
        push(Job.child(label, factory, null));
    }

    void child(String label, Supplier<Action> factory, Consumer<Action> after) {
        push(Job.child(label, factory, after));
    }

    private void push(Job job) {
        if (executing) {
            pending.add(job);
        } else {
            queue.addLast(job);
        }
    }

    @Nullable
    Action failed() {
        return failed;
    }

    /** 直接把这整条扔掉（同步步骤里发现没法继续的时候用）。 */
    void cancel() {
        queue.clear();
        pending.clear();
        done = true;
    }

    /** 失败在哪一步（给报错用）。 */
    String failedLabel() {
        return currentLabel;
    }

    /**
     * 推进一步。
     *
     * @return {@code true} = 整条跑完了（成功或者失败都算）；失败的话 {@link #failed()} 有值
     */
    boolean tick() {
        if (done) return true;

        if (current != null) {
            current.update();
            if (!current.isFinished()) return false;

            Action finished = current;
            Consumer<Action> callback = afterCurrent;
            current = null;
            afterCurrent = null;
            finished.cleanup();

            if (finished.failureReason() != null) {
                failed = finished;
                done = true;
                return true;
            }
            if (callback != null) {
                runWithInsertion(() -> callback.accept(finished));
            }
            if (done) return true;
            if (current != null) return false;
        }

        while (!done) {
            Job job = queue.pollFirst();
            if (job == null) {
                done = true;
                return true;
            }
            currentLabel = job.label();

            if (job.body() != null) {
                runWithInsertion(job.body());
                continue;
            }

            Action action = job.factory() == null ? null : job.factory().get();
            if (action == null) {
                done = true;
                return true;
            }
            ChatUtils.debug("单段挖掘步骤：" + job.label());
            current = action;
            afterCurrent = job.after();
            action.start();
            return false;
        }
        return true;
    }

    private void runWithInsertion(Runnable body) {
        executing = true;
        pending.clear();
        try {
            body.run();
        } finally {
            executing = false;
            for (int i = pending.size() - 1; i >= 0; i--) {
                queue.addFirst(pending.get(i));
            }
            pending.clear();
        }
    }
}
