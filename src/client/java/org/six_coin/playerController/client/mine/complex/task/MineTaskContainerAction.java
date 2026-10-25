package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.six_coin.playerController.client.mine.MineTools;
import org.six_coin.playerController.client.mine.station.MineToolRules;
import java.util.function.Predicate;

/**
 * 开一个容器，按几种固定套路搬东西。补货流程全靠它。
 *
 * <table border="1">
 *   <caption>模式</caption>
 *   <tr><th>模式</th><th>干什么</th><th>用在哪</th></tr>
 *   <tr><td>{@link Mode#TO_INVENTORY}</td><td>容器 → 物品栏（同类半叠优先，其次空格）</td>
 *       <td>把盒子里的东西倒进物品栏；从 item_temp 拿圆石</td></tr>
 *   <tr><td>{@link Mode#TO_CONTAINER}</td><td>物品栏 → 容器（同类半叠优先，其次空格）</td>
 *       <td>往收集盒里装货；往工具缓存里放待修 / 修好的工具</td></tr>
 *   <tr><td>{@link Mode#SYNC_TO_CONTAINER}</td><td>物品栏 → 容器，<b>按位置一一对应</b></td>
 *       <td>卸货（背包 1~27 → 大箱子第 1~3 行，快捷栏 → 第 4 行）</td></tr>
 *   <tr><td>{@link Mode#SYNC_TO_INVENTORY}</td><td>容器 → 物品栏，<b>按位置一一对应</b></td>
 *       <td>补货最后把东西原封不动拿回来</td></tr>
 *   <tr><td>{@link Mode#COUNT}</td><td>只数，不动东西</td><td>看盒子满没满</td></tr>
 * </table>
 *
 * <p><b>位置对应</b>（大箱子 54 格里的前 36 格）：
 * 箱子第 {@code 0~26} 格 ↔ 背包 {@code 9~35}；箱子第 {@code 27~35} 格 ↔ 快捷栏 {@code 0~8}。
 *
 * <p>「装不下」不算失败：{@link #containerFull()} / {@link #inventoryFull()} 会是 true，
 * 由调用方决定（收集盒满了要等活塞换一个新的；物品栏满了就是真满了）。
 */
public class MineTaskContainerAction extends Action {

    public enum Mode {
        TO_INVENTORY,
        TO_CONTAINER,
        SYNC_TO_CONTAINER,
        SYNC_TO_INVENTORY,
        COUNT
    }

    private static final int MAX_TOTAL_TICKS = 20 * 300;

    private final BlockPos pos;
    /** 对应的 station_data 条目（有的话搬完顺手更新它）；临时盒子这种没有。 */
    @Nullable
    private final MineTaskStationData.ContainerInfo info;
    private final Mode mode;
    private final Predicate<ItemStack> filter;
    private final int maxCount;
    private final Set<Integer> invSlots;
    private final String what;
    /** 界面中途被关掉算不算「满了」（收集盒被活塞打掉就是这种情况）。 */
    private final boolean allowClose;
    private final ContainerSession session;

    private enum Phase {
        OPENING,
        WORK
    }

    private Phase phase = Phase.OPENING;
    private int totalTicks;
    private int moved;
    private int mapIndex;
    private boolean containerFull;
    private boolean inventoryFull;

    private final Map<String, Integer> counted = new LinkedHashMap<>();
    private int countedSlots;
    private int countedEmpty;
    /** {@link Mode#COUNT}：里面有几个潜影盒。 */
    private int countedBoxes;
    /** {@link Mode#COUNT} 数到的工具：容器第几格、什么种类、还剩多少耐久、耐久上限。 */
    public record ToolEntry(int slot, MineToolRules.Kind kind, int remaining, int maxDamage,
                            String itemId) {

        /** 耐久是满的。 */
        public boolean full() {
            return remaining >= maxDamage;
        }
    }

    /** {@link Mode#COUNT}：每种工具里满耐久的 / 要修的各几件。 */
    private final Map<MineToolRules.Kind, Integer> fullTools = new java.util.EnumMap<>(MineToolRules.Kind.class);
    private final Map<MineToolRules.Kind, Integer> repairTools = new java.util.EnumMap<>(MineToolRules.Kind.class);
    /** {@link Mode#COUNT}：容器里所有工具（按格子）。 */
    private final List<ToolEntry> tools = new ArrayList<>();

    /**
     * @param maxCount 最多搬多少<b>个</b>（{@code <= 0} = 不限；整叠搬，可能略微超出）
     * @param invSlots 只看这几格物品栏（空集合 = 全部都看）
     */
    public MineTaskContainerAction(BlockPos pos, Mode mode, Predicate<ItemStack> filter,
                                   int maxCount, Set<Integer> invSlots, String what) {
        this(pos, null, mode, filter, maxCount, invSlots, what);
    }

