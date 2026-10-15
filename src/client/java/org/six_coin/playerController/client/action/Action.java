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

    /**
     * 结果输出层，默认是子任务（不输出结果）。
     *
     * <p>命令层直接提交的动作会被 {@link ActionManager#submit} 改成 {@link ResultOutput#COMMAND}；
     * 被别的动作内嵌跑的子动作保持默认值，于是它算出来的结果不会打到聊天栏。
     */
    private ResultOutput output = ResultOutput.SUBTASK;

    /** 名字，用于聊天栏输出。 */
    public abstract String name();

    /** 是不是“移动类”任务（/pc move cancel 只取消这一类）。 */
    public boolean isMovement() {
        return false;
    }

    /** 这个动作的结果往哪儿输出（见 {@link ResultOutput}）。 */
    public final ResultOutput output() {
        return output;
    }

    /** 由 {@link ActionManager} 在提交时设置（命令层 = {@link ResultOutput#COMMAND}）。 */
    public final void setOutput(ResultOutput output) {
        this.output = output;
    }

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

        // 只有「命令直接要的」那个动作报成功 / 失败：被内嵌跑的子动作把原因交给调用方往上带，
        // 由最外面那一个动作报一行（不然一层套一层，同一句话要刷好几遍）。
        if (failureReason == null) {
            ChatUtils.debug("✔ 完成: " + name());
        } else if (output().isEnabled()) {
            ChatUtils.error("✘ 失败: " + name() + " —— " + failureReason);
        } else {
            ChatUtils.debug("✘ 失败: " + name() + " —— " + failureReason);
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
