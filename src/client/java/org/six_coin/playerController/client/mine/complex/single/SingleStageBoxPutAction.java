package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.function.Predicate;

/**
 * 打开一个潜影盒，把物品栏里符合条件的物品**全部**塞进去（先塞同类半叠，再塞空格）。
 *
 * <p>用途：卸货时把圆石 / 黏液块 / 要收集的东西装盒。
 *
 * <p>盒子满了就正常结束，{@link #boxFull()} 是 true，由调用方决定换下一个盒子还是先记着。
 */
class SingleStageBoxPutAction extends SingleStageStep {

    private enum Phase {
        OPENING,
        WORK
    }

    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final BlockPos box;
    private final Predicate<ItemStack> filter;
    private final String what;
    private final ContainerSession session;

    private Phase phase = Phase.OPENING;
    private int moved;
    private int totalTicks;
    private boolean boxFull;

    SingleStageBoxPutAction(SingleStageCtx ctx, BlockPos box, Predicate<ItemStack> filter,
                            String what) {
        super(ctx);
        this.box = box.toImmutable();
        this.filter = filter;
        this.what = what;
        this.session = new ContainerSession(this.box);
    }

    /** 是盒子满了才停的（false = 物品栏里没有符合条件的东西了）。 */
    boolean boxFull() {
        return boxFull;
    }

    /** 塞进去几叠。 */
    int moved() {
        return moved;
    }

    @Override
    protected String phase() {
        return "把" + what + "装进 " + box.toShortString();
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }
        session.open(mc, player);
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("装盒超时（" + totalTicks + " tick）");
            return;
        }

        if (phase == Phase.OPENING) {
            switch (session.awaitOpen(mc)) {
                case READY -> phase = Phase.WORK;
                case TIMEOUT -> fail("打不开 " + box.toShortString() + "（等容器界面超时，够不着？）");
                case WAITING -> {
                }
            }
            return;
        }

        if (!session.stillOpen(mc)) {
            fail("盒子 " + box.toShortString() + " 的界面被关掉了");
            return;
        }

        work(player);
    }

    @Override
    protected void onEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (session.isOpen()) {
            ctx.data.setBox(box, SingleStageData.contentsOf(session.handler()));
        }
        session.close(mc);
        if (failureReason() == null) {
            ChatUtils.debug("往 %s 装了 %d 叠%s%s", box.toShortString(), moved, what,
                boxFull ? "（盒子满了）" : "");
        }
    }

    // ------------------------------------------------------------------

    private void work(ClientPlayerEntity player) {
        Slot source = findSource(player);
        if (source == null) {
            finish();
            return;
        }

        ItemStack stack = source.getStack().copy();
        Slot target = findTarget(stack);
        if (target == null) {
            boxFull = true;
            finish();
            return;
        }

        session.click(source, 0, SlotActionType.PICKUP, "拿起 " + SingleStageData.idOf(stack));
        if (session.handler().getCursorStack().isEmpty()) return;

        session.click(target, 0, SlotActionType.PICKUP, "放进盒子");
        if (!session.handler().getCursorStack().isEmpty()) {
            // 盒子这一格塞不下整叠：剩下的放回原格，下一轮会挑别的格子
            session.click(source, 0, SlotActionType.PICKUP, "剩下的放回去");
        }
        moved++;
    }

    /** 物品栏（快捷栏 + 主背包）里第一个符合条件的格子。 */
    @Nullable
    private Slot findSource(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (Slot slot : session.handler().slots) {
            if (slot.inventory != inventory) continue;
            int index = slot.getIndex();
            if (index < 0 || index >= InventoryUtils.INVENTORY_END) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            if (filter.test(stack)) return slot;
        }
        return null;
    }

    /** 盒子里能放这一叠的格子：先找同类半叠，再找空格。 */
    @Nullable
    private Slot findTarget(ItemStack stack) {
        Slot empty = null;
        for (Slot slot : InventoryUtils.containerSlots(session.handler())) {
            if (!slot.isEnabled() || !slot.canInsert(stack)) continue;
            if (slot.getStack().isEmpty()) {
                if (empty == null) empty = slot;
                continue;
            }
            if (InventoryUtils.roomFor(slot, stack) > 0) return slot;
        }
        return empty;
    }
}
