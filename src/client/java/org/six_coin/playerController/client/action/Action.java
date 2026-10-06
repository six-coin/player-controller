package org.six_coin.playerController.client.action;

import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 一个需要分多个 tick 完成的世界操作。
 *
 * <p>生命周期：{@link #start()} → 每个客户端 tick 一次 {@link #update()} →
 * 结束后由 {@link ActionManager} 调用一次 {@link #cleanup()}。
 * 子类只需要实现 {@link #onStart()} / {@link #tick()} / {@link #onEnd()}。
 */
public abstract class Action {

    private boolean started;
    private boolean finished;
    private String failureReason;

    /** 名字，用于聊天栏输出。 */
    public abstract String name();

    protected void onStart() {
    }

    protected abstract void tick();

    /** 无论成功失败都会执行一次，用来做收尾（例如关闭界面、恢复状态）。 */
    protected void onEnd() {
    }

    // ------------------------------------------------------------------
    // 由 ActionManager 调用
    // ------------------------------------------------------------------

    public final void start() {
        if (started) return;
        started = true;
        ChatUtils.debug("▶ 开始: " + name());
        try {
            onStart();
        } catch (Exception e) {
            fail("初始化异常: " + e);
        }
    }

    public final void update() {
        if (finished) return;
        try {
            tick();
        } catch (Exception e) {
            fail("执行异常: " + e);
        }
    }

    public final boolean isFinished() {
        return finished;
    }

    public final String failureReason() {
        return failureReason;
    }

    public final void cleanup() {
        try {
            onEnd();
        } catch (Exception e) {
            ChatUtils.error(name() + " 收尾异常: " + e);
        }
        if (failureReason == null) {
            ChatUtils.debug("✔ 完成: " + name());
        } else {
            ChatUtils.error("✘ 失败: " + name() + " —— " + failureReason);
        }
    }

    // ------------------------------------------------------------------
    // 供子类使用
    // ------------------------------------------------------------------

    /** 正常结束。 */
    protected final void finish() {
        finished = true;
    }

    /** 出错结束。 */
    protected final void fail(String reason) {
        this.failureReason = reason;
        this.finished = true;
    }
}
