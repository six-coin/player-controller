package org.six_coin.playerController.client.feature;

/**
 * 临时禁止客户端弹出容器界面。
 *
 * <p>控制器打开箱子时只需要给服务端发包，不需要真的把界面画出来。
 * 用引用计数管理，避免嵌套使用时提前放开。
 *
 * <p>计数分两种：
 *
 * <ul>
 *   <li>{@link #acquire()} / {@link #release()}：动作队列里的动作占的（开一个箱子点几下就还回来）。
 *       {@link #reset()} 兜底只清这一种 —— 动作队列空着的时候，它们肯定不该还挂着。</li>
 *   <li>{@link #hold()} / {@link #unhold()}：不是「一个动作」而是「一段时间」的占用
 *       （mending 一整个检测循环都要把末影箱界面挡住）。这种不会被动作队列空闲时的兜底清掉。</li>
 * </ul>
 */
public final class ScreenSuppressor {

    /** 动作占的。 */
    private static int holders = 0;

    /** 长期占用的（不受动作队列空闲时的兜底影响）。 */
    private static int longs = 0;

    private ScreenSuppressor() {
    }

    public static void acquire() {
        holders++;
    }

    public static void release() {
        if (holders > 0) holders--;
    }

    /** 长期占用：调用方保证之后一定会 {@link #unhold()}。 */
    public static void hold() {
        longs++;
    }

    public static void unhold() {
        if (longs > 0) longs--;
    }

    /**
     * 兜底：动作队列空着的时候，把动作占的计数清零（长期占用不动）。
     *
     * <p>正常流程每个动作的 {@code onEnd()} 都会 release，但动作要是异常退出（外围 catch 住的那种），
     * 计数就会一直挂着 —— 那之后容器界面全被挡掉，箱子永远打不开。
     *
     * @return 之前动作那边是不是还挂着（挂了就说明是上面那种残余计数，值得记一句日志）
     */
    public static boolean reset() {
        boolean wasHolding = holders > 0;
        holders = 0;
        return wasHolding;
    }

    /**
     * 彻底清零（进世界的时候用）：上次要是非正常退出（崩了 / 被强杀），长期占用也会挂着。
     *
     * @return 之前是不是还挂着
     */
    public static boolean resetAll() {
        boolean wasHolding = holders > 0 || longs > 0;
        holders = 0;
        longs = 0;
        return wasHolding;
    }

    public static boolean isSuppressed() {
        return holders > 0 || longs > 0;
    }
}
