package org.six_coin.playerController.client.action;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.container.ContainerCacheUpdater;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.ShulkerUtils;

/**
 * {@code /pc container put to_id|to_position ... all_items|all_shulker_boxes} 的执行体：
 * 把主背包 27 格（不含快捷栏）里的物品 / 潜影盒全部放进指定容器。
 *
 * <p>流程（按需求）：
 * <ol>
 *   <li>物品栏里没有这类东西 → 直接 {@code all_cleared = true}，容器都不用开；</li>
 *   <li>循环：容器收不下了 → {@code all_cleared = false} 结束；否则对物品栏里一处匹配的物品
 *       QuickMove（shift 点击）；物品栏里没有匹配的槽位了 → {@code all_cleared = true}。</li>
 * </ol>
 *
 * <p>一 tick 一下点击，点完等两 tick 再看物品栏里这类东西的总数有没有变少：
 * 连续几次都没搬走东西，就当容器满了。
 */
public class ContainerPutAction extends Action {

    /** 放什么。 */
    public enum Mode {
        /** 所有物品（潜影盒不算物品）。 */
        ALL_ITEMS,
        /** 所有潜影盒。 */
        ALL_SHULKER_BOXES,
        /** 什么都放（物品 + 潜影盒）。 */
        EVERYTHING,
        /** 只放 item_list 还要的东西（备货阶段3 往暂存盒里放成品用，见构造器里的 wanted）。 */
        WANTED
    }

    /** 打开界面之后等几 tick 再动。 */
    private static final int SETTLE_TICKS = 2;

    private static final int MAX_OPEN_WAIT_TICKS = 60;

    private static final int MAX_TOTAL_TICKS = 20 * 600;

    /** 连续这么多次点击都没搬走东西，就当容器满了。 */
    private static final int MAX_STRIKES = 3;

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
    private final ItemList wanted;

