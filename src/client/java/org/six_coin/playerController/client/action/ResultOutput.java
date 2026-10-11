package org.six_coin.playerController.client.action;

import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 动作结果的输出层：夹在「命令层」和「实现层」中间。
 *
 * <p>三层的关系：
 * <ol>
 *   <li><b>命令层</b>（{@code commands/}）：解析参数、校验、然后 {@link ActionManager#submit} 一个动作；</li>
 *   <li><b>输出层</b>（就是这个类）：决定动作算出来的结果要不要打到聊天栏；</li>
 *   <li><b>实现层</b>（{@code action/}）：只管干活、算出结果，然后调 {@code output().info(...)} /
 *       {@code output().rawCopyable(...)} 交上来。</li>
 * </ol>
 *
 * <p>为什么要有这一层：同一个动作既可能被命令层直接跑（{@code /pc container get}），
 * 也可能被别的任务内嵌着跑（备货流程里到处都在取物 / 放物 / 合成）。
 * 前一种情况用户要看到结果（item_list、all_cleared 之类），后一种情况看到的应该只有备货自己的进度日志 ——
 * 以前结果是在实现层直接 {@code ChatUtils.info} 的，于是 {@code /pc stock} 会刷出一堆取物返回的 item_list。
 *
 * <p>规则：命令层直接提交的任务用 {@link #COMMAND}，被别的任务内嵌跑的子动作用 {@link #SUBTASK}。
 * {@link ActionManager#submit} 就是这条分界线（它只被命令层调用）。
 *
 * <p>注意：这里只管「命令结果」。实现层自己的**调试日志**（{@code ChatUtils.debug}）不走这一层 ——
 * 备货里那些点击 / 搬运的细节日志照旧要能看到。
 */
public final class ResultOutput {

    /** 命令层直接交上来的任务：结果照常打到聊天栏。 */
    public static final ResultOutput COMMAND = new ResultOutput(true);

    /** 被别的任务内嵌跑的子动作：结果丢掉（debug 日志不受影响）。 */
    public static final ResultOutput SUBTASK = new ResultOutput(false);

    private final boolean enabled;

    private ResultOutput(boolean enabled) {
        this.enabled = enabled;
    }

    /** 要不要真的输出。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 结果里的一句话。 */
    public void info(String message) {
        if (enabled) ChatUtils.info(message);
    }

    /** 结果里的一句话（带格式）。 */
    public void info(String format, Object... args) {
        if (enabled) ChatUtils.info(String.format(format, args));
    }

    /** 结果里的原样文本（例如 item_list 的 JSON，点一下能复制）。 */
    public void rawCopyable(String message) {
        if (enabled) ChatUtils.rawCopyable(message);
    }
}
