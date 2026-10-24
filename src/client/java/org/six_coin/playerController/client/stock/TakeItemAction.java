package org.six_coin.playerController.client.stock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 从一个容器里拿**一整叠**符合条件的物品，直接放进**快捷栏的指定格**。
 *
 * <p>这是备货流程用的「轮子」（需求里点名要造的那种）：阶段1 从任务前物品暂存处取钻石镐放进快捷栏第一格、
 * 阶段3 从空潜影盒提供处取一个空盒放进快捷栏第三格，走的都是它。
 *
 * <p>为什么不复用 {@link ContainerGetAction}：那个只管主背包 27 格（绝对不碰快捷栏），
 * 而且它会按 item_list 扣数量、只取一部分；这里要的是「拿整叠 + 塞进指定的快捷栏格」。
 *
 * <p>找不到符合条件的东西**不算失败**：动作正常结束，{@link #found()} 是 false，由调用方决定怎么办
 * （备货里是「终止，拒绝执行」）。
 *
 * <p>快捷栏那一格被占了就直接失败（不会乱塞）：备货流程要求快捷栏第二、第三格是留出来的。
 */
public class TakeItemAction extends Action {

    private enum Phase {
        /** 等容器界面打开。 */
        OPENING,
        /** 找符合条件的容器格。 */
        FIND,
        /** 把源格整叠拿到光标上。 */
        PICK,
        /** 放进快捷栏指定格。 */
        DROP,
        DONE
    }

    private static final int MAX_STEP_WAIT_TICKS = 60;
    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final BlockPos pos;
    private final int hotbarIndex;
    private final Predicate<ItemStack> filter;
    /** 日志里怎么称呼要找的东西。 */
    private final String what;
    /** 没找到的时候，用来说明「为什么这一叠不合格」（返回 null 就是不相关的物品）。 */
    @Nullable
    private final java.util.function.Function<ItemStack, String> explain;

    private final ContainerSession session;
    private Phase phase = Phase.OPENING;

    private int stepWaitTicks;
    private int totalTicks;

    /** 这一 tick 一共点了几下（一 tick 的点击上限用它数）。 */
    private int clicks;

    private Slot source;
    private ItemStack expected = ItemStack.EMPTY;
    private boolean pendingClick;

    private boolean found;
    private ItemStack taken = ItemStack.EMPTY;

    /** 没找到的时候记下来的现场：容器里有几格东西、哪些是「差不多但不合格」的。 */
    private int nonEmptySlots;
    private final List<String> nearMiss = new ArrayList<>();

    /**
     * @param hotbarIndex 目标快捷栏格（背包下标 0~8，也就是「快捷栏第 N 格」的 N-1）
     * @param filter      什么样的物品算「要找的」
     * @param what        日志用的名字，比如「钻石镐」「空潜影盒」
     */
    public TakeItemAction(BlockPos pos, int hotbarIndex, Predicate<ItemStack> filter, String what) {
        this(pos, hotbarIndex, filter, what, null);
    }

    /**
     * @param explain 没找到时用的「为什么不合格」说明（比如「钻石镐（剩余耐久 500）」）；
     *                不是相关的物品就返回 null
     */
    public TakeItemAction(BlockPos pos, int hotbarIndex, Predicate<ItemStack> filter, String what,
                          @Nullable java.util.function.Function<ItemStack, String> explain) {
        this.pos = pos.immutable();
        this.hotbarIndex = hotbarIndex;
        this.filter = filter;
        this.what = what;
        this.explain = explain;
        this.session = new ContainerSession(this.pos);
    }

    /** 没找到的时候，一句能说清楚「容器里到底有什么」的话。 */
    public String notFoundReason() {
        StringBuilder sb = new StringBuilder();
        sb.append("容器 ").append(pos.toShortString()).append(" 里没有").append(what);
        if (!nearMiss.isEmpty()) {
            sb.append("（有类似的不合格：").append(String.join("、", nearMiss)).append("）");
        } else if (nonEmptySlots > 0) {
            sb.append("（里面 ").append(nonEmptySlots).append(" 格有东西，但都不是）");
        } else {
            sb.append("（里面是空的）");
        }
        return sb.toString();
    }

    /** 有没有找到并拿走。 */
    public boolean found() {
        return found;
    }

    /** 拿到手的东西（{@link #found()} 为 false 时是空气）。 */
    public ItemStack taken() {
        return taken;
    }

    @Override
    public String name() {
        return "从 " + pos.toShortString() + " 取一个" + what + "放进快捷栏第 " + (hotbarIndex + 1) + " 格";
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
        if (!(mc.level.getBlockEntity(pos) instanceof Container)) {
            fail(pos.toShortString() + " 不是容器（没有物品栏）");
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("容器 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            fail("光标上还有物品，先放下再执行");
            return;
        }

        ChatUtils.debug("取物到快捷栏：目标 %s，要找 %s，放进快捷栏第 %d 格（背包下标 %d）",
            pos.toShortString(), what, hotbarIndex + 1, hotbarIndex);

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
            fail("超时（" + totalTicks + " tick）");
            return;
        }
        if (phase != Phase.OPENING && session.isOpen() && !session.stillOpen(mc)) {
            fail("容器界面被关掉了");
            return;
        }

        // 点击是本地预测、立即生效的：「拿起 → 放进快捷栏」这一串在一个 tick 里就能走完，
        // 只有本地预测还没变成预期值时才停下来等下一个 tick。
        int budget = ContainerClicks.MAX_CLICKS_PER_TICK;
        while (!isFinished() && budget > 0) {
            Phase before = phase;
            int clicksBefore = clicks;

            switch (phase) {
                case OPENING -> tickOpening(mc);
                case FIND -> tickFind();
                case PICK -> tickPick();
                case DROP -> tickDrop();
                case DONE -> finish();
            }

            if (isFinished()) return;
            int used = clicks - clicksBefore;
            if (used == 0 && phase == before) return;

            budget -= Math.max(used, 1);
        }
    }

    @Override
    protected void onEnd() {
        session.close(Minecraft.getInstance());

        if (failureReason() == null) {
            if (found) {
                ChatUtils.debug("取到了 %s，已经在快捷栏第 %d 格", InventoryUtils.describe(taken),
                    hotbarIndex + 1);
            } else {
                ChatUtils.debug("%s", notFoundReason());
            }
        }
    }

    // ------------------------------------------------------------------

    private void tickOpening(Minecraft mc) {
        switch (session.awaitOpen(mc)) {
            case READY -> {
                phase = Phase.FIND;
                stepWaitTicks = 0;
            }
            case TIMEOUT -> fail("等待容器界面超时");
            case WAITING -> {
            }
        }
    }

    private void tickFind() {
        source = null;
        nonEmptySlots = 0;
        nearMiss.clear();

        for (Slot slot : InventoryUtils.containerSlots(session.handler())) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            if (filter.test(stack)) {
                source = slot;
                break;
            }

            nonEmptySlots++;
            ChatUtils.debug("第 %d 格 %s 不符合条件", slot.index, InventoryUtils.describe(stack));
            if (explain != null) {
                String reason = explain.apply(stack);
                if (reason != null) nearMiss.add(reason);
            }
        }

        if (source == null) {
            found = false;
            ChatUtils.debug("%s", notFoundReason());
            finish();
            return;
        }

        Slot target = InventoryUtils.hotbarSlot(session.handler(), hotbarIndex);
        if (target == null) {
            fail("找不到快捷栏第 " + (hotbarIndex + 1) + " 格");
            return;
        }
        if (!target.getItem().isEmpty()) {
            fail("快捷栏第 " + (hotbarIndex + 1) + " 格被 " + InventoryUtils.describe(target) + " 占了，先腾出来");
            return;
        }

        expected = source.getItem().copy();
        ChatUtils.debug("选中第 %d 格：%s → 快捷栏第 %d 格（%s）",
            source.index, InventoryUtils.describe(expected), hotbarIndex + 1, InventoryUtils.describe(target));
        click(source, 0, ContainerInput.PICKUP, "拿起 " + InventoryUtils.describe(expected));
        phase = Phase.PICK;
    }

    private void tickPick() {
        ItemStack cursor = session.handler().getCarried();

        if (pendingClick) {
            if (!cursor.isEmpty() && ItemStack.isSameItemSameComponents(cursor, expected)) {
                pendingClick = false;
                stepWaitTicks = 0;
                expected = cursor.copy();
                click(InventoryUtils.hotbarSlot(session.handler(), hotbarIndex), 0, ContainerInput.PICKUP,
                    "放进快捷栏第 " + (hotbarIndex + 1) + " 格");
                phase = Phase.DROP;
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("拿起源格物品失败：光标上是 " + InventoryUtils.describe(cursor)
                    + "，期望 " + InventoryUtils.describe(expected));
                return;
            }
            return;
        }

        click(source, 0, ContainerInput.PICKUP, "拿起源格整叠");
    }

    private void tickDrop() {
        Slot target = InventoryUtils.hotbarSlot(session.handler(), hotbarIndex);

        if (pendingClick) {
            if (session.handler().getCarried().isEmpty()) {
                pendingClick = false;
                stepWaitTicks = 0;
                if (target == null || target.getItem().isEmpty()) {
                    fail("东西没放进快捷栏第 " + (hotbarIndex + 1) + " 格");
                    return;
                }
                taken = target.getItem().copy();
                found = true;
                output().info("已取出 " + InventoryUtils.describe(taken)
                    + " 放进快捷栏第 " + (hotbarIndex + 1) + " 格");
                phase = Phase.DONE;
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("放进快捷栏失败：光标上还有 " + InventoryUtils.describe(session.handler().getCarried()));
                return;
            }
            return;
        }

        click(target, 0, ContainerInput.PICKUP, "放进快捷栏第 " + (hotbarIndex + 1) + " 格");
    }

    // ------------------------------------------------------------------

    /** 点一下，然后等结果。 */
    private void click(Slot slot, int button, ContainerInput type, String action) {
        if (!session.click(slot, button, type, action)) {
            fail("点不了格子（" + action + "）");
            return;
        }
        clicks++;
        pendingClick = true;
        stepWaitTicks = 0;
    }
}
