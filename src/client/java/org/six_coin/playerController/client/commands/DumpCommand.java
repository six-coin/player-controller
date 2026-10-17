package org.six_coin.playerController.client.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.six_coin.playerController.client.config.PlayerControllerConfig;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /pc dump ...} —— 把一些「清单」导成 json，方便往配置里抄。
 *
 * <pre>
 * /pc dump all_items   config/player-controller/dump/all_items.json  所有物品的 id（["minecraft:oak_log", ...]，不含空气）
 * /pc dump inventory   config/player-controller/dump/inventory.json  你物品栏 36 格（快捷栏 9 + 主背包 27）里的物品 id，去重
 * /pc dump all_tags    config/player-controller/dump/all_tags.json   minecraft 命名空间下的所有物品标签
 *                                                                    （["#minecraft:logs", ...]）
 * </pre>
 *
 * <p>三个文件都是**排好序**、**去过重**的字符串数组，直接往
 * {@code config/player-controller/materials/*.json} 里抄就行。
 * 标签导的是**物品标签**（配方和材料偏好用得到的那些），不是方块 / 流体那些注册表的标签。
 */
public final class DumpCommand {

    /** 这些清单都写到这里：{@code config/player-controller/dump/}。 */
    public static final String FOLDER_NAME = "dump";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private DumpCommand() {
    }

    public static Path dumpDir() {
        return PlayerControllerConfig.configDir().resolve(FOLDER_NAME);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("dump")
            .executes(DumpCommand::status)
            .then(ClientCommandManager.literal("all_items")
                .executes(DumpCommand::dumpAllItems))
            .then(ClientCommandManager.literal("inventory")
                .executes(DumpCommand::dumpInventory))
            .then(ClientCommandManager.literal("all_tags")
                .executes(DumpCommand::dumpAllTags));
    }

    // ------------------------------------------------------------------

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("§8[§bPC§8]§r §7导出清单（都写到 " + dumpDir() + "）："));
        source.sendFeedback(Text.literal("  §f/pc dump all_items§7 - 所有物品的 id"));
        source.sendFeedback(Text.literal("  §f/pc dump inventory§7 - 你物品栏 36 格里的物品 id（去重）"));
        source.sendFeedback(Text.literal("  §f/pc dump all_tags§7 - minecraft 命名空间下的所有物品标签"));
        return 1;
    }

    /** 所有物品的 id（不含空气）。 */
    private static int dumpAllItems(CommandContext<FabricClientCommandSource> context) {
        Set<String> ids = new LinkedHashSet<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            if (isAir(id)) continue;
            ids.add(id.toString());
        }
        return write(context.getSource(), "all_items.json", ids, "所有物品");
    }

    /** 物品栏 36 格（快捷栏 9 + 主背包 27）里的物品 id；盔甲和副手不算。 */
    private static int dumpInventory(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            source.sendError(Text.literal("没有玩家"));
            return 0;
        }

        Set<String> ids = new LinkedHashSet<>();
        List<ItemStack> main = player.getInventory().getMainStacks();
        for (ItemStack stack : main) {
            if (stack.isEmpty()) continue;
            ids.add(Registries.ITEM.getId(stack.getItem()).toString());
        }
        return write(source, "inventory.json", ids, "物品栏里的物品");
    }

    /** minecraft 命名空间下的所有物品标签（带 # 前缀）。 */
    private static int dumpAllTags(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        Set<String> ids = new LinkedHashSet<>();
        try {
            for (RegistryEntryList.Named<Item> named : Registries.ITEM.streamTags().toList()) {
                Identifier id = named.getTag().id();
                if (!"minecraft".equals(id.getNamespace())) continue;
                ids.add("#" + id);
            }
        } catch (Throwable t) {
            source.sendError(Text.literal("读标签表失败：" + t));
            return 0;
        }

        if (ids.isEmpty()) {
            source.sendError(Text.literal("一个标签都没读到（还没进世界？标签表还没加载？）"));
            return 0;
        }
        return write(source, "all_tags.json", ids, "物品标签");
    }

    // ------------------------------------------------------------------

    /** 排序 + 写文件 + 报一句。 */
    private static int write(FabricClientCommandSource source, String fileName, Set<String> ids,
                             String what) {
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);

        Path file = dumpDir().resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(sorted, writer);
            }
        } catch (IOException e) {
            source.sendError(Text.literal("写 " + file + " 失败：" + e.getMessage()));
            return 0;
        }

        source.sendFeedback(Text.literal("已导出" + what + "：" + sorted.size() + " 个 -> " + file));
        return 1;
    }

    private static boolean isAir(Identifier id) {
        return Registries.ITEM.get(id) == Items.AIR;
    }
}