    /**
     * 允许界面中途被关掉（当成「容器满了」正常结束）。
     *
     * <p>收集盒装满之后会被活塞打掉，客户端这边的界面会被服务端关掉 —— 那不是出错。
     */
    public MineTaskContainerAction allowClose() {
        return new MineTaskContainerAction(pos, info, mode, filter, maxCount, invSlots, what, true);
    }

    /** 带 station_data 的版本：搬完会更新那边的数量和空格数。 */
    public MineTaskContainerAction(MineTaskStationData.ContainerInfo container, Mode mode,
                                   Predicate<ItemStack> filter, int maxCount,
                                   Set<Integer> invSlots, String what) {
        this(container.pos(), container, mode, filter, maxCount, invSlots, what);
    }

    private MineTaskContainerAction(BlockPos pos, @Nullable MineTaskStationData.ContainerInfo info,
                                    Mode mode, Predicate<ItemStack> filter,
                                    int maxCount, Set<Integer> invSlots, String what) {
        this(pos, info, mode, filter, maxCount, invSlots, what, false);
    }

    private MineTaskContainerAction(BlockPos pos, @Nullable MineTaskStationData.ContainerInfo info,
                                    Mode mode, Predicate<ItemStack> filter,
                                    int maxCount, Set<Integer> invSlots, String what,
                                    boolean allowClose) {
        this.pos = pos.immutable();
        this.info = info;
        this.mode = mode;
        this.filter = filter;
        this.maxCount = maxCount;
        this.invSlots = Set.copyOf(invSlots);
        this.what = what;
        this.allowClose = allowClose;
        this.session = new ContainerSession(this.pos);
    }

    /** 搬走了几个（{@link Mode#COUNT} 时没意义）。 */
    public int moved() {
        return moved;
    }

    /** 容器满了（还有东西没搬完）。 */
    public boolean containerFull() {
        return containerFull;
    }

    /** 物品栏满了（还有东西没搬完）。 */
    public boolean inventoryFull() {
        return inventoryFull;
    }

    /** {@link Mode#COUNT}：容器里每种物品多少个。 */
    public Map<String, Integer> counted() {
        return counted;
    }

    public int countedSlots() {
        return countedSlots;
    }

    public int countedEmpty() {
        return countedEmpty;
    }

    /** {@link Mode#COUNT}：里面有几个潜影盒。 */
    public int countedBoxes() {
        return countedBoxes;
    }

    /** {@link Mode#COUNT}：这种工具满耐久的有几件。 */
    public int fullTools(MineToolRules.Kind kind) {
        return fullTools.getOrDefault(kind, 0);
    }

    /** {@link Mode#COUNT}：这种工具要修的有几件。 */
    public int repairTools(MineToolRules.Kind kind) {
        return repairTools.getOrDefault(kind, 0);
    }

    /** {@link Mode#COUNT}：容器里所有工具（按格子）。 */
    public List<ToolEntry> tools() {
        return tools;
    }

