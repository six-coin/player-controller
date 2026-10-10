package org.six_coin.playerController.client.action;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheUpdater;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.ItemList;
import org.six_coin.playerController.client.util.ItemRules;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.List;

/**
 * {@code /pc container get from_target <item_list>} 的执行体。
 *
 * <p>流程：
 * <ol>
 *   <li>看向玩家准星指着的方块、右键打开（客户端界面用 {@link ScreenSuppressor} 挡住不显示）；</li>
 *   <li>逐个遍历容器的格子：
 *     <ul>
 *       <li><b>潜影盒</b>：盒子里有 item_list 还要的东西 → 整个盒子搬走；</li>
 *       <li><b>普通物品</b>：搬 {@code min(这一格的数量, item_list 还要的数量)} 个；</li>
 *     </ul>
 *   </li>
 *   <li>每次提取前先看主背包（27 格，不含快捷栏）有没有空格，没有就停下；</li>
 *   <li>提取成功后扣 item_list：普通物品按个数扣；潜影盒先解析盒子里装的东西再逐个扣，
 *       每个物品最多扣到 0，不会扣成负数。</li>
 * </ol>
 *
 * <p>不看的东西（见 {@link ItemRules}）：堆叠上限 1 的物品、改过名字（custom_name）的物品。
 * 潜影盒自己也是堆叠上限 1 的物品，但它走潜影盒那条规则；盒子要是改过名字，一样无视。
 *
 * <p><b>绝对不碰快捷栏</b>：不用 shift 点击（{@code QUICK_MOVE}，服务端会往快捷栏塞），
 * 所有往背包放的操作都只点主背包 27 格。
 *
 * <p>点击方式：一 tick 最多一次点击，点完等本地状态变成预期值再继续（客户端点击是本地预测的，
 * 正常情况下下一 tick 就能往下走；等太久就报错停下，日志里能看到卡在哪一步）。
 * 整叠用「拿起整叠 → 左键点主背包格（同类半叠优先，其次空格）直到光标空」；
 * 只取一部分用「拿起整叠 → 右键往目标格放 N 个 → 剩下的放回原格」。
 */
public class ContainerGetAction extends Action {

    private enum Phase {
        /** 等容器界面打开。 */
        OPENING,
        /** 找下一个能提取的容器格。 */
        SCAN,
        /** 把源格整叠拿到光标上。 */
        PICKUP,
        /** 右键往主背包目标格放，一次放 1 个（只取一部分时用）。 */
        PLACE,
        /** 把光标上剩下的放回源格。 */
        RETURN,
        /** 整叠往主背包里放：左键点主背包格，直到光标空。 */
        DUMP,
        /** only_one_shulker 专用：找一个有需要的潜影盒，拿走并放进快捷栏第三格。 */
        ONE_BOX
    }

    /** 一次点击最多等这么多 tick 生效。 */
    private static final int MAX_STEP_WAIT_TICKS = 60;

    /**
     * 「一个一个放」（右键往目标格放 1 个）时，一个 tick 里最多连点几下。
     *
     * <p>客户端点击是**本地预测、同步生效**的（{@code clickSlot} 内部就会改本地状态），
     * 所以没必要点一下等一个 tick —— 一个 tick 连点几下能快好几倍。
     * 每一批点完下一 tick 会按「光标少了多少」核对一次，服务端少接受几下也能自己纠正。
     */
    private static final int PLACE_CLICKS_PER_TICK = 8;

    /** 等容器界面打开最久。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;

    /** 整个任务最久（20 tick = 1 秒）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 300;

    /** 取货模式。 */
    public enum Mode {
        /** 正常：item_list 要的物品和潜影盒都拿。 */
        NORMAL,
        /** only_item：只拿物品，无视潜影盒（盒子里的东西也不算）。 */
        ONLY_ITEM,
        /** only_one_shulker：只找一个「里面有 item_list 需要的东西」的潜影盒，拿走并放进快捷栏第三格；不返回 item_list。 */
        ONLY_ONE_SHULKER
    }

    private final BlockPos pos;
    private final ItemList itemList;
    private final Mode mode;

