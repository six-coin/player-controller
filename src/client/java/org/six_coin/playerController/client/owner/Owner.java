package org.six_coin.playerController.client.owner;

/**
 * 「谁在用」某样同一时间只能一个人用的东西（快捷栏的选中格、服务端看到的朝向）。
 *
 * <p>优先级数字越大越强势：抢的时候优先级高的赢；优先级低的请求直接失败，
 * 调用方应当放弃这一次操作（例如这一次攻击就不打了）。
 *
 * <p>只有需要独占的人才会出现在这里；不碰快捷栏 / 朝向的功能不需要。
 */
public enum Owner {

    /** 攻击：动快捷栏、动朝向，优先级最低，谁来抢都让。 */
    ATTACK(1),

    /** mending：只动朝向（打开末影箱），比攻击强势，但抢不过备货。 */
    MENDING(2),

    /** 备货：优先级最高，跑备货的时候别人的请求都不作数。 */
    STOCK(3);

    private final int priority;

    Owner(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
