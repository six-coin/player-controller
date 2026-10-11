package org.six_coin.playerController.client.feature;

/**
 * 临时禁止客户端弹出容器界面。
 *
 * <p>控制器打开箱子时只需要给服务端发包，不需要真的把界面画出来。
 * 用引用计数管理，避免嵌套使用时提前放开。
 */
public final class ScreenSuppressor {

    private static int holders = 0;

    private ScreenSuppressor() {
    }

    public static void acquire() {
        holders++;
    }

    public static void release() {
        if (holders > 0) holders--;
    }

    /**
     * 强制清零。
     *
     * <p>进世界的时候调一次：正常流程每个动作的 {@code onEnd()} 都会 release，但要是上次
     * 备货中途崩了 / 被强杀，计数就会一直挂着 —— 那之后所有容器界面都会被挡掉，永远打不开箱子。
     *
     * @return 之前是不是还挂着（挂了就说明是上面那种残余计数，值得记一句日志）
     */
    public static boolean reset() {
        boolean wasHolding = holders > 0;
        holders = 0;
        return wasHolding;
    }

    public static boolean isSuppressed() {
        return holders > 0;
    }
}
