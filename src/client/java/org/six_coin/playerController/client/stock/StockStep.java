package org.six_coin.playerController.client.stock;

import org.six_coin.playerController.client.action.Action;

/**
 * 备货流水线里的一步。
 *
 * <p>每一步只管自己这一段：跑多久、怎么跑都是它自己的事。它不知道前面是谁、后面是谁，
 * 也不会去启动别的步骤 —— 谁先谁后由 {@link StockTask} 的步骤表决定，进度由 {@link StepQueue} 推。
 */
abstract class StockStep extends Action {

    /** 这一步属于哪个任务。 */
    protected final String task;

    /** 整条流水线共享的数据。 */
    protected final StockRun run;

    /** 聊天栏里怎么称呼这一步，例如「第一部分阶段3（分盒重装）」。 */
    protected final String phase;

    StockStep(String task, StockRun run, String phase) {
        this.task = task;
        this.run = run;
        this.phase = phase;
    }

    @Override
    public final String name() {
        return "备货 " + task + " " + phase;
    }

    /** 这一步在流程里的名字（不含任务名），用来打「第 x/y 步 …」这种进度 / 失败信息。 */
    String phase() {
        return phase;
    }
}
