package org.six_coin.playerController.client.stock;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.ContainerStash;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 把**快捷栏某几格**里的整叠东西搬进一个容器（{@link TakeItemAction} 的反向操作）。
 *
 * <p>两处用到它：
 * <ul>
 *   <li>备货第一部分阶段3：挖掉暂存处的潜影盒以后，捡到的盒子在快捷栏第二格，
 *       要把它塞进 item_final / item_storage（单格）；</li>
 *   <li>备货第二部分阶段3：{@code /pc recipe} 的成品会 QuickMove 到快捷栏 4~9 格，
 *       合成完要把这几格一起放进目标容器（一段）。</li>
 * </ul>
 *
 * <p>搬法是整叠 {@code QUICK_MOVE}（shift 点击）：放哪儿、能放多少**由服务端决定**，
 * 所以这里不猜容器还剩几格、也不自己挑格子 —— 一轮把这几格点一遍，等两 tick 逐格核对有没有真的搬走，
 * 连着两轮一点都没搬动就是容器装不下了（见 {@link ContainerStash}）。搬不进去的部分就留在快捷栏那一格里
 * （不会乱塞），由调用方决定换下一个容器。
 */
public class PutHotbarItemAction extends Action {

    private enum Phase {
        /** 等界面打开（内容同步过来才算好，见 {@link ContainerSession#awaitOpen}）。 */
        OPENING,
        PUT,
        DONE
    }

    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final BlockPos pos;
    private final int hotbarFrom;
    private final int hotbarTo;
    private final boolean wantDetail;

    private final ContainerSession session;
    /** 搬运本身（一轮点一遍 → 核对 → 判定满没满）。 */
    private final ContainerStash stash;
    private Phase phase = Phase.OPENING;
    private int totalTicks;

    private boolean allCleared;
    private boolean containerFull;
    private int freeSlots = -1;
    private ContainerCacheManager.Breakdown detail;

    /**
     * @param hotbarIndex 要搬走的快捷栏格（背包下标 0~8）
     */
    public PutHotbarItemAction(BlockPos pos, int hotbarIndex, boolean wantDetail) {
        this(pos, hotbarIndex, hotbarIndex, wantDetail);
    }

    /**
     * @param hotbarFrom 起始格（含，背包下标 0~8）
     * @param hotbarTo   结束格（含）
     */
    public PutHotbarItemAction(BlockPos pos, int hotbarFrom, int hotbarTo, boolean wantDetail) {
        this.pos = pos.toImmutable();
        this.hotbarFrom = Math.min(hotbarFrom, hotbarTo);
        this.hotbarTo = Math.max(hotbarFrom, hotbarTo);
        this.wantDetail = wantDetail;
        this.session = new ContainerSession(this.pos);
        this.stash = new ContainerStash(this.session);
    }

    /** 快捷栏那几格空了（东西都进容器了）。 */
    public boolean allCleared() {
        return allCleared;
    }

    /** 容器装不下剩下的了。 */
    public boolean containerFull() {
        return containerFull;
    }

    /** 还剩几个空格（wantDetail 时才有意义）。 */
    public int freeSlots() {
        return freeSlots;
    }

    /** 放完之后容器里剩什么（wantDetail 时才有意义）。 */
    public ContainerCacheManager.Breakdown detail() {
        return detail;
    }

    @Override
    public String name() {
        return hotbarFrom == hotbarTo
            ? "把快捷栏第 " + (hotbarFrom + 1) + " 格放进 " + pos.toShortString()
            : "把快捷栏第 " + (hotbarFrom + 1) + "~" + (hotbarTo + 1) + " 格放进 " + pos.toShortString();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
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
        if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
            fail("光标上还有物品，先放下再执行");
            return;
        }

        ItemStack first = firstStack(player);
        ChatUtils.debug("把快捷栏第 %d~%d 格（第一叠是 %s）放进 %s",
            hotbarFrom + 1, hotbarTo + 1, InventoryUtils.describe(first), pos.toShortString());
        if (first.isEmpty()) {
            allCleared = true;
            ChatUtils.debug("快捷栏第 %d~%d 格本来就是空的，不用放", hotbarFrom + 1, hotbarTo + 1);
            finish();
            return;
        }

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
            case PUT -> tickPut();
            case DONE -> finish();
        }
    }

    @Override
    protected void onEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (wantDetail && session.isOpen()) {
            detail = session.breakdown();
            freeSlots = session.freeSlots();
            if (freeSlots == 0) containerFull = true;
        }
        session.close(mc);

        if (failureReason() != null) {
            allCleared = false;
        }

        JsonObject result = new JsonObject();
        result.addProperty("all_cleared", allCleared);
        ChatUtils.debug("放物结果：all_cleared=%s，点了 %d 下（搬空 %d 格），free_slots=%d",
            allCleared, stash.clicks(), stash.stacks(), freeSlots);
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

    private void tickPut() {
        ContainerStash.Result result = stash.tick(hotbarSlots());
        if (stash.rejected()) {
            fail("点不了格子（放进容器）");
            return;
        }

        switch (result) {
            case WORKING -> {
            }
            case CLEARED -> {
                allCleared = true;
                ChatUtils.debug("快捷栏第 %d~%d 格都空了，放完了（点了 %d 下，搬空 %d 格）",
                    hotbarFrom + 1, hotbarTo + 1, stash.clicks(), stash.stacks());
                phase = Phase.DONE;
            }
            case FULL -> {
                allCleared = false;
                containerFull = true;
                ChatUtils.debug("容器装不下快捷栏第 %d~%d 格里剩下的东西了（点了 %d 下，搬空 %d 格）",
                    hotbarFrom + 1, hotbarTo + 1, stash.clicks(), stash.stacks());
                phase = Phase.DONE;
            }
        }
    }

    /** 这一轮可以考虑的源格：快捷栏这几格里有东西的。 */
    private List<Slot> hotbarSlots() {
        ScreenHandler handler = session.handler();
        List<Slot> result = new ArrayList<>();

        for (int i = hotbarFrom; i <= hotbarTo; i++) {
            Slot slot = InventoryUtils.hotbarSlot(handler, i);
            if (slot == null || slot.getStack().isEmpty()) continue;
            result.add(slot);
        }
        return result;
    }

    /** 快捷栏范围内第一叠东西（只给日志用）。 */
    private ItemStack firstStack(ClientPlayerEntity player) {
        for (int i = hotbarFrom; i <= hotbarTo; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }
}