    private ScreenHandler handler;
    private Phase phase = Phase.OPENING;
    private int openWaitTicks;
    private int settleTicks;
    private int totalTicks;
    private int strikes;
    private int moved;
    private int beforeCount;
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
        this.pos = pos;
        this.mode = mode;
        this.wantDetail = wantDetail;
        this.wanted = wanted;
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
        };
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        ScreenSuppressor.acquire();

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("没有玩家或世界");
            return;
        }

        // 第 1 步：物品栏里没有这类东西就别开容器了
        if (countInInventory(player) == 0) {
            allCleared = true;
            ChatUtils.info("物品栏（27 格，不含快捷栏）里没有" + modeName() + "，没什么可放的");
            finish();
            return;
        }

        ChatUtils.debug("放入模式 %s：物品栏里有 %d 个要放的", modeName(), countInInventory(player));
        ContainerOpener.open(mc, player, pos);
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

        if (handler != null && player.currentScreenHandler != handler) {
            fail("界面被关掉了");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(mc, player);
            case PUT -> tickPut(mc, player);
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        // 先趁界面还开着，把「放完之后容器里剩什么 / 满没满 / 还剩几格」记下来（备货流程要用）
        if (wantDetail && handler != null) {
            detail = ContainerCacheManager.breakdown(handler);
            freeSlots = countFreeSlots();
            containerFull = freeSlots == 0;
        }

        // 放完顺手把容器缓存刷新一下（这个容器在缓存里的话）
        if (handler != null) {
            ContainerCacheUpdater.refresh(MinecraftClient.getInstance(), pos, handler);
        }
        closeScreen();
        ScreenSuppressor.release();

        if (failureReason() != null) {
            allCleared = false;
            ChatUtils.error(name() + "失败：" + failureReason());
        }

        JsonObject result = new JsonObject();
        result.addProperty("all_cleared", allCleared);

        ChatUtils.debug("放物结果：all_cleared=%s，一共搬了 %d 次", allCleared, moved);
        ChatUtils.info("放物结果：all_cleared=" + allCleared + (allCleared
            ? "（物品栏里的" + modeName() + "都放进去了）"
            : "（没放完：容器满了）"));
        ChatUtils.rawCopyable(result.toString());
    }

    // ------------------------------------------------------------------

    private void tickOpening(MinecraftClient mc, ClientPlayerEntity player) {
        openWaitTicks++;
        ScreenHandler current = player.currentScreenHandler;

        if (current != null && current != player.playerScreenHandler
            && !ContainerCacheTracker.isCreativeInventory(mc)) {
            handler = current;
            phase = Phase.PUT;
            settleTicks = 0;
            ChatUtils.debug("容器界面已打开：" + current.getClass().getSimpleName());
            return;
        }

        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("等待容器界面超时（" + openWaitTicks + " tick）");
        }
    }

    private void tickPut(MinecraftClient mc, ClientPlayerEntity player) {
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
            allCleared = true;
            ChatUtils.debug("物品栏里找不到能放的格子了，放完了");
            phase = Phase.DONE;
            return;
        }

        // 容器满了？
        if (!containerCanAccept(source.getStack())) {
            allCleared = false;
            ChatUtils.debug("容器装不下了（没有格子能收下 " + describe(source.getStack()) + "），结束");
            phase = Phase.DONE;
            return;
        }

        beforeCount = countInInventory(player);
        mc.interactionManager.clickSlot(handler.syncId, source.id, 0, SlotActionType.QUICK_MOVE, player);
        moved++;
        settleTicks = SETTLE_TICKS;
    }

    /** 点完等一下：物品栏里这类东西有没有变少。 */
    private void verifyMove(ClientPlayerEntity player) {
        int after = countInInventory(player);
        if (after < beforeCount) {
            strikes = 0;
            return;
        }

        strikes++;
        ChatUtils.debug("这一下 QuickMove 没搬走东西（" + beforeCount + " → " + after + "），第 "
            + strikes + "/" + MAX_STRIKES + " 次");
        if (strikes >= MAX_STRIKES) {
            allCleared = false;
            ChatUtils.debug("连续 " + strikes + " 次没搬动，当容器满了");
            phase = Phase.DONE;
        }
    }

    // ------------------------------------------------------------------

    /** 主背包 27 格（不含快捷栏）里这类东西一共有多少。 */
    private int countInInventory(ClientPlayerEntity player) {
        int total = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty() || !matches(stack)) continue;
            total += stack.getCount();
        }
        return total;
    }

    /** 主背包 27 格里第一个匹配的格子。 */
    @Nullable
    private Slot findSource() {
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            if (slot.getIndex() < 9 || slot.getIndex() >= 36) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !matches(stack)) continue;
            return slot;
        }
        return null;
    }

    /** 这一叠算不算「要放的东西」：潜影盒不算物品，everything 什么都算，wanted 只认 item_list。 */
    private boolean matches(ItemStack stack) {
        if (mode == Mode.WANTED) return wanted != null && wanted.wants(stack.getItem());
        if (mode == Mode.EVERYTHING) return true;

        boolean shulker = ShulkerUtils.isShulkerBox(stack);
        return mode == Mode.ALL_SHULKER_BOXES ? shulker : !shulker;
    }

    /** 容器的格子里有没有能收下这一叠的（空格，或者同类还有余量的格子）。 */
    private boolean containerCanAccept(ItemStack stack) {
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            if (!slot.canInsert(stack)) continue;

            ItemStack existing = slot.getStack();
            if (existing.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(existing, stack)
                && existing.getCount() < Math.min(slot.getMaxItemCount(stack), stack.getMaxCount())) {
                return true;
            }
        }
        return false;
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;
        mc.player.closeHandledScreen();
    }

    /** 容器还剩几个空格。 */
    private int countFreeSlots() {
        if (handler == null) return -1;
        int free = 0;
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            if (slot.getStack().isEmpty()) free++;
        }
        return free;
    }

    private static String describe(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