    /** 一开始的 item_list 文本，给任务名字用（跑完以后 item_list 会被扣减，不适合再拿来显示）。 */
    private final String requestText;

    private Phase phase = Phase.OPENING;

    private ScreenHandler handler;
    private PlayerInventory playerInventory;
    private List<Slot> containerSlots = List.of();

    private int slotCursor;
    private int openWaitTicks;
    private int totalTicks;
    private int stepWaitTicks;
    private int extractions;
    private boolean pendingClick;
    private String stopReason;

    /** 整个容器的格子都看完了。 */
    private boolean scannedAll;

    /** 是因为物品栏（27 格主背包）放不下才停的。 */
    private boolean stoppedForInventory;

    /** 要不要把「取完之后容器里剩什么」带出来（备货流程用）。 */
    private final boolean wantDetail;
    private ContainerCacheManager.Breakdown detail;
    private int freeSlots = -1;

    /** only_one_shulker 用的状态。 */
    public static final int HOTBAR_BOX_INDEX = 2;
    private int oneBoxStep;
    private int boxSlotId = -1;

    // 当前这次提取
    private Slot source;
    private ItemStack sourceStack;
    private int sourceCount;
    private int take;
    private int placed;
    private Slot target;
    /** 发点击之前光标上有多少个，用来判断这次点击有没有生效。 */
    private int clickCursorCount;

    public ContainerGetAction(BlockPos pos, ItemList itemList) {
        this(pos, itemList, false, Mode.NORMAL);
    }

    /**
     * @param wantDetail 要不要把「取完之后容器里剩什么」也带出来（备货流程用；命令输出不受影响）
     */
    public ContainerGetAction(BlockPos pos, ItemList itemList, boolean wantDetail) {
        this(pos, itemList, wantDetail, Mode.NORMAL);
    }

    public ContainerGetAction(BlockPos pos, ItemList itemList, boolean wantDetail, Mode mode) {
        this.pos = pos.toImmutable();
        this.itemList = itemList;
        this.requestText = itemList.describe();
        this.wantDetail = wantDetail;
        this.mode = mode;
    }

    /** 取完之后容器里剩什么（wantDetail 时才有意义；散装物品 + 每个潜影盒的内容）。 */
    public ContainerCacheManager.Breakdown detail() {
        return detail;
    }

    /** 取完之后容器还剩几个空格（wantDetail 时才有意义）。 */
    public int freeSlots() {
        return freeSlots;
    }