    @Override
    public String name() {
        return "容器 " + pos.toShortString() + "：" + what;
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("离容器 " + pos.toShortString() + " 太远（落点没走到？）");
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            fail("光标上还有物品，先放下");
            return;
        }
        session.open(mc, player);
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail(what + " 超时（" + totalTicks + " tick）");
            return;
        }

        if (phase == Phase.OPENING) {
            switch (session.awaitOpen(mc)) {
                case READY -> phase = Phase.WORK;
                case TIMEOUT -> fail("打不开 " + pos.toShortString() + "（等界面超时）");
                case WAITING -> {
                }
            }
            return;
        }

        if (!session.stillOpen(mc)) {
            if (allowClose) {
                ChatUtils.debug("%s 的界面被关掉了（收集盒被活塞打掉了？）", pos.toShortString());
                containerFull = true;
                finish();
                return;
            }
            fail("容器界面被关掉了（" + pos.toShortString() + "）");
            return;
        }

        switch (mode) {
            case COUNT -> count();
            case TO_INVENTORY -> toInventory(player);
            case TO_CONTAINER -> toContainer(player);
            case SYNC_TO_CONTAINER -> syncToContainer(player);
            case SYNC_TO_INVENTORY -> syncToInventory(player);
        }
    }

    @Override
    protected void onEnd() {
        session.close(Minecraft.getInstance());
        if (failureReason() == null) {
            ChatUtils.debug("%s：%s（搬了 %d 个%s%s）", pos.toShortString(), what, moved,
                containerFull ? "，容器满了" : "", inventoryFull ? "，物品栏满了" : "");
        }
    }

    // ------------------------------------------------------------------
    // 各种模式
    // ------------------------------------------------------------------

    private void count() {
        List<Slot> slots = containerSlots();
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            countedSlots++;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                countedEmpty++;
                continue;
            }
            counted.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), Integer::sum);

            if (ShulkerUtils.isShulkerBox(stack)) {
                countedBoxes++;
                continue;
            }
            MineToolRules.Kind kind = MineToolRules.kindOf(stack);
            if (kind != null) {
                tools.add(new ToolEntry(index, kind, MineTools.remaining(stack),
                    stack.getMaxDamage(),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
            }
            if (kind != null && MineToolRules.isLegal(stack)) {
                Map<MineToolRules.Kind, Integer> target =
                    MineToolRules.isUsable(stack) ? fullTools : repairTools;
                target.merge(kind, 1, Integer::sum);
            }
        }
        finish();
    }

    private void toInventory(LocalPlayer player) {
        if (maxCount > 0 && moved >= maxCount) {
            finish();
            return;
        }

        Slot source = null;
        for (Slot slot : containerSlots()) {
            if (slot.getItem().isEmpty()) continue;
            if (!filter.test(slot.getItem())) continue;
            source = slot;
            break;
        }
        if (source == null) {
            finish();
            return;
        }

        ItemStack stack = source.getItem().copy();
        Slot target = findInventoryTarget(player, stack);
        if (target == null) {
            inventoryFull = true;
            finish();
            return;
        }
        doMove(source, target, stack.getCount());
    }

    private void toContainer(LocalPlayer player) {
        if (maxCount > 0 && moved >= maxCount) {
            finish();
            return;
        }

        Slot source = null;
        for (int index = 0; index <= 35; index++) {
            if (!invSlots.isEmpty() && !invSlots.contains(index)) continue;
            ItemStack stack = player.getInventory().getItem(index);
            if (stack.isEmpty()) continue;
            if (!filter.test(stack)) continue;
            source = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), index);
            if (source != null) break;
        }
        if (source == null) {
            finish();
            return;
        }

        ItemStack stack = source.getItem().copy();
        Slot target = findContainerTarget(stack);
        if (target == null) {
            containerFull = true;
            finish();
            return;
        }
        doMove(source, target, stack.getCount());
    }

    private void syncToContainer(LocalPlayer player) {
        while (mapIndex <= 35) {
            int index = mapIndex++;
            if (!invSlots.isEmpty() && !invSlots.contains(index)) continue;

            ItemStack stack = player.getInventory().getItem(index);
            if (stack.isEmpty()) continue;

            Slot source = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), index);
            Slot target = containerSlotAt(chestSlotFor(index));
            if (source == null || target == null) {
                fail("找不到物品栏第 " + index + " 格或者箱子第 " + chestSlotFor(index) + " 格");
                return;
            }
            if (!target.getItem().isEmpty()) {
                fail("箱子第 " + chestSlotFor(index) + " 格被 " + InventoryUtils.describe(target.getItem())
                    + " 占着（应该是空的）");
                return;
            }
            doMove(source, target, stack.getCount());
            return;
        }
        finish();
    }

    private void syncToInventory(LocalPlayer player) {
        while (mapIndex <= 35) {
            int chest = mapIndex++;
            Slot source = containerSlotAt(chest);
            if (source == null) continue;
            if (source.getItem().isEmpty()) continue;

            int index = invIndexFor(chest);
            Slot target = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), index);
            if (target == null) {
                fail("找不到物品栏第 " + index + " 格");
                return;
            }
            if (!target.getItem().isEmpty()) {
                fail("物品栏第 " + index + " 格被 " + InventoryUtils.describe(target.getItem())
                    + " 占着（应该是空的）");
                return;
            }
            doMove(source, target, source.getItem().getCount());
            return;
        }
        finish();
    }

    // ------------------------------------------------------------------
    // 搬一格
    // ------------------------------------------------------------------

    private void doMove(Slot from, Slot to, int count) {
        ItemStack before = from.getItem().copy();
        if (!session.click(from, 0, ContainerInput.PICKUP, "拿起 " + InventoryUtils.describe(before))) {
            fail("点不了 " + InventoryUtils.describe(from));
            return;
        }
        if (session.handler().getCarried().isEmpty()) {
            return;      // 本地预测还没跟上，下一 tick 再来
        }

        if (!session.click(to, 0, ContainerInput.PICKUP, "放进 " + InventoryUtils.describe(to))) {
            session.click(from, 0, ContainerInput.PICKUP, "放回原格");
            fail("点不了 " + InventoryUtils.describe(to));
            return;
        }

        if (!session.handler().getCarried().isEmpty()) {
            // 目标格塞不下整叠：剩下的放回去，按「满了」处理
            session.click(from, 0, ContainerInput.PICKUP, "剩下的放回原格");
            if (mode == Mode.TO_INVENTORY || mode == Mode.SYNC_TO_INVENTORY) {
                inventoryFull = true;
            } else {
                containerFull = true;
            }
        }

        int after = from.getItem().getCount();
        int delta = Math.max(0, before.getCount() - after);
        moved += delta;

        // 顺手更新 station_data（补货流程后面判断「还有没有」全靠它）
        if (info != null && delta > 0) {
            ItemStack change = before.copy();
            change.setCount(delta);
            if (mode == Mode.TO_INVENTORY || mode == Mode.SYNC_TO_INVENTORY) {
                info.take(change);
            } else {
                info.put(change);
            }
        }
    }

    // ------------------------------------------------------------------
    // 找格子
    // ------------------------------------------------------------------

    /** 物品栏里能放这一叠的格子：同类半叠优先，其次空格（背包优先，其次快捷栏）。 */
    @Nullable
    private Slot findInventoryTarget(LocalPlayer player, ItemStack stack) {
        Slot empty = null;
        for (int index = 9; index <= 35; index++) {
            Slot slot = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), index);
            if (slot == null) continue;
            if (!slot.getItem().isEmpty() && InventoryUtils.roomFor(slot, stack) >= stack.getCount()) {
                return slot;
            }
            if (slot.getItem().isEmpty() && empty == null) empty = slot;
        }
        if (empty != null) return empty;

        for (int index = 0; index <= 8; index++) {
            Slot slot = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), index);
            if (slot == null) continue;
            if (!slot.getItem().isEmpty() && InventoryUtils.roomFor(slot, stack) >= stack.getCount()) {
                return slot;
            }
            if (slot.getItem().isEmpty() && empty == null) empty = slot;
        }
        return empty;
    }

    /**
     * 容器里能放这一叠的格子：**先找整叠放得下的**（同类半叠），其次空格，最后才是放得下一部分的半叠。
     *
     * <p>先找「整叠放得下」很重要：收集盒里常常有半叠，随便挑一个半叠会把整叠拆开，
     * 剩下的又得放回物品栏，白白多跑一趟。
     */
    @Nullable
    private Slot findContainerTarget(ItemStack stack) {
        Slot empty = null;
        Slot partial = null;
        for (Slot slot : containerSlots()) {
            if (!slot.isActive() || !slot.mayPlace(stack)) continue;
            if (slot.getItem().isEmpty()) {
                if (empty == null) empty = slot;
                continue;
            }
            int room = InventoryUtils.roomFor(slot, stack);
            if (room >= stack.getCount()) return slot;
            if (room > 0 && partial == null) partial = slot;
        }
        return empty != null ? empty : partial;
    }

    private List<Slot> containerSlots() {
        return InventoryUtils.containerSlots(session.handler());
    }

    @Nullable
    private Slot containerSlotAt(int index) {
        List<Slot> slots = containerSlots();
        return index >= 0 && index < slots.size() ? slots.get(index) : null;
    }

    /** 物品栏下标 → 大箱子里的第几格。 */
    public static int chestSlotFor(int invIndex) {
        return invIndex >= 9 ? invIndex - 9 : 27 + invIndex;
    }

    /** 大箱子里的第几格 → 物品栏下标。 */
    public static int invIndexFor(int chestSlot) {
        return chestSlot < 27 ? chestSlot + 9 : chestSlot - 27;
    }

    /** 0~35 所有物品栏格（给「只看全部」用）。 */
    public static Set<Integer> allSlots() {
        Set<Integer> set = new LinkedHashSet<>();
        for (int i = 0; i <= 35; i++) set.add(i);
        return set;
    }

    /** {@code from ~ to} 这一段物品栏格。 */
    public static Set<Integer> slots(int from, int to) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int i = from; i <= to; i++) set.add(i);
        return set;
    }

    /** 日志用：一个容器里的东西（前 8 种）。 */
    public static String describeItems(Map<String, Integer> items) {
        if (items.isEmpty()) return "（空）";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            parts.add(entry.getKey() + " x" + entry.getValue());
            if (parts.size() >= 8) {
                parts.add("…");
                break;
            }
        }
        return String.join("、", parts);
    }
}
