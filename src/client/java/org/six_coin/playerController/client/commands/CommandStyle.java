package org.six_coin.playerController.client.commands;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
    public static MutableText fillButton(String label, String command, String hover) {
        return Text.literal(label).styled(style -> style
            .withColor(Formatting.GOLD)
            .withUnderline(true)
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover)))
            .withClickEvent(new ClickEvent.SuggestCommand(command)));
    }

    /** 可点的按钮：点了直接执行（只看不改的命令才用这个）。 */
    public static MutableText runButton(String label, String command, String hover) {
        return Text.literal(label).styled(style -> style
            .withColor(Formatting.AQUA)
            .withUnderline(true)
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover)))
            .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    /** 灰色的 id，点了复制。 */
    public static MutableText gray(String text, String copy) {
        return Text.literal(text).styled(style -> style
            .withColor(Formatting.GRAY)
            .withClickEvent(new ClickEvent.CopyToClipboard(copy)));
    }
}
