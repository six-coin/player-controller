package org.six_coin.playerController.client.action;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code /pc station check} 的执行体（分多 tick）：把工作站里那几个容器挨个打开数一遍，
 * 验证内容相关的硬性要求，全过了就把报告写到 {@code world_<n>/station/check.json}。
 *
 * <p>同步能查的（方块类型、空气、路径点、触及范围、数量）都在命令里先查过了，
 * 这里只管必须要开箱才知道的东西：
 * <ul>
 *   <li>任务前物品暂存处：必须全空；</li>
 *   <li>空潜影盒提供处：必须整桶都是空潜影盒；</li>
 *   <li>物资存储地 / 最终产物地：空闲格要够（各 9 个大箱子 = 486 格），顺手把里面的东西汇总。</li>
 * </ul>
 *
 * <p>大箱子两半只开一次（配置里两半都写了也只算一个容器）。
 */
public class StationCheckAction extends Action {

    /** 打开界面之后等几 tick 再读，保证槽位数据同步完了。 */
    private static final int SETTLE_TICKS = 2;

    /** 等容器界面打开最久。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;

    /** 整个任务最久（20 tick = 1 秒）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 600;

    /** 存储区 / 产物地至少要留出的空闲格：9 个大箱子。 */
    public static final int REQUIRED_FREE_SLOTS = 9 * 54;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** 一个要开的容器。 */
    private record Target(StationPart part, BlockPos pos) {
    }

    /** 开完一个容器数出来的东西。 */
    private record Survey(BlockPos pos, Map<String, Integer> items, int totalSlots, int emptySlots,
                          boolean allEmpty, boolean allEmptyShulkerBoxes) {
    }

    private final List<Target> targets = new ArrayList<>();
    private final Map<StationPart, List<Survey>> surveys = new EnumMap<>(StationPart.class);
    private final List<String> problems = new ArrayList<>();

    private int index;
    private ScreenHandler handler;
    private int openWaitTicks;
    private int settleTicks;
    private int totalTicks;

    @Override
    public String name() {
        return "工作站检查";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        ScreenSuppressor.acquire();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            fail("没有世界");
            return;
        }

        collectTargets(mc.world, StationPart.ITEM_TEMP);
        collectTargets(mc.world, StationPart.SHULKER_BOX_PROVIDER);
        collectTargets(mc.world, StationPart.ITEM_STORAGE);
        collectTargets(mc.world, StationPart.ITEM_FINAL);

        ChatUtils.debug("工作站检查：要开 " + targets.size() + " 个容器（大箱子两半只算一个），"
            + "不显示界面");
        if (targets.isEmpty()) {
            fail("没有要检查的容器");
            return;
        }
        openNext();
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

        Target target = targets.get(index);

