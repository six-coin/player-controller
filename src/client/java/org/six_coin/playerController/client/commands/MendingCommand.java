package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import org.six_coin.playerController.client.attack.AttackConfig;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.mending.MendingConfig;
import org.six_coin.playerController.client.mending.MendingManager;
import org.six_coin.playerController.client.mending.MendingPos;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import static org.six_coin.playerController.client.commands.CommandStyle.fillButton;
import static org.six_coin.playerController.client.commands.CommandStyle.runButton;

/**
 * {@code /pc mend ...} —— 靠刷经验把带经验修补的东西修满。
 *
 * <pre>
 * /pc mend                                       看在跑没跑、站立点 / 末影箱、配置
 * /pc mend start                                 开始（先走去站立点，再开攻击，然后一直检测）
 *
 * /pc mend station                               两个位置
 * /pc mend station stand_point set_here          站立点设在脚下
 * /pc mend station stand_point set &lt;x&gt; &lt;y&gt; &lt;z&gt; [dim]
 * /pc mend station ender_chest set_target        末影箱（准星指着的方块）
 * /pc mend station ender_chest set &lt;x&gt; &lt;y&gt; &lt;z&gt; [dim]
 * /pc mend station ender_chest del               不打开末影箱了
 * </pre>
 *
 * <p>坐标都带维度：set / set_here / set_target 用当前维度，也可以补一个 [dim] 直接写别的维度。
 * x y z 都支持一次 Tab 补齐成准星指着的方块。
 *
 * <p>{@code start} 要求快捷栏第一位是一把带经验修补、剩余耐久大于 100 的剑（和攻击挑武器的门槛一样）；
 * 开始以后手上就切成第一位。第一位和盔甲一样不挪地方。
 *
 * <p>改配置的按钮都是「填命令」（点了只填进聊天栏，回车才生效）；{@code [开始]} 是直接执行的。
 */
public final class MendingCommand {

    private static final String ROOT = "/pc mend ";