    @Override
    public String name() {
        return "从容器 " + pos.toShortString() + " 取 " + requestText;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        ScreenSuppressor.acquire();

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (!(mc.world.getBlockEntity(pos) instanceof Inventory)) {
            fail(pos.toShortString() + " 不是容器（没有物品栏）");
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("容器 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }
        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        if (!cursor.isEmpty()) {
            fail("光标上还有物品（" + describeStack(cursor) + "），先放下再执行");
            return;
        }
        if (itemList.isEmpty()) {
            fail("item_list 里没有还需要的东西");
            return;
        }

        ChatUtils.debug("容器任务开始：目标 %s（%s），item_list = %s",
            pos.toShortString(),
            Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()),
            itemList.toJson());

        openContainer(mc, player);
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

        if (phase != Phase.OPENING && handler != null && player.currentScreenHandler != handler) {
            fail("容器界面被关掉了");
            return;
        }

        switch (phase) {
            case OPENING -> tickOpening(player);
            case SCAN -> tickScan();
            case PICKUP -> tickPickup();
            case PLACE -> tickPlace();
            case RETURN -> tickReturn();
            case DUMP -> tickDump();
            case ONE_BOX -> tickOneBox(player);
        }
    }

    @Override
    protected void onEnd() {
        // 先趁界面还开着，把「取完之后容器里剩什么 / 还剩几格」记下来（备货流程要用）
        if (wantDetail && handler != null) {
            detail = ContainerCacheManager.breakdown(handler);
            freeSlots = 0;
            for (Slot slot : containerSlots) {
                if (slot.getStack().isEmpty()) freeSlots++;
            }
        }

        // 拿完东西以后，这个容器要是已经在缓存里，就把缓存里的物品列表刷新一遍
        if (handler != null) {
            ContainerCacheUpdater.refresh(MinecraftClient.getInstance(), pos, handler);
        }
        closeScreen();

        if (stopReason != null) {
            ChatUtils.info("容器任务结束：" + stopReason);
        }

        // only_one_shulker 没有返回值，前面已经报过了
        if (mode == Mode.ONLY_ONE_SHULKER) {
            ChatUtils.debug("only_one_shulker 模式：不输出 item_list / all_cleared");
            ScreenSuppressor.release();
            return;
        }

        boolean allCleared = allCleared();
        String itemListJson = itemList.toJson();
        JsonObject result = new JsonObject();
        result.add("item_list", JsonParser.parseString(itemListJson));
        result.addProperty("all_cleared", allCleared);

        ChatUtils.debug("一共提取了 %d 次；item_list = %s；all_cleared = %s",
            extractions, itemListJson, allCleared);
        ChatUtils.info("取物结果：all_cleared=" + allCleared + (allCleared
            ? "（容器里能拿的都拿完了）"
            : "（没拿完：多半是物品栏满了，item_list 还差 " + itemList.describe() + "）"));

        // 指令跑完之后输出结果：item_list + all_cleared
        ChatUtils.rawCopyable(result.toString());

        ScreenSuppressor.release();
    }

    /**
     * 容器里能拿的都拿完了没有。
     *
     * <p>判断方式：整个容器都看完了，或者 item_list 已经满足了，而且不是「因为物品栏放不下」才停的。
     */
    public boolean allCleared() {
        return !stoppedForInventory && (scannedAll || itemList.isEmpty());
    }

    // ------------------------------------------------------------------
    // 打开容器
    // ------------------------------------------------------------------

    private void openContainer(MinecraftClient mc, ClientPlayerEntity player) {
        ContainerOpener.open(mc, player, pos);
        openWaitTicks = 0;
        phase = Phase.OPENING;
    }

    private void tickOpening(ClientPlayerEntity player) {
        openWaitTicks++;
        ScreenHandler current = player.currentScreenHandler;
        if (current != null && current != player.playerScreenHandler) {
            handler = current;
            playerInventory = player.getInventory();
            containerSlots = InventoryUtils.containerSlots(handler);
            slotCursor = 0;
            pendingClick = false;
            stepWaitTicks = 0;
            phase = mode == Mode.ONLY_ONE_SHULKER ? Phase.ONE_BOX : Phase.SCAN;
            ChatUtils.debug("容器已打开：syncId=%d，界面共 %d 格，其中容器格 %d 格",
                handler.syncId, handler.slots.size(), containerSlots.size());
            dumpContainerSlots();
            return;
        }
        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("等待容器界面超时（" + openWaitTicks + " tick）");
        }
    }

    /**
     * only_one_shulker 的流程：找一个「里面有 item_list 需要的东西」的潜影盒 → 拿走 →
     * 放进快捷栏第三格（背包下标 2）。一 tick 一下点击，没有返回值。
     */
    private void tickOneBox(ClientPlayerEntity player) {
        switch (oneBoxStep) {
            case 0 -> {
                boxSlotId = findShulkerWithNeeded();
                if (boxSlotId < 0) {
                    fail("容器里没有「装着 item_list 需要的东西」的潜影盒");
                    return;
                }
                ItemStack box = handler.slots.get(boxSlotId).getStack();
                ChatUtils.debug("选中第 %d 格的潜影盒（内容：%s）", boxSlotId,
                    ShulkerUtils.describeContents(box));
                oneBoxStep = 1;
            }
            case 1 -> {
                click(handler.slots.get(boxSlotId), 0, SlotActionType.PICKUP, "拿起潜影盒");
                oneBoxStep = 2;
            }
            case 2 -> {
                if (handler.getCursorStack().isEmpty()) {
                    fail("潜影盒没拿起来");
                    return;
                }
                oneBoxStep = 3;
            }
            case 3 -> {
                Slot target = hotbarSlot(HOTBAR_BOX_INDEX);
                if (target == null) {
                    fail("找不到快捷栏第 " + (HOTBAR_BOX_INDEX + 1) + " 格");
                    return;
                }
                if (!target.getStack().isEmpty()) {
                    fail("快捷栏第 " + (HOTBAR_BOX_INDEX + 1) + " 格被 " + InventoryUtils.describe(target) + " 占了");
                    return;
                }
                click(target, 0, SlotActionType.PICKUP, "把潜影盒放进快捷栏");
                oneBoxStep = 4;
            }
            default -> {
                if (!handler.getCursorStack().isEmpty()) {
                    fail("潜影盒没放进快捷栏");
                    return;
                }
                ChatUtils.info("已取出一个潜影盒并放进快捷栏第 " + (HOTBAR_BOX_INDEX + 1) + " 格");
                finish();
            }
        }
    }

