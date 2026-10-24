package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.List;
import java.util.function.Predicate;

/**
 * 打开一个潜影盒，把里面的东西拿到物品栏的**指定几格**（可以指定每格拿几个）。
 *
 * <p>用途：建站时从粘液块 / 圆石盒子里取料（9 组黏液块、21 个圆石）、拆站时把盒子收回背包。
 *
 * <p>盒子里没有符合条件的东西**不算失败**：正常结束，调用方看 {@link #taken()} 判断。
 * 拿完（或没得拿）会把盒子的新内容写回 {@link SingleStageData} 的缓存。
 */
class SingleStageBoxGetAction extends SingleStageStep {

    /** 想要一格：{@code count <= 0} 表示整叠。 */
    record Want(int slot, int count) {

        static Want whole(int slot) {
            return new Want(slot, 0);
        }

        static Want exactly(int slot, int count) {
            return new Want(slot, count);
        }
    }

    private enum Phase {
        OPENING,
        WORK
    }

    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final BlockPos box;
    private final Predicate<ItemStack> filter;
    private final List<Want> wants;
    private final String what;
    private final ContainerSession session;

    private Phase phase = Phase.OPENING;
    private int wantIndex;
    private int taken;
    private int totalTicks;

    SingleStageBoxGetAction(SingleStageCtx ctx, BlockPos box, Predicate<ItemStack> filter,
                            List<Want> wants, String what) {
        super(ctx);
        this.box = box.immutable();
        this.filter = filter;
        this.wants = List.copyOf(wants);
        this.what = what;
        this.session = new ContainerSession(this.box);
    }

    /** 实际拿到了几叠。 */
    int taken() {
        return taken;
    }

    @Override
    protected String phase() {
        return "从 " + box.toShortString() + " 取" + what;
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        session.open(mc, player);
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("取东西超时（" + totalTicks + " tick）");
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
        Minecraft mc = Minecraft.getInstance();
        if (session.isOpen()) {
            ctx.data.setBox(box, SingleStageData.contentsOf(session.handler()));
        }
        session.close(mc);
        if (failureReason() == null) {
            ChatUtils.debug("从 %s 取了 %d 叠%s（要 %d 格）", box.toShortString(), taken, what, wants.size());
        }
    }

    // ------------------------------------------------------------------

    private void work(LocalPlayer player) {
        if (wantIndex >= wants.size()) {
            finish();
            return;
        }

        Want want = wants.get(wantIndex);
        Slot target = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), want.slot());
        if (target == null) {
            fail("找不到物品栏第 " + want.slot() + " 格");
            return;
        }

        // need = 0 表示「整叠」；> 0 表示「这一格补到 want.count() 个」
        int need;
        if (want.count() <= 0) {
            if (!target.getItem().isEmpty()) {
                wantIndex++;
                return;
            }
            need = 0;
        } else {
            need = want.count() - target.getItem().getCount();
            if (need <= 0) {
                wantIndex++;
                return;
            }
        }

        Slot source = findSource();
        if (source == null) {
            finish();          // 盒子里没有符合条件的东西了
            return;
        }

        ItemStack before = source.getItem().copy();
        session.click(source, 0, ContainerInput.PICKUP, "拿起 " + SingleStageData.idOf(before));
        if (session.handler().getCarried().isEmpty()) {
            // 本地预测没跟上：下个 tick 再来（不要在这里空转）
            return;
        }

        int remaining = session.handler().getCarried().getCount();
        if (need == 0) {
            session.click(target, 0, ContainerInput.PICKUP,
                "放进物品栏第 " + want.slot() + " 格");
        } else {
            int clicks = Math.min(need, remaining);
            for (int i = 0; i < clicks; i++) {
                session.clickQuiet(target, 1, ContainerInput.PICKUP);
            }
        }

        if (!session.handler().getCarried().isEmpty()) {
            session.click(source, 0, ContainerInput.PICKUP, "剩下的放回盒子");
        }

        taken++;
        wantIndex++;
    }

    @Nullable
    private Slot findSource() {
        for (Slot slot : InventoryUtils.containerSlots(session.handler())) {
            if (slot.getItem().isEmpty()) continue;
            if (filter.test(slot.getItem())) return slot;
        }
        return null;
    }
}
