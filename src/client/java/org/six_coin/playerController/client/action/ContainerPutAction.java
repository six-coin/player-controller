package org.six_coin.playerController.client.action;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * {@code /pc container put to_id|to_position ... all_items|all_shulker_boxes|everything|everything_include_hotbar}
 * 的执行体：把物品栏里的物品 / 潜影盒放进指定容器。
 *
 * <p>放哪几格由 {@link Mode} 决定：默认只动**主背包 27 格（不含快捷栏）**；
 * {@link Mode#EVERYTHING_INCLUDE_HOTBAR} 连快捷栏一起放（备货第一部分清空物品栏用）；
 * {@link Mode#HOTBAR_RANGE} 只放快捷栏连续几格（见 {@link #hotbarRange}）。
 *
 * <p>流程（按需求）：
 * <ol>
 *   <li>物品栏里没有这类东西 → 直接 {@code all_cleared = true}，容器都不用开；</li>
 *   <li>循环：对物品栏里一处匹配的格子 Shift 点击（QuickMove）；连着几次点击都没搬走东西
 *       → 容器满了、{@code all_cleared = false} 结束；物品栏里没有匹配的槽位了 → {@code all_cleared = true}。</li>
 * </ol>
 *
 * <p>「容器满没满」**只认服务端有没有真的搬走东西**，不拿界面里的空位去猜：
 * 空着几格和实际能不能收下这一叠不一定对得上（能不能收下由服务端算），猜错了会白白换容器 / 换盒子。
 * 所以每一处都先点一下看结果；哪一格点不动就换别的格子试，**每一格都试过、都搬不动**才算真的满了。
 *
 * <p>一 tick 一下点击，点完等两 tick 再看物品栏里这类东西的总数有没有变少。
 */
public class ContainerPutAction extends Action {

    /** 放什么。 */
    public enum Mode {
        /** 所有物品（潜影盒不算物品）。 */
        ALL_ITEMS,
        /** 所有潜影盒。 */
        ALL_SHULKER_BOXES,
        /** 什么都放（物品 + 潜影盒），只动主背包 27 格。 */
        EVERYTHING,
        /** 只放 item_list 还要的东西（备货阶段3 往暂存盒里放成品用，见构造器里的 wanted）。 */
        WANTED,
        /** 什么都放，**连快捷栏也放**（备货阶段1 把物品栏清空用）。 */
        EVERYTHING_INCLUDE_HOTBAR,
        /** 只放快捷栏连续几格（备货阶段3 把合成出来的成品倒进暂存盒用，见 {@link #hotbarRange}）。 */
        HOTBAR_RANGE
    }

    /** 主背包（27 格，不含快捷栏）在玩家背包里的起始下标。 */
    private static final int MAIN_START = InventoryUtils.MAIN_START;

    /** 打开界面之后等几 tick 再动。 */
    private static final int SETTLE_TICKS = 2;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    private enum Phase {
        OPENING,
        PUT,
        DONE
    }

    private final BlockPos pos;
    private final Mode mode;
    /** 要不要把「放完之后容器里剩什么」也带出来（备货流程用；命令输出不受影响）。 */
    private final boolean wantDetail;
    /** 只有 {@link Mode#WANTED} 用：要放的东西（item_list 里还要的才算）。 */
    @Nullable
    private final ItemList wanted;
    /** 只有 {@link Mode#HOTBAR_RANGE} 用：源格范围（玩家背包下标，含两端）。 */
    private final int hotbarFrom;
    private final int hotbarTo;

    private final ContainerSession session;
    private Phase phase = Phase.OPENING;
    private int settleTicks;
    private int totalTicks;
    private int moved;
    private int beforeCount;
    /** 上一次点击点的是哪一格（{@code Slot.id}），验证有没有搬动时用。 */
    private int lastSourceId = -1;
    /** 点过、而且一点都没搬动的源格：换别的格子试，全都试过才算容器满了。 */
    private final Set<Integer> triedNoMove = new HashSet<>();
    private boolean allCleared;
    private boolean containerFull;
    private int freeSlots = -1;
    private ContainerCacheManager.Breakdown detail;

    public ContainerPutAction(BlockPos pos, Mode mode) {
        this(pos, mode, false);
    }

    public ContainerPutAction(BlockPos pos, Mode mode, boolean wantDetail) {
        this(pos, mode, wantDetail, null);
    }

    /**
     * @param wanted 只有 {@link Mode#WANTED} 用：只放「这个 item_list 还要的」物品
     */
    public ContainerPutAction(BlockPos pos, Mode mode, boolean wantDetail, @Nullable ItemList wanted) {
        this(pos, mode, wantDetail, wanted, 0, 0);
    }

    private ContainerPutAction(BlockPos pos, Mode mode, boolean wantDetail, @Nullable ItemList wanted,
                               int hotbarFrom, int hotbarTo) {
        this.pos = pos.toImmutable();
        this.mode = mode;
        this.wantDetail = wantDetail;
        this.wanted = wanted;
        this.hotbarFrom = Math.min(hotbarFrom, hotbarTo);
        this.hotbarTo = Math.max(hotbarFrom, hotbarTo);
        this.session = new ContainerSession(this.pos);
    }

    /**
     * 只放快捷栏连续几格（含两端）里的东西。
     *
     * <p>往最终产物盒里放成品走这一条：一路 QuickMove，放哪儿、能放多少都由服务端决定，
     * 盒子是不是真的满也由它说了算。
     */
    public static ContainerPutAction hotbarRange(BlockPos pos, int from, int to, boolean wantDetail) {
        return new ContainerPutAction(pos, Mode.HOTBAR_RANGE, wantDetail, null, from, to);
    }

    @Override
    public String name() {
        return "放入容器 " + pos.toShortString() + "（" + modeName() + "）";
    }

    /** 结果：物品栏里这类东西是不是都放进去了。 */
    public boolean allCleared() {
        return allCleared;
    }

    /** 容器是不是一格空位都没有了（wantDetail 时才有意义）。 */
    public boolean containerFull() {
        return containerFull;
    }

    /** 放完之后容器里剩什么（wantDetail 时才有意义；散装物品 + 每个潜影盒的内容）。 */
    public ContainerCacheManager.Breakdown detail() {
        return detail;
    }

    /** 放完之后容器还剩几个空格（wantDetail 时才有意义）。 */
    public int freeSlots() {
        return freeSlots;
    }

    private String modeName() {
        return switch (mode) {
            case ALL_ITEMS -> "all_items";
            case ALL_SHULKER_BOXES -> "all_shulker_boxes";
            case EVERYTHING -> "everything";
            case WANTED -> "item_list 里还要的东西";
            case EVERYTHING_INCLUDE_HOTBAR -> "everything_include_hotbar";
            case HOTBAR_RANGE -> rangeName();
        };
    }

    /** 这个模式动不动快捷栏。 */
    public boolean includesHotbar() {
        return mode == Mode.EVERYTHING_INCLUDE_HOTBAR;
    }

    /** 要动的背包下标从哪个开始。 */
    private int rangeStart() {
        if (mode == Mode.HOTBAR_RANGE) return hotbarFrom;
        return includesHotbar() ? 0 : MAIN_START;
    }

    /** 要动的背包下标到哪儿结束（不含）。 */
    private int rangeEnd() {
        if (mode == Mode.HOTBAR_RANGE) return hotbarTo + 1;
        return InventoryUtils.INVENTORY_END;
    }

    /** 一句话说清楚这个模式管哪几格。 */
    private String rangeName() {
        if (mode == Mode.HOTBAR_RANGE) {
            return hotbarFrom == hotbarTo
                ? "快捷栏第 " + (hotbarFrom + 1) + " 格"
                : "快捷栏第 " + (hotbarFrom + 1) + "~" + (hotbarTo + 1) + " 格";
        }
        return includesHotbar() ? "整个物品栏（27 格主背包 + 快捷栏 9 格）"
            : "物品栏（27 格，不含快捷栏）";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("没有玩家或世界");
            return;
        }

        // 第 1 步：物品栏里没有这类东西就别开容器了
        if (countInInventory(player) == 0) {
            allCleared = true;
            output().info(rangeName() + "里没有" + modeName() + "，没什么可放的");
            finish();
            return;
        }

        ChatUtils.debug("放入模式 %s：%s 里有 %d 个要放的",
            modeName(), rangeName(), countInInventory(player));
        session.open(mc, player);
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

        if (phase != Phase.OPENING && session.isOpen() && !session.stillOpen(mc)) {
            fail("容器界面被关掉了");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(mc);
            case PUT -> tickPut(player);
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();

        // 先趁界面还开着，把「放完之后容器里剩什么 / 满没满 / 还剩几格」记下来（备货流程要用）
        if (wantDetail && session.isOpen()) {
            detail = session.breakdown();
            freeSlots = session.freeSlots();
            containerFull = freeSlots == 0;
        }

        session.close(mc);

        if (failureReason() != null) {
            allCleared = false;
        }

        JsonObject result = new JsonObject();
        result.addProperty("all_cleared", allCleared);

        ChatUtils.debug("放物结果：all_cleared=%s，一共搬了 %d 次", allCleared, moved);
        output().info("放物结果：all_cleared=" + allCleared + (allCleared
            ? "（" + rangeName() + "里要放的都放进去了）"
            : "（没放完：容器满了）"));
        output().rawCopyable(result.toString());
    }

    // ------------------------------------------------------------------

    private void tickOpening(MinecraftClient mc) {
        switch (session.awaitOpen(mc)) {
            case READY -> phase = Phase.PUT;
            case TIMEOUT -> fail("等待容器界面超时");
            case WAITING -> {
            }
        }
    }

    private void tickPut(ClientPlayerEntity player) {
        // 上一次点击还在等结果
        if (settleTicks > 0) {
            settleTicks--;
            if (settleTicks == 0) verifyMove(player);
            return;
        }

        // 物品栏里没有这类东西了 → 放完了
        if (countInInventory(player) == 0) {
            allCleared = true;
            ChatUtils.debug("物品栏里没有" + modeName() + "了，放完了");
            phase = Phase.DONE;
            return;
        }

        Slot source = findSource();
        if (source == null) {
            if (triedNoMove.isEmpty()) {
                allCleared = true;
                ChatUtils.debug("物品栏里找不到能放的格子了，放完了");
            } else {
                // 每一格都点过了，一点都搬不动：容器是真的满了
                allCleared = false;
                ChatUtils.debug("能试的 " + triedNoMove.size() + " 格都点过了、一点都没搬动，容器满了");
            }
            phase = Phase.DONE;
            return;
        }

        beforeCount = countInInventory(player);
        lastSourceId = source.id;
        if (!session.quickMove(source, "放进容器 " + InventoryUtils.describe(source.getStack()))) {
            fail("点不了格子（放进容器）");
            return;
        }
        moved++;
        settleTicks = SETTLE_TICKS;
    }

    /**
     * 点完等一下：物品栏里这类东西有没有变少。
     *
     * <p>没变少就说明**这一格**服务端搬不动（容器里放不下这一叠），换别的格子接着试；
     * 每一格都试过、都搬不动，才算容器满了。
     */
    private void verifyMove(ClientPlayerEntity player) {
        int after = countInInventory(player);
        if (after < beforeCount) {
            // 搬动了：容器还有地方，之前试不动的格子过会儿可以再试
            triedNoMove.clear();
            return;
        }

        triedNoMove.add(lastSourceId);
        ChatUtils.debug("这一下 QuickMove 没搬走东西（" + beforeCount + " → " + after + "，slot="
            + lastSourceId + "），换别的格子试");
    }

    // ------------------------------------------------------------------

    /** 要动的那几格里有这类东西一共有多少。 */
    private int countInInventory(ClientPlayerEntity player) {
        int total = 0;
        for (int i = rangeStart(); i < rangeEnd(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty() || !matches(stack)) continue;
            total += stack.getCount();
        }
        return total;
    }

    /** 要动的那几格里下一个（还没试过、或者试过但搬得动的）匹配格子。 */
    @Nullable
    private Slot findSource() {
        for (Slot slot : session.handler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            if (slot.getIndex() < rangeStart() || slot.getIndex() >= rangeEnd()) continue;
            if (triedNoMove.contains(slot.id)) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !matches(stack)) continue;
            return slot;
        }
        return null;
    }

    /** 这一叠算不算「要放的东西」：潜影盒不算物品，everything 什么都算，wanted 只认 item_list。 */
    private boolean matches(ItemStack stack) {
        if (mode == Mode.HOTBAR_RANGE) return true;
        if (mode == Mode.WANTED) return wanted != null && wanted.wants(stack.getItem());
        if (mode == Mode.EVERYTHING || mode == Mode.EVERYTHING_INCLUDE_HOTBAR) return true;

        boolean shulker = ShulkerUtils.isShulkerBox(stack);
        return mode == Mode.ALL_SHULKER_BOXES ? shulker : !shulker;
    }
}