    private MendingCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("mend")
            .executes(MendingCommand::status)
            .then(ClientCommands.literal("start")
                .executes(MendingCommand::start))
            .then(station());
    }

    // ------------------------------------------------------------------
    // 总览
    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MendingConfig config = MendingConfig.get();
        MendingManager manager = MendingManager.get();
        AttackConfig attack = AttackConfig.get();

        source.sendFeedback(Component.literal("§8[§bPC§8]§r §7mending（文件：" + config.currentFile() + "）："));

        MutableComponent state = Component.literal("  状态：");
        if (manager.stage() == MendingManager.Stage.IDLE) {
            state.append(Component.literal("§7没在跑"));
        } else {
            state.append(Component.literal("§a" + manager.stage().text()));
        }
        state.append(Component.literal("§8（上次：" + manager.lastResult() + "） "));
        if (manager.stage() == MendingManager.Stage.IDLE) {
            state.append(runButton("[开始]", ROOT + "start", "直接执行 /pc mend start"));
        }
        source.sendFeedback(state);

        sendStandPoint(source);
        sendEnderChest(source);
        sendFirstSlot(source);

        String tempSlot = manager.tempSlotText();
        if (tempSlot != null) {
            source.sendFeedback(Component.literal("  临时物品格：" + tempSlot + "§8（副手里那件是从这儿换过去的）"));
        }

        source.sendFeedback(Component.literal("  attack：profile=" + attack.profileName()
            + "，" + (attack.enable() ? "§a开" : "§c关") + "§8（跑起来会切到 "
            + MendingManager.PROFILE + " 并打开，结束后还原）"));

        int orbs = manager.orbCount();
        source.sendFeedback(Component.literal("  检测：每 " + PlayerControllerConfig.getMendDetectIntervalTicks()
            + " tick 一次；" + PlayerControllerConfig.getMendMaxWaitExpSeconds() + " 秒没有任何东西被修好就退出"));
        source.sendFeedback(Component.literal("  经验球：站立点周围 " + MendingManager.ORB_RADIUS + " 格的球里超过 "
            + MendingManager.ORB_LIMIT + " 个就先关掉攻击"
            + (orbs < 0 ? "" : "（现在 " + orbs + " 个）")));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "start§7 - 开始"));
        source.sendFeedback(Component.literal("§7  §f" + ROOT + "station§7 - 站立点 / 末影箱"));
        return 1;
    }

    private static int start(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String error = MendingManager.get().start();
        if (error != null) {
            source.sendError(Component.literal(error));
            return 0;
        }
        return 1;
    }

    private static void sendStandPoint(FabricClientCommandSource source) {
        MendingPos stand = MendingConfig.get().standPoint();
        MutableComponent line = Component.literal("  站立点：");
        line.append(stand == null ? Component.literal("§c未设置") : Component.literal(stand.describe()));
        line.append(Component.literal("  "));
        line.append(fillButton("[设在这儿]", ROOT + "station stand_point set_here",
            "填命令：把站立点设在脚下的方块"));
        source.sendFeedback(line);
    }

    private static void sendEnderChest(FabricClientCommandSource source) {
        MendingPos chest = MendingConfig.get().enderChest();
        MutableComponent line = Component.literal("  末影箱：");
        line.append(chest == null ? Component.literal("§7没设（不打开末影箱）") : Component.literal(chest.describe()));
        line.append(Component.literal("  "));
        line.append(fillButton("[指向的方块]", ROOT + "station ender_chest set_target",
            "填命令：把末影箱设成准星指着的方块"));
        if (chest != null) {
            line.append(Component.literal(" "));
            line.append(fillButton("[删掉]", ROOT + "station ender_chest del", "填命令：删掉末影箱"));
        }
        source.sendFeedback(line);
    }

    /** 快捷栏第一位那把剑合不合用（start 的硬性条件之一）。 */
    private static void sendFirstSlot(FabricClientCommandSource source) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        ItemStack sword = player.getInventory().getItem(MendingManager.FIRST_SLOT);
        String problem = MendingManager.firstSlotProblem(player);

        MutableComponent line = Component.literal("  第一位（不挪）：");
        line.append(sword.isEmpty()
            ? Component.literal("§c空的")
            : Component.literal(InventoryUtils.describe(sword)));
        line.append(Component.literal("  "));
        if (problem == null) {
            line.append(Component.literal("§a可以打（带经验修补）"));
        } else {
            line.append(Component.literal("§c" + problem));
        }
        source.sendFeedback(line);
    }

    // ------------------------------------------------------------------
    // station
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<FabricClientCommandSource> station() {
        return ClientCommands.literal("station")
            .executes(context -> {
                sendStandPoint(context.getSource());
                sendEnderChest(context.getSource());
                return 1;
            })
            .then(ClientCommands.literal("stand_point")
                .executes(context -> {
                    sendStandPoint(context.getSource());
                    return 1;
                })
                .then(ClientCommands.literal("set_here")
                    .executes(MendingCommand::setStandHere))
                .then(ClientCommands.literal("set")
                    .then(coords(MendingCommand::setStandCoords))))
            .then(ClientCommands.literal("ender_chest")
                .executes(context -> {
                    sendEnderChest(context.getSource());
                    return 1;
                })
                .then(ClientCommands.literal("set_target")
                    .executes(MendingCommand::setChestTarget))
                .then(ClientCommands.literal("set")
                    .then(coords(MendingCommand::setChestCoords)))
                .then(ClientCommands.literal("del")
                    .executes(MendingCommand::delChest)));
    }

    private static int setStandHere(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            source.sendError(Component.literal("没有玩家"));
            return 0;
        }
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) {
            source.sendError(Component.literal("拿不到玩家位置"));
            return 0;
        }
        return setStand(source, DimensionUtils.current(), here);
    }

    private static int setStandCoords(CommandContext<FabricClientCommandSource> context) {
        return setStand(context.getSource(), readDimension(context), readPos(context));
    }

    private static int setStand(FabricClientCommandSource source, String dimension, BlockPos pos) {
        MendingConfig.get().setStandPoint(dimension, pos);
        source.sendFeedback(Component.literal("已设置站立点 = "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()));
        hintChestReach(source);
        return 1;
    }

    private static int setChestTarget(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        BlockPos pos = PlayerUtils.lookedAtBlock();
        if (pos == null) {
            source.sendError(Component.literal("你没有看向任何方块"));
            return 0;
        }
        return setChest(source, DimensionUtils.current(), pos);
    }

    private static int setChestCoords(CommandContext<FabricClientCommandSource> context) {
        return setChest(context.getSource(), readDimension(context), readPos(context));
    }

    private static int setChest(FabricClientCommandSource source, String dimension, BlockPos pos) {
        MendingConfig.get().setEnderChest(dimension, pos);
        source.sendFeedback(Component.literal("已设置末影箱 = "
            + DimensionUtils.display(dimension) + " " + pos.toShortString()));
        hintBlockType(source, pos, dimension);
        hintChestReach(source);
        return 1;
    }

    private static int delChest(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (MendingConfig.get().enderChest() == null) {
            source.sendFeedback(Component.literal("本来就没设末影箱"));
            return 1;
        }
        MendingConfig.get().clearEnderChest();
        source.sendFeedback(Component.literal("已删掉末影箱（之后只修物品栏里的东西）"));
        return 1;
    }

    // ------------------------------------------------------------------
    // 提示 / 小工具
    // ------------------------------------------------------------------

    /** 站立点和末影箱得同维度、而且站在站立点上够得着（start 的时候会硬查）。 */
    private static void hintChestReach(FabricClientCommandSource source) {
        MendingPos stand = MendingConfig.get().standPoint();
        MendingPos chest = MendingConfig.get().enderChest();
        if (stand == null || chest == null) return;

        if (!stand.dimension().equals(chest.dimension())) {
            source.sendFeedback(Component.literal("§e提示：末影箱和站立点不在一个维度，start 会拒绝执行"));
            return;
        }
        if (!PlayerUtils.isWithinReachFrom(stand.pos(), chest.pos())) {
            source.sendFeedback(Component.literal("§e提示：站在站立点上够不着末影箱（距离 "
                + format(PlayerUtils.eyeDistanceFrom(stand.pos(), chest.pos()))
                + " > " + format(PlayerUtils.reach()) + "），start 会拒绝执行"));
        }
    }

    /** 这里是不是真的末影箱（硬性检查在 start 里）。 */
    private static void hintBlockType(FabricClientCommandSource source, BlockPos pos, String dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !DimensionUtils.current().equals(dimension)) return;

        if (!(mc.level.getBlockState(pos).getBlock() instanceof EnderChestBlock)) {
            source.sendFeedback(Component.literal("§e提示：这里现在是 "
                + ContainerTypes.idOf(mc.level.getBlockState(pos)) + "，start 会要求是末影箱"));
        }
    }

    /** x y z 三个参数（补全成准星指着的方块），z 上还挂一个可选的 [dim]。 */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> coords(
            Command<FabricClientCommandSource> command) {
        return ClientCommands.argument("x", IntegerArgumentType.integer())
            .suggests(LookSuggestions::x)
            .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                .suggests(LookSuggestions::y)
                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                    .suggests(LookSuggestions::z)
                    .executes(command)
                    .then(ClientCommands.argument("dim", StringArgumentType.greedyString())
                        .suggests(DimensionSuggestions::suggest)
                        .executes(command))));
    }

    private static BlockPos readPos(CommandContext<FabricClientCommandSource> context) {
        return new BlockPos(
            IntegerArgumentType.getInteger(context, "x"),
            IntegerArgumentType.getInteger(context, "y"),
            IntegerArgumentType.getInteger(context, "z"));
    }

    /** 可选 [dim]：没写就是当前维度。 */
    private static String readDimension(CommandContext<FabricClientCommandSource> context) {
        try {
            String dim = StringArgumentType.getString(context, "dim");
            return dim == null || dim.isBlank() ? DimensionUtils.current() : dim.trim();
        } catch (IllegalArgumentException e) {
            return DimensionUtils.current();
        }
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }
}
