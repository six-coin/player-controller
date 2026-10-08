package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.ItemList;
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
 * <p>结束（不管是正常结束还是失败）都会把修改后的 item_list 原样输出到聊天栏。
 *
 * <p>点击方式：一 tick 最多一次点击，点完等本地状态变成预期值再继续（客户端点击是本地预测的，
 * 正常情况下下一 tick 就能往下走；等太久就报错停下，日志里能看到卡在哪一步）。
 * 整叠搬走用 {@code QUICK_MOVE}；只搬一部分用「拿起整叠 → 右键往目标格放 N 个 → 剩下的放回原格」。
 */
public class ContainerGetAction extends Action {

    private enum Phase {
        /** 等容器界面打开。 */
        OPENING,
        /** 找下一个能提取的容器格。 */
        SCAN,
        /** 把源格整叠拿到光标上。 */
        PICKUP,
        /** 右键往主背包目标格放，一次放 1 个。 */
        PLACE,
        /** 把光标上剩下的放回源格。 */
        RETURN,
        /** shift 点击整叠搬走。 */
        QUICK
    }

    /** 一次点击最多等这么多 tick 生效。 */
    private static final int MAX_STEP_WAIT_TICKS = 60;

    /** 等容器界面打开最久。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;

    /** 整个任务最久（20 tick = 1 秒）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 300;

    private final BlockPos pos;
    private final ItemList itemList;

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

    // 当前这次提取
    private Slot source;
    private ItemStack sourceStack;
    private int sourceCount;
    private int take;
    private int placed;
    private Slot target;

    public ContainerGetAction(BlockPos pos, ItemList itemList) {
        this.pos = pos.toImmutable();
        this.itemList = itemList;
    }

    @Override
    public String name() {
        return "从容器 " + pos.toShortString() + " 取 " + itemList.describe();
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
            case QUICK -> tickQuick();
        }
    }

    @Override
    protected void onEnd() {
        closeScreen();

        if (stopReason != null) {
            ChatUtils.info("容器任务结束：" + stopReason);
        }

        String json = itemList.toJson();
        ChatUtils.debug("一共提取了 %d 次；修改后的 item_list = %s", extractions, json);

        // 指令跑完之后原封不动输出修改过的 item_list
        ChatUtils.rawCopyable(json);

        ScreenSuppressor.release();
    }

    // ------------------------------------------------------------------
    // 打开容器
    // ------------------------------------------------------------------

    private void openContainer(MinecraftClient mc, ClientPlayerEntity player) {
        PlayerUtils.lookAt(pos);
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        // 先把朝向同步给服务端，再发交互包
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround(), player.horizontalCollision));

        Direction side = PlayerUtils.facingSide(pos);
        Vec3d hitPos = Vec3d.ofCenter(pos).add(
            side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitPos, side, pos, false);

        boolean wasSneaking = player.isSneaking();
        player.setSneaking(false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        player.swingHand(Hand.MAIN_HAND);
        player.setSneaking(wasSneaking);

        ChatUtils.debug("已右键 %s（面 %s，yaw %.1f pitch %.1f），等容器界面",
            pos.toShortString(), side.asString(), yaw, pitch);
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
            phase = Phase.SCAN;
            ChatUtils.debug("容器已打开：syncId=%d，界面共 %d 格，其中容器格 %d 格",
                handler.syncId, handler.slots.size(), containerSlots.size());
            dumpContainerSlots();
            return;
        }
        if (openWaitTicks > MAX_OPEN_WAIT_TICKS) {
            fail("等待容器界面超时（" + openWaitTicks + " tick）");
        }
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

            // ① 潜影盒：盒子里有要的东西就整个搬走
            if (ShulkerUtils.isShulkerBox(stack)) {
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

            // ② 普通物品：搬 min(这一格的数量, 还要的数量)
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
            stop("容器的格子都看完了，item_list 还差：" + itemList.describe());
            return;
        }

        // 每次提取之前都检查一次主背包有没有空位
        Slot empty = InventoryUtils.firstEmptyMainSlot(handler, playerInventory);
        if (empty == null) {
            stop("主背包（27 格，不含快捷栏）没有空位了，停止；item_list 还差：" + itemList.describe());
            return;
        }

        if (take == sourceCount) {
            phase = Phase.QUICK;
            ChatUtils.debug("搬法：QUICK_MOVE 整叠搬走（空位检查通过，第一个空格是 #%d）", empty.id);
            return;
        }

        // 只取一部分：优先塞进同类半叠，塞不下就放空格
        Slot merge = InventoryUtils.mergeTarget(handler, playerInventory, sourceStack, take);
        target = merge != null ? merge : empty;
        if (!target.canInsert(sourceStack)) {
            stop("主背包里找不到能放下 " + describeStack(sourceStack)
                + " 的格子，停止；item_list 还差：" + itemList.describe());
            return;
        }
        placed = 0;
        phase = Phase.PICKUP;
        ChatUtils.debug("搬法：拿起整叠 → 右键往 #%d（%s）放 %d 个 → 剩下的放回原格",
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
                phase = Phase.PLACE;
                ChatUtils.debug("整叠 %d 个已经在光标上了（这次要放 %d 个）", sourceCount, take);
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
        if (pendingClick) {
            int expectedCursor = sourceCount - placed - 1;
            if (cursor.getCount() == expectedCursor) {
                placed++;
                pendingClick = false;
                stepWaitTicks = 0;
                ChatUtils.debug("已放入 %d/%d 个（目标格 %s）", placed, take, InventoryUtils.describe(target));
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

        click(target, 1, SlotActionType.PICKUP, "右键放 1 个进 " + InventoryUtils.describe(target));
    }

    /** 该放的都放完了：还有剩的就放回源格，没剩就直接结账。 */
    private void finishPlacing() {
        pendingClick = false;
        stepWaitTicks = 0;

        int back = sourceCount - take;
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

    private void tickQuick() {
        if (pendingClick) {
            if (source.getStack().getCount() < sourceCount) {
                pendingClick = false;
                stepWaitTicks = 0;
                completeExtraction();
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("整叠搬走失败，源格还是 " + InventoryUtils.describe(source));
                return;
            }
            return;
        }

        click(source, 0, SlotActionType.QUICK_MOVE, "整叠搬走");
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
            List<ItemStack> contents = ShulkerUtils.contents(sourceStack);
            ChatUtils.debug("潜影盒搬走了 %d 个，按盒子里的东西扣 item_list：", moved);
            if (contents.isEmpty()) {
                ChatUtils.debug("　（空盒子，item_list 不用扣）");
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
