package org.six_coin.playerController.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.six_coin.playerController.client.config.PlayerControllerConfig;

/** 统一往聊天栏输出信息。 */
public final class ChatUtils {

    private static final String PREFIX = "§8[§bPC§8]§r ";

    private ChatUtils() {
    }

    /** 普通信息（始终输出）。 */
    public static void info(String message) {
        send(PREFIX + message);
    }

    /** 调试信息（受配置里的 debug 开关控制）。 */
    public static void debug(String message) {
        if (!PlayerControllerConfig.isDebug()) return;
        send(PREFIX + "§7" + message);
    }

    /** 带格式的调试信息。 */
    public static void debug(String format, Object... args) {
        if (!PlayerControllerConfig.isDebug()) return;
        debug(String.format(format, args));
    }

    /** 错误信息（始终输出）。 */
    public static void error(String message) {
        send(PREFIX + "§c" + message);
    }

    /**
     * 原样输出一行：不加前缀、不加颜色，文字就是传进来的这段（例如 item_list 的 JSON 文本）。
     *
     * <p>点击可以复制这一行（文字本身不变），方便把结果再粘回命令里。
     */
    public static void rawCopyable(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;

        MutableText text = Text.literal(message).setStyle(Style.EMPTY
            .withClickEvent(new ClickEvent.CopyToClipboard(message))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击复制这一行"))));
        mc.inGameHud.getChatHud().addMessage(text);
    }

    private static void send(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal(message));
    }
}
