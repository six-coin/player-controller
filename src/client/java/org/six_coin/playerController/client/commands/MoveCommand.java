package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.MoveAction;
import org.six_coin.playerController.client.util.ChatUtils;

/** {@code /pc move [x|y|z] [blocks]} —— 沿轴移动指定格数。 */
public final class MoveCommand {

    private MoveCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal("move");
        node.then(axis("x", Direction.Axis.X));
        node.then(axis("y", Direction.Axis.Y));
        node.then(axis("z", Direction.Axis.Z));
        return node;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> axis(String name, Direction.Axis axis) {
        return ClientCommandManager.literal(name).then(
            ClientCommandManager.argument("blocks", IntegerArgumentType.integer())
                .executes(context -> {
                    int blocks = IntegerArgumentType.getInteger(context, "blocks");
                    FabricClientCommandSource source = context.getSource();

                    if (blocks == 0) {
                        source.sendError(Text.literal("移动距离不能为 0"));
                        return 0;
                    }
                    if (source.getPlayer() == null) {
                        source.sendError(Text.literal("没有玩家"));
                        return 0;
                    }

                    ActionManager.get().submit(new MoveAction(axis, blocks));
                    ChatUtils.info("已提交任务：沿 " + name + " 轴移动 " + blocks + " 格");
                    return 1;
                }));
    }
}
