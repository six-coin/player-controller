package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.six_coin.playerController.client.feature.AccountSwitcher;

/**
 * {@code /pc account ...} —— 换离线账户 / 原地重新登录。
 *
 * <pre>
 * /pc account                    看当前账户 + 用法
 * /pc account switch_to &lt;player_id&gt;
 *                                下线 → 换成名为 player_id 的离线账户 → 重新加入刚才的世界
 * /pc account re_login           只退出到主界面再重新登录（账户不变），回到刚才的世界
 * </pre>
 */
public final class AccountCommand {

    private AccountCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("account")
            .executes(AccountCommand::show)
            .then(ClientCommands.literal("switch_to")
                .then(ClientCommands.argument("player_id", StringArgumentType.word())
                    .executes(context -> switchTo(context,
                        StringArgumentType.getString(context, "player_id")))))
            .then(ClientCommands.literal("re_login")
                .executes(AccountCommand::reLogin));
    }

    private static int show(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = Minecraft.getInstance();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7当前账户："
            + (client == null ? "拿不到客户端" : AccountSwitcher.describeCurrent(client))));
        source.sendFeedback(Component.literal("§7用法：§f/pc account switch_to <player_id>"));
        source.sendFeedback(Component.literal("§7  把当前玩家下线，换成名为 player_id 的离线账户，"
            + "然后重新加入刚才的世界（单人游戏重开同一个存档，服务器重连同一个地址）"));
        source.sendFeedback(Component.literal("§7  离线名字只能是 3~16 个字母、数字、下划线"));
        source.sendFeedback(Component.literal("§7  单人存档是按 UUID 存的，换账户 = 换 UUID：世界里通常会是另一个玩家"
            + "（出生点、空背包），除非以前就用这个名字玩过这个存档"));
        source.sendFeedback(Component.literal("§7用法：§f/pc account re_login"));
        source.sendFeedback(Component.literal("§7  不换账户，只是退出到主界面再重新登录一遍，"
            + "然后回到刚才的世界（单人游戏重开同一个存档，服务器重连同一个地址）"));
        source.sendFeedback(Component.literal("§7  换过去之后想换回正版账户要重启游戏（原来的登录令牌不会自动恢复）"));
        return 1;
    }

    private static int switchTo(CommandContext<FabricClientCommandSource> context, String playerName) {
        FabricClientCommandSource source = context.getSource();
        String error = AccountSwitcher.request(playerName);
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }
        source.sendFeedback(Component.literal("准备切换到离线账户 " + playerName + " ……"));
        return 1;
    }

    private static int reLogin(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String error = AccountSwitcher.requestRelogin();
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }
        source.sendFeedback(Component.literal("准备重新登录（账户不变）……"));
        return 1;
    }
}
