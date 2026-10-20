package org.six_coin.playerController.client.mine.station;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.mine.MineTools;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.PathPlanner;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointGraph;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /pc mine complex station check} 的执行体（分多 tick）。
 *
 * <p>分两大段：
 * <ol>
 *   <li><b>算落点</b>：给每个配置的容器找一个 {@code access_position}（见 {@link MineStationAccess}），
 *       写进 {@code world_<n>/mine/station.json}；再检查 safe_point 到每个落点走不走得通。
 *       这一步是同步的，{@link #onStart()} 里一次算完；</li>
 *   <li><b>走过去开箱</b>：从当前位置按路径图一个接一个走到落点、对齐到方块中心、打开容器数内容
 *       （和 {@code /pc stock station check} 一样：不显示界面）。</li>
 * </ol>
 *
 * <p>「不在路径点的可达范围内」是硬性要求：任何一个配了的东西，只要周围触及距离内没有路径点 / 边，
 * 或者它的落点从安全点走不到，都记一条问题（而且不去开它，省得白跑）。
 *
 * <p>内容要求（不满足的记成问题，最后一起报）：
 * <ul>
 *   <li>产物收集（<b>可选</b>，不配就不查）：方块必须是潜影盒，里面要么空、要么只有对应的产物；</li>
 *   <li>食物补给：每一格都是熟猪排、而且每格 64 个；</li>
 *   <li>图腾补给：每一格都是不死图腾（不可堆叠，每格 1 个就是满的）；</li>
 *   <li>工具缓存：每个容器里只有合法工具（见 {@link MineToolRules}），
 *       而且<em>每个容器</em>里五类工具各至少 {@value MineToolRules#REQUIRED_PER_KIND} 件可用；</li>
 *   <li>黏液块缓存：每一格都是黏液块或树脂块。</li>
 * </ul>
 *
 * <p>报告写到 {@code world_<n>/mine/check.json}（不管过没过都写，过没过看 {@code passed}）。
 */
public class MineStationCheckAction extends Action {

    /** 整个检查最久（20 tick = 1 秒）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 60 * 60;

    private static final String COOKED_PORKCHOP = "minecraft:cooked_porkchop";
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static final String SLIME_BLOCK = "minecraft:slime_block";
    private static final String RESIN_BLOCK = "minecraft:resin_block";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final List<MineStationPart> CONTAINER_PARTS = List.of(
        MineStationPart.ITEM_COLLECTOR,
        MineStationPart.FOOD_PROVIDER,
        MineStationPart.TOTEM_PROVIDER,
        MineStationPart.TOOL_CACHE,
        MineStationPart.SLIME_CACHE);

    /** 一个要开的目标。 */
    private record Target(MineStationPart part, MinePlace place, BlockPos pos, BlockPos access) {
    }

    /** 开完一个容器数出来的东西。 */
    private record Survey(MineStationPart part,
                          int id,
                          @Nullable String itemId,
                          String dimension,
                          BlockPos pos,
                          BlockPos access,
                          String blockId,
                          boolean shulkerBox,
                          int totalSlots,
                          int emptySlots,
                          List<ItemStack> stacks,
                          Map<String, Integer> items) {
    }

    private enum Stage {
        MOVING,
        OPENING
    }

    private final List<Target> targets = new ArrayList<>();
    private final List<Survey> surveys = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private int index;
    private Stage stage = Stage.MOVING;
    @Nullable
    private Target current;
    @Nullable
    private Action moveAction;
    @Nullable
    private ContainerSession session;
    private int totalTicks;
    private boolean scannedAll;

    @Override
    public String name() {
        return "挖掘站检查";
    }

    // ------------------------------------------------------------------
    // 开始：算落点、建目标
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        // 后面每一步都是从玩家当前位置规划路径，所以必须先站在图上
        if (WaypointManager.get().playerStartWaypoint() == null) {
            fail("你现在既不在路径点上，也不在任何边上 —— 先站到路径点图上再检查");
            return;
        }

        prepare(mc);
        if (isFinished()) return;

        if (targets.isEmpty()) {
            scannedAll = true;
            finish();
            return;
        }

        beginMove();
    }

    /** 第 1 步：给所有容器算 access_position、检查可达性、列出要开的目标。 */
    private void prepare(MinecraftClient mc) {
        MineStationManager manager = MineStationManager.get();
        WaypointGraph graph = WaypointManager.get().graph();

        MinePlace safe = manager.single(MineStationPart.SAFE_POINT);
        if (safe == null) {
            fail("没有设置安全点（/pc mine complex station safe_point set …）");
            return;
        }
        Waypoint safeNode = MineStationAccess.waypointAt(safe.dimension(), safe.pos());
        if (safeNode == null) {
            fail("安全点 " + safe.describe() + " 既不是路径点，也不在任何边上 —— 先把安全点放到路径点图上");
            return;
        }
        safe.access(MineAccessPos.of(safe.dimension(), safe.pos()));

        // 该配没配的部分：产物收集（item_collector）是可选的，不配就是不收集产物，别的不配都算问题
        for (MineStationPart part : CONTAINER_PARTS) {
            if (part.optional()) continue;
            if (manager.positions(part).isEmpty()) {
                addProblem("没有配置" + part.display() + "（" + part.id() + "）");
            }
        }

        Map<String, Set<Long>> occupied = MineStationAccess.buildOccupied(graph);
        List<BlockPos> offsets = MineStationAccess.sphereOffsets(PlayerUtils.reach());

        ChatUtils.debug("挖掘站检查：给容器算落点（触及距离 " + PlayerUtils.reach()
            + " 格，球形偏移 " + offsets.size() + " 个）");

        // 配了的东西每一个都要在路径点的可达范围内：落点算得出来、而且从安全点走得到。
        // 不满足的只报错、不加进 targets —— 免得白走一趟再报一次「走不到」。
        for (MineStationPart part : CONTAINER_PARTS) {
            for (MinePlace place : manager.positions(part)) {
                if (!resolveAccess(part, place, occupied, offsets, safeNode, graph)) continue;
                targets.add(new Target(part, place, place.pos(), place.access().pos()));
            }
        }

        // 落点可能刚从边中间切出新路径点，统一存一次
        manager.save();
        WaypointManager.get().save();

        ChatUtils.debug("挖掘站检查：落点算完，要开 " + targets.size() + " 个容器；问题 " + problems.size() + " 条");
    }

    /**
     * 给一个容器算落点并检查可达性。
     *
     * <p>有问题（周围没有路径点 / 边、建不成路径点、从安全点走不到）就记一条问题并返回 false，
     * 这个容器后面就不去开了。
     */
    private boolean resolveAccess(MineStationPart part, MinePlace place,
                                  Map<String, Set<Long>> occupied, List<BlockPos> offsets,
                                  Waypoint safeNode, WaypointGraph graph) {
        BlockPos access = MineStationAccess.findAccess(occupied, place.dimension(),
            place.pos(), offsets);
        if (access == null) {
            place.access(null);
            addProblem(describe(part, place) + " 周围 " + format(PlayerUtils.reach())
                + " 格内既没有路径点也没有边 —— 不在路径点的可达范围内");
            return false;
        }

        place.access(MineAccessPos.of(place.dimension(), access));
        Waypoint node = MineStationAccess.waypointAt(place.dimension(), access);
        if (node == null) {
            addProblem(describe(part, place) + " 的落点 " + access.toShortString() + " 建不成路径点");
            return false;
        }
        if (graph.shortestPath(safeNode.id(), node.id()) == null) {
            addProblem(describe(part, place) + " 的落点 " + access.toShortString()
                + " 从安全点走不到 —— 不在路径点的可达范围内");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 走 / 开
    // ------------------------------------------------------------------

    private void beginMove() {
        while (index < targets.size()) {
            Target target = targets.get(index);
            current = target;
            stage = Stage.MOVING;

            PathPlanner.Plan plan = PathPlanner.toPosition(target.access(), target.place().dimension());
            if (!plan.ok()) {
                addProblem("走不到 " + describe(target.part(), target.place()) + " 的落点 "
                    + target.access().toShortString() + "：" + plan.error());
                index++;
                continue;
            }
            if (plan.alreadyThere()) {
                arrive();
                return;
            }

            ChatUtils.debug("挖掘站检查 第 " + (index + 1) + "/" + targets.size() + " 个：走向 "
                + describe(target.part(), target.place()) + " 的落点 " + target.access().toShortString());
            moveAction = new PathMoveAction(plan.path());
            moveAction.start();
            return;
        }
        finishAll();
    }

    private void arrive() {
        Target target = current;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (target == null || player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!DimensionUtils.current().equals(target.place().dimension())) {
            addProblem("走到 " + describe(target.part(), target.place()) + " 的落点时不在 "
                + DimensionUtils.display(target.place().dimension()) + "（现在在 "
                + DimensionUtils.display(DimensionUtils.current()) + "）");
            nextTarget();
            return;
        }

        // 对齐到落点方块中心：开容器用的是玩家当前位置，偏了可能够不着
        Vec3d center = PlayerUtils.blockCenter(target.access());
        if (player.squaredDistanceTo(center) > 1.0E-6) {
            PlayerUtils.snapToBlockCenter(target.access());
        }

        MinecraftClient client = MinecraftClient.getInstance();
        BlockState state = client.world.getBlockState(target.pos());
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        boolean shulkerBox = state.getBlock() instanceof ShulkerBoxBlock;
        boolean supported = ContainerTypes.isSupported(state.getBlock());

        if (target.part() == MineStationPart.ITEM_COLLECTOR) {
            if (!shulkerBox) {
                addProblem(describe(target.part(), target.place()) + " 不是潜影盒（是 " + blockId + "）");
            }
        } else if (!supported) {
            addProblem(describe(target.part(), target.place()) + " 不是支持的容器（是 " + blockId
                + "，只支持 " + ContainerTypes.describeSupported() + "）");
        }

        if (!shulkerBox && !supported) {
            addProblem(describe(target.part(), target.place()) + " 打不开（不是容器）");
            nextTarget();
            return;
        }

        session = new ContainerSession(target.pos());
        session.open(client, player);
        stage = Stage.OPENING;
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("检查超时（" + totalTicks + " tick）");
            return;
        }

        switch (stage) {
            case MOVING -> tickMove();
            case OPENING -> tickOpen(mc);
        }
    }

    private void tickMove() {
        if (moveAction == null) {
            fail("内部状态不对：没有正在跑的移动");
            return;
        }

        moveAction.update();
        if (!moveAction.isFinished()) return;

        String failure = moveAction.failureReason();
        moveAction.cleanup();
        moveAction = null;

        if (failure != null) {
            addProblem("走到 " + (current == null ? "？" : current.access().toShortString())
                + " 失败：" + failure);
            nextTarget();
            return;
        }
        arrive();
    }

    private void tickOpen(MinecraftClient mc) {
        Target target = current;
        if (target == null || session == null) {
            fail("内部状态不对：没有正在打开的容器");
            return;
        }

        if (!session.isOpen()) {
            switch (session.awaitOpen(mc)) {
                case READY -> ChatUtils.debug("挖掘站检查：" + describe(target.part(), target.place())
                    + " 界面已打开");
                case TIMEOUT -> {
                    addProblem(describe(target.part(), target.place()) + " 打不开（等容器界面超时）");
                    closeSession(mc);
                    nextTarget();
                }
                case WAITING -> {
                }
            }
            return;
        }

        if (!session.stillOpen(mc)) {
            addProblem(describe(target.part(), target.place()) + " 打开后被关掉了，没数到");
            closeSession(mc);
            nextTarget();
            return;
        }

        if (!session.settled()) return;

        survey(target, session);
        closeSession(mc);
        nextTarget();
    }

    private void survey(Target target, ContainerSession open) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockState state = mc.world.getBlockState(target.pos());

        List<ItemStack> stacks = new ArrayList<>();
        Map<String, Integer> items = new LinkedHashMap<>();
        int totalSlots = 0;
        int emptySlots = 0;

        for (Slot slot : InventoryUtils.containerSlots(open.handler())) {
            totalSlots++;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            ItemStack copy = stack.copy();
            stacks.add(copy);
            items.merge(Registries.ITEM.getId(copy.getItem()).toString(), copy.getCount(), Integer::sum);
        }

        surveys.add(new Survey(target.part(), target.place().id(), target.place().itemId(),
            target.place().dimension(), target.pos(), target.access(),
            Registries.BLOCK.getId(state.getBlock()).toString(),
            state.getBlock() instanceof ShulkerBoxBlock,
            totalSlots, emptySlots, stacks, items));

        ChatUtils.debug("挖掘站检查：" + describe(target.part(), target.place()) + " —— " + totalSlots
            + " 格，空 " + emptySlots + " 格，物品 " + items.size() + " 种");
    }

    private void closeSession(MinecraftClient mc) {
        if (session == null) return;
        session.close(mc);
        session = null;
    }

    private void nextTarget() {
        index++;
        if (index >= targets.size()) {
            finishAll();
            return;
        }
        beginMove();
    }

    private void finishAll() {
        scannedAll = true;
        finish();
    }

    // ------------------------------------------------------------------
    // 收尾：验证 + 写报告
    // ------------------------------------------------------------------

    @Override
    protected void onEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (moveAction != null) {
            moveAction.cleanup();
            moveAction = null;
        }
        closeSession(mc);

        if (failureReason() != null) {
            for (String problem : problems) ChatUtils.error("  · " + problem);
            return;
        }
        if (!scannedAll) return;

        validate();

        Path file = MineStationManager.get().currentCheckFile();
        int slime = countSlime(SLIME_BLOCK);
        int resin = countSlime(RESIN_BLOCK);
        writeReport(file, slime, resin);

        if (problems.isEmpty()) {
            output().info("挖掘站检查通过，报告已写到 " + file);
        } else {
            ChatUtils.error("挖掘站检查没通过（" + problems.size() + " 条）：");
            for (String problem : problems) ChatUtils.error("  · " + problem);
            output().info("报告（含问题）已写到 " + file);
        }
        output().info("黏液块 " + slime + " 个、树脂块 " + resin + " 个（共 " + (slime + resin) + " 个）");
    }

    /** 第 2 步的内容检查。 */
    private void validate() {
        validateCollectors();
        validateProvider(MineStationPart.FOOD_PROVIDER, COOKED_PORKCHOP, "熟猪排");
        validateProvider(MineStationPart.TOTEM_PROVIDER, TOTEM, "不死图腾");
        validateToolCaches();
        validateSlimeCaches();

        // 该开的容器有没有真的数到（没数到的大半是上面「打不开 / 半路失败」）：
        // 凡是配了而且可达的都要数到，一条没数到就报一条
        for (Target target : targets) {
            if (surveyed(target)) continue;
            addProblem(describe(target.part(), target.place()) + " 没有数到（没走到或者没打开）");
        }
    }

    /** 这个目标开过箱、数到过内容没有。 */
    private boolean surveyed(Target target) {
        for (Survey survey : surveys) {
            if (survey.part() == target.part() && survey.pos().equals(target.pos())) return true;
        }
        return false;
    }

    /** 产物收集是可选的：没配就一个都不查。 */
    private void validateCollectors() {
        for (Survey survey : surveysOf(MineStationPart.ITEM_COLLECTOR)) {
            String itemId = survey.itemId();
            if (itemId == null) {
                addProblem(describe(MineStationPart.ITEM_COLLECTOR, survey) + " 没有写产物 id（item_id）");
                continue;
            }
            for (ItemStack stack : survey.stacks()) {
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                if (id.equals(itemId)) continue;
                addProblem(describe(MineStationPart.ITEM_COLLECTOR, survey) + " 里有 " + id
                    + " x" + stack.getCount() + "，只允许 " + itemId + " 或者空");
            }
        }
    }

    /** 食物 / 图腾：每一格都必须是那种物品，而且每一格都是满堆（食物 64、图腾 1）。 */
    private void validateProvider(MineStationPart part, String itemId, String label) {
        for (Survey survey : surveysOf(part)) {
            if (survey.emptySlots() > 0) {
                addProblem(describe(part, survey) + " 有 " + survey.emptySlots()
                    + " 个空格，" + label + "要装满（每一格都是满堆）");
            }
            for (ItemStack stack : survey.stacks()) {
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                if (!id.equals(itemId)) {
                    addProblem(describe(part, survey) + " 里有 " + id + " x" + stack.getCount()
                        + "，这里只能放" + label);
                } else if (stack.getCount() != stack.getMaxCount()) {
                    addProblem(describe(part, survey) + " 里的" + label + "只有 " + stack.getCount()
                        + " 个（每格要 " + stack.getMaxCount() + " 个）");
                }
            }
        }
    }

    private void validateToolCaches() {
        for (Survey survey : surveysOf(MineStationPart.TOOL_CACHE)) {
            Map<MineToolRules.Kind, Integer> usable = new EnumMap<>(MineToolRules.Kind.class);
            Map<MineToolRules.Kind, Integer> repair = new EnumMap<>(MineToolRules.Kind.class);
            List<String> illegal = new ArrayList<>();

            for (ItemStack stack : survey.stacks()) {
                MineToolRules.Kind kind = MineToolRules.kindOf(stack);
                String reason = MineToolRules.illegalityReason(stack);
                if (reason != null) {
                    illegal.add(describeItem(stack) + "（" + reason + "）");
                    continue;
                }
                Map<MineToolRules.Kind, Integer> target =
                    MineToolRules.isUsable(stack) ? usable : repair;
                target.merge(kind, 1, Integer::sum);
            }

            if (!illegal.isEmpty()) {
                addProblem(describe(MineStationPart.TOOL_CACHE, survey) + " 里有不合法的工具："
                    + String.join("；", illegal));
            }
            for (MineToolRules.Kind kind : MineToolRules.Kind.values()) {
                int count = usable.getOrDefault(kind, 0);
                if (count < MineToolRules.REQUIRED_PER_KIND) {
                    addProblem(describe(MineStationPart.TOOL_CACHE, survey) + " 里可用的" + kind.label()
                        + "只有 " + count + " 件（每个容器要 ≥" + MineToolRules.REQUIRED_PER_KIND + "）");
                }
            }
        }
    }

    private void validateSlimeCaches() {
        for (Survey survey : surveysOf(MineStationPart.SLIME_CACHE)) {
            for (ItemStack stack : survey.stacks()) {
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                if (id.equals(SLIME_BLOCK) || id.equals(RESIN_BLOCK)) continue;
                addProblem(describe(MineStationPart.SLIME_CACHE, survey) + " 里有 " + id
                    + " x" + stack.getCount() + "，只允许黏液块和树脂块");
            }
        }
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private void writeReport(Path file, int slime, int resin) {
        JsonObject root = new JsonObject();
        root.addProperty("passed", problems.isEmpty());

        JsonArray problemArray = new JsonArray();
        for (String problem : problems) problemArray.add(problem);
        root.add("problems", problemArray);

        JsonObject slimeObject = new JsonObject();
        slimeObject.addProperty("slime_block", slime);
        slimeObject.addProperty("resin_block", resin);
        slimeObject.addProperty("total", slime + resin);
        root.add("slime_cache", slimeObject);

        JsonObject tools = new JsonObject();
        for (Survey survey : sortedSurveys(MineStationPart.TOOL_CACHE)) {
            JsonObject entry = new JsonObject();
            entry.add("position", positionJson(survey));
            entry.add("access_position", accessJson(survey));
            entry.add("usable", kindCountJson(survey, true));
            entry.add("repair", kindCountJson(survey, false));
            tools.add(String.valueOf(survey.id()), entry);
        }
        root.add("tool_cache", tools);

        JsonObject collectors = new JsonObject();
        for (Survey survey : sortedSurveys(MineStationPart.ITEM_COLLECTOR)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item_id", survey.itemId());
            entry.add("position", positionJson(survey));
            entry.add("access_position", accessJson(survey));
            entry.addProperty("is_shulker_box", survey.shulkerBox());
            entry.addProperty("slots", survey.totalSlots());
            entry.addProperty("empty_slots", survey.emptySlots());
            entry.add("items", itemsJson(survey.items()));
            collectors.add(String.valueOf(survey.id()), entry);
        }
        root.add("item_collector", collectors);

        JsonObject food = providerJson(MineStationPart.FOOD_PROVIDER);
        if (food != null) root.add("food_provider", food);
        JsonObject totem = providerJson(MineStationPart.TOTEM_PROVIDER);
        if (totem != null) root.add("totem_provider", totem);

        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("写挖掘站检查报告失败：" + e.getMessage());
        }
    }

    /** 某个部分里面每种工具各几件（{@code usable} 或 {@code repair}）。 */
    private static JsonObject kindCountJson(Survey survey, boolean usable) {
        JsonObject object = new JsonObject();
        Map<MineToolRules.Kind, Integer> counts = new EnumMap<>(MineToolRules.Kind.class);
        for (ItemStack stack : survey.stacks()) {
            MineToolRules.Kind kind = MineToolRules.kindOf(stack);
            if (kind == null || MineToolRules.illegalityReason(stack) != null) continue;
            if (MineToolRules.isUsable(stack) != usable) continue;
            counts.merge(kind, 1, Integer::sum);
        }
        for (MineToolRules.Kind kind : MineToolRules.Kind.values()) {
            object.addProperty(kind.suffix(), counts.getOrDefault(kind, 0));
        }
        return object;
    }

    private JsonObject providerJson(MineStationPart part) {
        Survey survey = firstSurvey(part);
        if (survey == null) return null;

        JsonObject object = new JsonObject();
        object.add("position", positionJson(survey));
        object.add("access_position", accessJson(survey));
        object.addProperty("slots", survey.totalSlots());
        object.addProperty("empty_slots", survey.emptySlots());
        object.add("items", itemsJson(survey.items()));
        return object;
    }

    private static JsonObject positionJson(Survey survey) {
        JsonObject object = new JsonObject();
        object.addProperty("dimension", survey.dimension());
        object.addProperty("x", survey.pos().getX());
        object.addProperty("y", survey.pos().getY());
        object.addProperty("z", survey.pos().getZ());
        return object;
    }

    private static JsonObject accessJson(Survey survey) {
        JsonObject object = new JsonObject();
        object.addProperty("dimension", survey.dimension());
        object.addProperty("x", survey.access().getX());
        object.addProperty("y", survey.access().getY());
        object.addProperty("z", survey.access().getZ());
        return object;
    }

    private static JsonObject itemsJson(Map<String, Integer> items) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Integer> item : items.entrySet()) {
            object.addProperty(item.getKey(), item.getValue());
        }
        return object;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private int countSlime(String itemId) {
        int total = 0;
        for (Survey survey : surveysOf(MineStationPart.SLIME_CACHE)) {
            for (ItemStack stack : survey.stacks()) {
                if (Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private List<Survey> surveysOf(MineStationPart part) {
        List<Survey> result = new ArrayList<>();
        for (Survey survey : surveys) {
            if (survey.part() == part) result.add(survey);
        }
        return result;
    }

    /** 按 id 排好的某一部分的 survey（报告里的键顺序稳定一些）。 */
    private List<Survey> sortedSurveys(MineStationPart part) {
        List<Survey> result = surveysOf(part);
        result.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return result;
    }

    @Nullable
    private Survey firstSurvey(MineStationPart part) {
        List<Survey> result = surveysOf(part);
        return result.isEmpty() ? null : result.get(0);
    }

    private static String describe(MineStationPart part, MinePlace place) {
        return part.display() + " #" + place.id() + " " + place.describe();
    }

    private static String describe(MineStationPart part, Survey survey) {
        return part.display() + " #" + survey.id() + " " + DimensionUtils.display(survey.dimension())
            + " " + survey.pos().toShortString();
    }

    private static String describeItem(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount()
            + "（剩余耐久 " + MineTools.remaining(stack) + "）";
    }

    private void addProblem(String problem) {
        problems.add(problem);
        ChatUtils.debug("挖掘站检查问题： " + problem);
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }
}