        // 阶段一：等容器界面打开
        if (handler == null) {
            openWaitTicks++;
            ScreenHandler current = player.currentScreenHandler;
            if (current != null && current != player.playerScreenHandler
                && !ContainerCacheTracker.isCreativeInventory(mc)) {
                handler = current;
                settleTicks = 0;
                return;
            }
            if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
                addProblem(target.part().display() + " " + target.pos().toShortString()
                    + " 打不开（等容器界面超时）");
                advance();
            }
            return;
        }

        // 界面被别人关掉了
        if (player.currentScreenHandler != handler) {
            addProblem(target.part().display() + " " + target.pos().toShortString()
                + " 打开后被关掉了，没数到");
            handler = null;
            advance();
            return;
        }

        // 阶段二：等槽位同步完
        if (settleTicks < SETTLE_TICKS) {
            settleTicks++;
            return;
        }

        // 阶段三：数一遍
        survey(target);
        closeScreen();
        handler = null;
        advance();
    }

    @Override
    protected void onEnd() {
        closeScreen();
        ScreenSuppressor.release();

        if (failureReason() != null) {
            ChatUtils.error("工作站检查中断：" + failureReason());
            for (String problem : problems) ChatUtils.error("  · " + problem);
            return;
        }

        // 内容相关的硬性要求
        checkItemTemp();
        checkProvider();

        int freeStorage = freeSlots(StationPart.ITEM_STORAGE);
        int freeFinal = freeSlots(StationPart.ITEM_FINAL);
        if (freeStorage < REQUIRED_FREE_SLOTS) {
            addProblem("物资存储地只剩 " + freeStorage + " 格空位，至少要 "
                + REQUIRED_FREE_SLOTS + " 格（9 个大箱子）");
        }
        if (freeFinal < REQUIRED_FREE_SLOTS) {
            addProblem("最终产物地只剩 " + freeFinal + " 格空位，至少要 "
                + REQUIRED_FREE_SLOTS + " 格（9 个大箱子）");
        }

        if (!problems.isEmpty()) {
            ChatUtils.error("工作站检查没通过（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.error("  · " + problem);
            return;
        }

        writeReport(freeStorage, freeFinal);
    }

    // ------------------------------------------------------------------
    // 收集要开的容器
    // ------------------------------------------------------------------

    private void collectTargets(World world, StationPart part) {
        String dimension = DimensionUtils.current();
        Set<BlockPos> seen = new HashSet<>();

        for (StationPos stationPos : StationManager.get().positions(part)) {
            if (!stationPos.dimension().equals(dimension)) {
                ChatUtils.debug("工作站检查：跳过 " + part.display() + " " + stationPos.describe()
                    + "（不在当前维度）");
                continue;
            }

            BlockPos configured = stationPos.pos();
            BlockPos other = ContainerTypes.otherHalf(world, configured);
            BlockPos canonical = other == null || configured.compareTo(other) <= 0 ? configured : other;
            if (!seen.add(canonical)) continue;
            targets.add(new Target(part, configured));
        }
    }

    private void openNext() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        Target target = targets.get(index);
        handler = null;
        openWaitTicks = 0;
        settleTicks = 0;

        ChatUtils.debug("工作站检查 第 " + (index + 1) + "/" + targets.size() + " 个："
            + target.part().display() + " " + target.pos().toShortString());
        ContainerOpener.open(mc, player, target.pos());
    }

    private void advance() {
        index++;
        if (index >= targets.size()) {
            finish();
            return;
        }
        openNext();
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;
        mc.player.closeHandledScreen();
    }

    // ------------------------------------------------------------------
    // 数一个容器
    // ------------------------------------------------------------------

    private void survey(Target target) {
        Map<String, Integer> items = ContainerCacheManager.snapshot(handler);
        int totalSlots = 0;
        int emptySlots = 0;
        boolean allEmpty = true;
        boolean allEmptyShulkerBoxes = true;

        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            ItemStack stack = slot.getStack();
            totalSlots++;
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            allEmpty = false;
            if (!isEmptyShulkerBox(stack)) allEmptyShulkerBoxes = false;
        }

        surveys.computeIfAbsent(target.part(), key -> new ArrayList<>())
            .add(new Survey(target.pos(), items, totalSlots, emptySlots, allEmpty, allEmptyShulkerBoxes));

        ChatUtils.debug("工作站检查：" + target.part().display() + " " + target.pos().toShortString()
            + " —— " + totalSlots + " 格，空 " + emptySlots + " 格，物品 " + items.size() + " 种"
            + (allEmpty ? "，全空" : "")
            + (!allEmpty && allEmptyShulkerBoxes && emptySlots == 0 ? "，整箱都是空潜影盒" : ""));
    }

    private static boolean isEmptyShulkerBox(ItemStack stack) {
        return ShulkerUtils.isShulkerBox(stack) && ShulkerUtils.contents(stack).isEmpty();
    }

    // ------------------------------------------------------------------
    // 验证
    // ------------------------------------------------------------------

    private void checkItemTemp() {
        List<Survey> list = surveys.get(StationPart.ITEM_TEMP);
        if (list == null || list.isEmpty()) {
            addProblem("任务前物品暂存处没有检查到（没打开成功）");
            return;
        }
        Survey survey = list.get(0);
        if (!survey.allEmpty()) {
            addProblem("任务前物品暂存处 " + survey.pos().toShortString() + " 里还有东西（占用了 "
                + (survey.totalSlots() - survey.emptySlots()) + " 格），必须清空");
        }
    }

    private void checkProvider() {
        List<Survey> list = surveys.get(StationPart.SHULKER_BOX_PROVIDER);
        if (list == null || list.isEmpty()) {
            addProblem("空潜影盒提供处没有检查到（没打开成功）");
            return;
        }
        Survey survey = list.get(0);
        if (survey.emptySlots() > 0) {
            addProblem("空潜影盒提供处 " + survey.pos().toShortString() + " 还没填满（空着 "
                + survey.emptySlots() + " 格），要整桶都是空潜影盒");
        } else if (!survey.allEmptyShulkerBoxes()) {
            addProblem("空潜影盒提供处 " + survey.pos().toShortString() + " 里有不是空潜影盒的东西");
        }
    }

    private int freeSlots(StationPart part) {
        int free = 0;
        for (Survey survey : surveys.getOrDefault(part, List.of())) free += survey.emptySlots();
        return free;
    }

    private void addProblem(String problem) {
        problems.add(problem);
        ChatUtils.debug("工作站检查问题： " + problem);
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private void writeReport(int freeStorage, int freeFinal) {
        JsonObject report = new JsonObject();
        report.add("item_storage", itemsJson(aggregate(StationPart.ITEM_STORAGE)));
        report.add("item_final", itemsJson(aggregate(StationPart.ITEM_FINAL)));
        report.addProperty("free_storage_slots", freeStorage);
        report.addProperty("free_final_slots", freeFinal);

        Path file = StationManager.get().currentCheckFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(report, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("写工作站检查报告失败: " + e.getMessage());
            return;
        }

        ChatUtils.info("工作站检查通过，报告已写到 " + file);
        ChatUtils.info("物资存储地 " + freeStorage + " 格空位（物品 " + describe(aggregate(StationPart.ITEM_STORAGE))
            + "）；最终产物地 " + freeFinal + " 格空位（物品 " + describe(aggregate(StationPart.ITEM_FINAL)) + "）");
    }

    /** 某一部分里所有容器数出来的东西的加和。 */
    private Map<String, Integer> aggregate(StationPart part) {
        Map<String, Integer> total = new TreeMap<>();
        for (Survey survey : surveys.getOrDefault(part, List.of())) {
            for (Map.Entry<String, Integer> item : survey.items().entrySet()) {
                total.merge(item.getKey(), item.getValue(), Integer::sum);
            }
        }
        return total;
    }

    private static JsonObject itemsJson(Map<String, Integer> items) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Integer> item : items.entrySet()) {
            object.addProperty(item.getKey(), item.getValue());
        }
        return object;
    }

    private static String describe(Map<String, Integer> items) {
        if (items.isEmpty()) return "空";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> item : items.entrySet()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(item.getKey()).append(" x").append(item.getValue());
        }
        return sb.toString();
    }
}
