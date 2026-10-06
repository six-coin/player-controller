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

    public static boolean isSuppressed() {
        return holders > 0;
    }
}
