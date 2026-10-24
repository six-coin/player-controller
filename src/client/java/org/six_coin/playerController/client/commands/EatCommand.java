package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.six_coin.playerController.client.eat.EatConfig;
import org.six_coin.playerController.client.eat.EatManager;

import static org.six_coin.playerController.client.commands.CommandStyle.runButton;

/**
 * {@code /pc eat ...} —— 自动进食的开关。
 *
 * <pre>
 * /pc eat                 看开关、状态（不带子命令就是「切换」）
 * /pc eat toggle [true|false]
 * /pc eat status
 * </pre>
 *
 * <p>开关存在 {@code config/player-controller/world_<n>/eat.json}（{@code {"enabled": true}}），
 * 逻辑见 {@link EatManager}：饱食度降到 {@value EatManager#TRIGGER_FOOD} 时切快捷栏 9 吃东西，
 * 吃完切回去。
 */
public final class EatCommand {

    private static final String ROOT = "/pc eat";

    private EatCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> builder =
            ClientCommands.literal("eat")
                .executes(EatCommand::toggle);

        builder.then(ClientCommands.literal("toggle")
            .executes(EatCommand::toggle)
            .then(ClientCommands.literal("true")
                .executes(context -> set(context, true)))
            .then(ClientCommands.literal("false")
                .executes(context -> set(context, false))));

        builder.then(ClientCommands.literal("true").executes(context -> set(context, true)));
        builder.then(ClientCommands.literal("false").executes(context -> set(context, false)));
        builder.then(ClientCommands.literal("status").executes(EatCommand::status));
        return builder;
    }

    // ------------------------------------------------------------------

    private static int toggle(CommandContext<FabricClientCommandSource> context) {
        EatConfig config = EatConfig.get();
        boolean value = config.toggle();

        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("自动进食已" + (value ? "§a开启" : "§c关闭")
            + "§r（" + EatConfig.file(Math.max(1, config.loadedWorld())) + "）"));
        if (value) {
            reportFood(source);
        }
        return 1;
    }

    private static int set(CommandContext<FabricClientCommandSource> context, boolean value) {
        EatConfig config = EatConfig.get();
        if (config.enabled() == value) {
            context.getSource().sendFeedback(Component.literal("自动进食本来就是"
                + (value ? "开" : "关") + "的"));
            return 1;
        }
        config.setEnabled(value);
        context.getSource().sendFeedback(Component.literal("自动进食已" + (value ? "§a开启" : "§c关闭")));
        if (value) reportFood(context.getSource());
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        EatConfig config = EatConfig.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7自动进食（文件："
            + EatConfig.file(Math.max(1, config.loadedWorld())) + "）："));
        source.sendFeedback(Component.literal("  开关：" + (config.enabled() ? "§a开" : "§c关"))
            .append(Component.literal("  "))
            .append(runButton(config.enabled() ? "[关闭]" : "[开启]", ROOT + " toggle",
                "点了直接切换（不用回车）")));
        source.sendFeedback(Component.literal("  状态：" + EatManager.get().state().text()));
        source.sendFeedback(Component.literal("  规则：饱食度 ≤ " + EatManager.TRIGGER_FOOD
            + "（满 20）时切到快捷栏 9 吃掉，吃完切回原来那一格"));
        source.sendFeedback(Component.literal("  优先级：比挖掘高、比攻击低（攻击要打的时候让给攻击）"));
        reportFood(source);
        return 1;
    }

    private static void reportFood(FabricClientCommandSource source) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        ItemStack food = player.getInventory().getItem(EatManager.FOOD_HOTBAR);
        source.sendFeedback(Component.literal("  现在饱食度 " + player.getFoodData().getFoodLevel()
            + "；快捷栏 9：" + (food.isEmpty() ? "§c空" : food.getHoverName().getString()
                + " x" + food.getCount())));
        if (food.isEmpty()) {
            source.sendFeedback(Component.literal("§e  快捷栏 9 是空的，开着了也吃不到东西"));
        }
    }
}