    /** 找一个里面有 item_list 需要的东西、而且没改过名字的潜影盒。 */
    private int findShulkerWithNeeded() {
        for (Slot slot : containerSlots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !ShulkerUtils.isShulkerBox(stack)) continue;
            if (ItemRules.customNameOf(stack) != null) continue;
            if (!ShulkerUtils.containsWanted(stack, itemList)) continue;
            return slot.id;
        }
        return -1;
    }

    /** 玩家快捷栏某一格（0~8，也就是背包下标）在界面里的 Slot。 */
    @Nullable
    private Slot hotbarSlot(int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) return slot;
        }
        return null;
    }

    /** 打开之后先把容器里都有什么打一遍日志（每行 5 格，免得一行太长）。 */
    private void dumpContainerSlots() {
        ChatUtils.debug("容器格一览：");
        StringBuilder line = new StringBuilder();
        int count = 0;
        for (Slot slot : containerSlots) {
            if (line.length() > 0) line.append("　");
            line.append(InventoryUtils.describe(slot));
            if (++count % 5 == 0) {
                ChatUtils.debug("　%s", line);
                line.setLength(0);
            }
        }
        if (line.length() > 0) ChatUtils.debug("　%s", line);
    }

    // ------------------------------------------------------------------
    // 找下一个要提取的格子
    // ------------------------------------------------------------------

    private void tickScan() {
        pendingClick = false;
        stepWaitTicks = 0;
        source = null;
        sourceStack = null;
        target = null;

        if (itemList.isEmpty()) {
            stop("item_list 已经全部拿到");
            return;
        }

        while (slotCursor < containerSlots.size()) {
            Slot slot = containerSlots.get(slotCursor);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                slotCursor++;
                continue;
            }

            // ① 潜影盒：盒子里有要的东西就整个搬走。
            //    潜影盒自己也是堆叠上限 1 的物品，所以它先走这条规则；
            //    不过盒子要是改过名字，按「改过名字的一律无视」处理。
            if (ShulkerUtils.isShulkerBox(stack)) {
                if (mode == Mode.ONLY_ITEM) {
                    ChatUtils.debug("第 %d 格是潜影盒，但这次是 only_item（只拿物品），跳过", slot.id);
                    slotCursor++;
                    continue;
                }
                String boxName = ItemRules.customNameOf(stack);
                if (boxName != null) {
                    ChatUtils.debug("第 %d 格是潜影盒但改过名字（%s），无视", slot.id, boxName);
                    slotCursor++;
                    continue;
                }
                if (!ShulkerUtils.containsWanted(stack, itemList)) {
                    ChatUtils.debug("第 %d 格是潜影盒，里面没有 item_list 还要的东西，跳过（内容：%s）",
                        slot.id, ShulkerUtils.describeContents(stack));
                    slotCursor++;
                    continue;
                }
                source = slot;
                sourceStack = stack.copy();
                sourceCount = stack.getCount();
                take = stack.getCount();
                ChatUtils.debug("第 %d 格是潜影盒 x%d，里面有要的东西（%s）→ 整个搬走",
                    slot.id, sourceCount, ShulkerUtils.describeContents(stack));
                break;
            }

            // ② 普通物品：堆叠上限 1 的、改过名字的都当没看见
            if (ItemRules.isIgnored(stack)) {
                ChatUtils.debug("第 %d 格 %s x%d 无视（%s）", slot.id,
                    Registries.ITEM.getId(stack.getItem()), stack.getCount(),
                    ItemRules.ignoreReason(stack));
                slotCursor++;
                continue;
            }

            int need = itemList.remaining(stack.getItem());
            if (need <= 0) {
                ChatUtils.debug("第 %d 格 %s x%d 不需要，跳过",
                    slot.id, Registries.ITEM.getId(stack.getItem()), stack.getCount());
                slotCursor++;
                continue;
            }
            source = slot;
            sourceStack = stack.copy();
            sourceCount = stack.getCount();
            take = Math.min(sourceCount, need);
            ChatUtils.debug("第 %d 格 %s x%d，item_list 还要 %d → 提取 %d 个（%s）",
                slot.id, Registries.ITEM.getId(stack.getItem()), sourceCount, need, take,
                take == sourceCount ? "整叠" : "只取一部分");
            break;
        }

        if (source == null) {
            scannedAll = true;
            stop("容器的格子都看完了，item_list 还差：" + itemList.describe());
            return;
        }

        // 每次提取之前都检查一次主背包有没有空位
        Slot empty = InventoryUtils.firstEmptyMainSlot(handler, playerInventory);
        if (empty == null) {
            stoppedForInventory = true;
            stop("主背包（27 格，不含快捷栏）没有空位了，停止；item_list 还差：" + itemList.describe());
            return;
        }

        if (take == sourceCount) {
            placed = 0;
            phase = Phase.PICKUP;
            ChatUtils.debug("搬法：整叠 %d 个 → 拿起后左键放进主背包"
                + "（同类半叠优先，其次空格；不会碰快捷栏；空位检查通过，第一个空格是 #%d）",
                take, empty.id);
            return;
        }

        // 只取一部分：优先塞进同类半叠，塞不下就放空格
        Slot merge = InventoryUtils.mergeTarget(handler, playerInventory, sourceStack, take);
        target = merge != null ? merge : empty;
        if (!target.canInsert(sourceStack)) {
            stoppedForInventory = true;
            stop("主背包里找不到能放下 " + describeStack(sourceStack)
                + " 的格子，停止；item_list 还差：" + itemList.describe());
            return;
        }
        placed = 0;
        phase = Phase.PICKUP;
        ChatUtils.debug("搬法：拿起整叠 → 右键往 #%d（%s）放 %d 个 → 剩下的放回原格（不会碰快捷栏）",
            target.id, merge != null ? "同类半叠" : "空格", take);
    }

    // ------------------------------------------------------------------
    // 提取的点击序列
    // ------------------------------------------------------------------

    private void tickPickup() {
        ItemStack cursor = handler.getCursorStack();

        if (pendingClick) {
            if (!cursor.isEmpty() && ItemStack.areItemsAndComponentsEqual(cursor, sourceStack)) {
                // 以实际拿到手的数量为准（本地预测和服务端对不上时也不会乱）
                if (cursor.getCount() != sourceCount) {
                    ChatUtils.debug("实际拿到手的是 %d 个（本来算的是 %d 个），按实际的来",
                        cursor.getCount(), sourceCount);
                    sourceCount = cursor.getCount();
                    take = Math.min(take, sourceCount);
                }
                pendingClick = false;
                stepWaitTicks = 0;
                placed = 0;
                if (take >= sourceCount) {
                    phase = Phase.DUMP;
                    ChatUtils.debug("整叠 %d 个已经在光标上了，准备放进主背包", sourceCount);
                } else {
                    phase = Phase.PLACE;
                    ChatUtils.debug("整叠 %d 个已经在光标上了（这次要放 %d 个）", sourceCount, take);
                }
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("拿起源格物品失败：光标上是 " + describeStack(cursor)
                    + "，期望 " + describeStack(sourceStack) + " x" + sourceCount);
                return;
            }
            return;
        }

        click(source, 0, SlotActionType.PICKUP, "拿起源格整叠");
    }

    private void tickPlace() {
        if (placed >= take) {
            finishPlacing();
            return;
        }

        ItemStack cursor = handler.getCursorStack();

        // 上一批点完了：光标少了多少就是实际放进去多少（服务端少接受几下的情况也能自愈）
        if (pendingClick) {
            int nowPlaced = sourceCount - cursor.getCount();
            if (nowPlaced != placed) {
                if (nowPlaced > placed) {
                    ChatUtils.debug("一批点完，实际放进去了 %d 个（本来算的是 %d 个），按实际的来",
                        nowPlaced, placed);
                }
                placed = nowPlaced;
                pendingClick = false;
                stepWaitTicks = 0;
                if (placed >= take) finishPlacing();
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("往主背包放物品失败：已经放了 " + placed + "/" + take
                    + "，光标上还有 " + cursor.getCount() + " 个");
                return;
            }
            return;
        }

        // 一个 tick 里连点几下（右键一次放 1 个），别一个一个等 tick
        int room = target.getStack().isEmpty() ? 0
            : Math.min(target.getMaxItemCount(sourceStack), sourceStack.getMaxCount())
                - target.getStack().getCount();
        int batch = Math.min(take - placed, Math.min(PLACE_CLICKS_PER_TICK, cursor.getCount()));
        if (!target.getStack().isEmpty()) {
            batch = Math.min(batch, Math.max(0, room));
        }
        if (batch <= 0) {
            fail("目标格 " + InventoryUtils.describe(target) + " 放不下更多了（还要放 "
                + (take - placed) + " 个）");
            return;
        }

        for (int i = 0; i < batch; i++) {
            clickQuiet(target, 1, SlotActionType.PICKUP);
        }
        clickCursorCount = cursor.getCount();
        pendingClick = true;
        stepWaitTicks = 0;
    }

    /** 该放的都放完了：还有剩的就放回源格，没剩就直接结账。 */
    private void finishPlacing() {
        pendingClick = false;
        stepWaitTicks = 0;

        int back = sourceCount - take;
        if (take > 1) {
            ChatUtils.debug("往 %s 放了 %d 个（一次一个，这里只报一句）", InventoryUtils.describe(target), take);
        }
        if (back <= 0) {
            ChatUtils.debug("%d 个都放进去了，源格也空了", take);
            completeExtraction();
            return;
        }
        phase = Phase.RETURN;
        ChatUtils.debug("%d 个已经放好，把剩下的 %d 个放回源格", take, back);
    }

    private void tickReturn() {
        int back = sourceCount - take;

        if (pendingClick) {
            if (handler.getCursorStack().isEmpty()) {
                pendingClick = false;
                stepWaitTicks = 0;
                ChatUtils.debug("剩下的 %d 个已经放回源格", back);
                completeExtraction();
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("把剩下的 " + back + " 个放回源格失败（光标上还有 "
                    + handler.getCursorStack().getCount() + " 个）");
                return;
            }
            return;
        }

        click(source, 0, SlotActionType.PICKUP, "把剩下的 " + back + " 个放回源格");
    }

    /** 整叠往主背包里放：左键点主背包格，直到光标空（同类半叠优先，其次空格）。 */
    private void tickDump() {
        ItemStack cursor = handler.getCursorStack();

        if (cursor.isEmpty()) {
            pendingClick = false;
            stepWaitTicks = 0;
            ChatUtils.debug("整叠都放进主背包了");
            completeExtraction();
            return;
        }

        if (pendingClick) {
            if (cursor.getCount() < clickCursorCount) {
                ChatUtils.debug("主背包收下了 %d 个，光标上还剩 %d 个",
                    clickCursorCount - cursor.getCount(), cursor.getCount());
                pendingClick = false;
                stepWaitTicks = 0;
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("往主背包放物品失败：光标上还有 " + cursor.getCount() + " 个");
                return;
            }
            return;
        }

        Slot dumpTarget = InventoryUtils.dumpTarget(handler, playerInventory, sourceStack);
        if (dumpTarget == null) {
            fail("主背包 27 格里没有能放下 " + describeStack(sourceStack) + " 的格子了（光标上还有 "
                + cursor.getCount() + " 个）");
            return;
        }
        click(dumpTarget, 0, SlotActionType.PICKUP, "把光标上的 " + cursor.getCount()
            + " 个放进 " + InventoryUtils.describe(dumpTarget));
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /** 一次提取成功：按实际搬走的量扣 item_list，然后继续看下一格。 */
    private void completeExtraction() {
        int moved = sourceCount - source.getStack().getCount();
        if (moved <= 0) {
            fail("源格 " + InventoryUtils.describe(source) + " 其实没搬走东西");
            return;
        }

        if (ShulkerUtils.isShulkerBox(sourceStack)) {
            List<ItemStack> contents = ShulkerUtils.consideredContents(sourceStack);
            ChatUtils.debug("潜影盒搬走了 %d 个，按盒子里要看的东西扣 item_list（内容：%s）",
                moved, ShulkerUtils.describeContents(sourceStack));
            if (contents.isEmpty()) {
                ChatUtils.debug("　（盒子里的东西都被无视、或者本来就没东西，item_list 不用扣）");
            }
            for (ItemStack inner : contents) {
                int before = itemList.remaining(inner.getItem());
                int amount = inner.getCount() * moved;
                int cut = itemList.take(inner.getItem(), amount);
                ChatUtils.debug("　%s：盒子里 x%d × %d 个盒子 = %d 个；item_list 里 %d → %d（扣了 %d）",
                    Registries.ITEM.getId(inner.getItem()), inner.getCount(), moved, amount,
                    before, itemList.remaining(inner.getItem()), cut);
            }
        } else {
            int before = itemList.remaining(sourceStack.getItem());
            int cut = itemList.take(sourceStack.getItem(), moved);
            ChatUtils.debug("%s 搬走 %d 个：item_list 里 %d → %d（扣了 %d）",
                Registries.ITEM.getId(sourceStack.getItem()), moved,
                before, itemList.remaining(sourceStack.getItem()), cut);
        }

        extractions++;
        ChatUtils.debug("第 %d 次提取完成，现在 item_list = %s", extractions, itemList.toJson());

        slotCursor++;
        source = null;
        sourceStack = null;
        target = null;
        pendingClick = false;
        stepWaitTicks = 0;
        phase = Phase.SCAN;
    }

    /** 正常停止（不是出错）。 */
    private void stop(String reason) {
        stopReason = reason;
        ChatUtils.debug("停止：%s", reason);
        finish();
    }

    private void click(Slot slot, int button, SlotActionType type, String what) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null || mc.player == null) {
            fail("拿不到 interactionManager");
            return;
        }
        ChatUtils.debug("点击：%s（slot=%d，button=%d，%s，光标=%s）",
            what, slot.id, button, type, describeStack(handler.getCursorStack()));
        clickCursorCount = handler.getCursorStack().getCount();
        mc.interactionManager.clickSlot(handler.syncId, slot.id, button, type, mc.player);
        pendingClick = true;
        stepWaitTicks = 0;
    }

    /**
     * 跟 {@link #click} 一样，但**不打日志**。
     *
     * <p>「一个一个往背包里放」那种循环会点几十上百次，每次都刷一行日志没有意义
     * （放完会有一句汇总）。
     */
    private void clickQuiet(Slot slot, int button, SlotActionType type) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null || mc.player == null) {
            fail("拿不到 interactionManager");
            return;
        }
        clickCursorCount = handler.getCursorStack().getCount();
        mc.interactionManager.clickSlot(handler.syncId, slot.id, button, type, mc.player);
        pendingClick = true;
        stepWaitTicks = 0;
    }

    private void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || handler == null) return;
        if (mc.player.currentScreenHandler != handler) return;

        // 界面本来就没有显示出来（见 ScreenSuppressor），
        // 这里只要通知服务端关闭，并让客户端把 currentScreenHandler 换回玩家自己的。
        mc.player.closeHandledScreen();
        ChatUtils.debug("已关闭容器界面（客户端全程没有显示界面）");
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
