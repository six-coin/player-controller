package org.six_coin.playerController.client.action;

import org.six_coin.playerController.client.util.ChatUtils;

import java.util.function.BooleanSupplier;

/**
 * 什么都不做，等某个条件成立（或者超时）。
 *
 * <p>备货里用来等「挖掉潜影盒以后自然下落、掉落物被捡起来」：这段时间里不写任何速度，
 * 让重力自己把玩家带下去。
 */
public class WaitAction extends Action {

    private final String what;
    private final BooleanSupplier condition;
    private final int maxTicks;
    private final boolean failOnTimeout;

    private int ticks;
    private boolean met;

    /**
     * @param what          日志里等的是什么
     * @param condition     条件
     * @param maxTicks      最多等多少 tick
     * @param failOnTimeout 超时算不算失败（false 就是「等不到也继续」）
     */
    public WaitAction(String what, BooleanSupplier condition, int maxTicks, boolean failOnTimeout) {
        this.what = what;
        this.condition = condition;
        this.maxTicks = maxTicks;
        this.failOnTimeout = failOnTimeout;
    }

    /** 条件有没有等到。 */
    public boolean met() {
        return met;
    }

    @Override
    public String name() {
        return "等" + what;
    }

    @Override
    protected void tick() {
        if (condition.getAsBoolean()) {
            met = true;
            ChatUtils.debug("等到了（" + what + "，用了 " + ticks + " tick）");
            finish();
            return;
        }

        ticks++;
        if (ticks >= maxTicks) {
            if (failOnTimeout) {
                fail("等" + what + "超时（" + ticks + " tick）");
            } else {
                ChatUtils.debug("等" + what + "超时（" + ticks + " tick），不等了，接着往下走");
                finish();
            }
        }
    }
}
