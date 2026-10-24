package org.six_coin.playerController.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
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
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;

        MutableComponent text = Component.literal(message).setStyle(Style.EMPTY
            .withClickEvent(new ClickEvent.CopyToClipboard(message))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击复制这一行"))));
        mc.gui.hud.getChat().addClientSystemMessage(text);
    }

    private static void send(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;
        mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
    }
}
