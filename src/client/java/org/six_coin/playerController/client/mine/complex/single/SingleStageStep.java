package org.six_coin.playerController.client.mine.complex.single;

import org.six_coin.playerController.client.action.Action;

/** {@code /pc mine debug_chunk_miner} 里的一步：共享 {@link SingleStageCtx}，名字统一带前缀。 */
abstract class SingleStageStep extends Action {

    protected final SingleStageCtx ctx;

    protected SingleStageStep(SingleStageCtx ctx) {
        this.ctx = ctx;
    }

    @Override
    public final String name() {
        return "单段挖掘：" + phase();
    }

    /** 这一步在干什么（聊天栏 / 日志里用）。 */
    protected abstract String phase();
}
