package org.six_coin.playerController.client.commands;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * 聊天栏里那些可点的按钮。
 *
 * <p>两种：{@link #fillButton} 点了把命令**填进**聊天栏（改配置的都用这个，回车才真的生效），
 * {@link #runButton} 点了**直接执行**（只看不改的命令才用它）。
 */
public final class CommandStyle {

    private CommandStyle() {
    }

    /** 可点的按钮：点了把这个命令填进聊天栏（不直接执行）。 */
    public static MutableComponent fillButton(String label, String command, String hover) {
        return Component.literal(label).withStyle(style -> style
            .withColor(ChatFormatting.GOLD)
            .withUnderlined(true)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))
            .withClickEvent(new ClickEvent.SuggestCommand(command)));
    }

    /** 可点的按钮：点了直接执行（只看不改的命令才用这个）。 */
    public static MutableComponent runButton(String label, String command, String hover) {
        return Component.literal(label).withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))
            .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    /** 灰色的 id，点了复制。 */
    public static MutableComponent gray(String text, String copy) {
        return Component.literal(text).withStyle(style -> style
            .withColor(ChatFormatting.GRAY)
            .withClickEvent(new ClickEvent.CopyToClipboard(copy)));
    }
}
