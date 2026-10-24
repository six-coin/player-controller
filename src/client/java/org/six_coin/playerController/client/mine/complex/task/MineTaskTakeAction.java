package org.six_coin.playerController.client.mine.complex.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.List;
import java.util.function.Predicate;

/**
 * 打开一个容器，把**一叠**符合条件的东西拿到物品栏的**指定一格**（整叠搬，不拆）。
 *
 * <p>用途：{@code d_start_mining} 第二阶段的「备货」—— 工具进快捷栏 1~5、备用工具进背包 1~5、
 * 熟猪排进快捷栏 9、潜影盒进背包 6~21 等等。玩家的物品栏在这之前已经被要求清空，
 * 所以目标格一定是空的；万一不是，直接报错（不会把东西塞到别处去）。
 *
 * <p>搬完会顺手更新 {@link MineTaskStationData}（那个容器的数量、空格数），补货流程后面要用。
 */
public class MineTaskTakeAction extends Action {

    /** 一次点击最多等这么多 tick 生效。 */
    private static final int MAX_STEP_WAIT_TICKS = 60;

    /** 整个动作最久。 */
    private static final int MAX_TOTAL_TICKS = 20 * 120;

    private final MineTaskStationData.ContainerInfo container;
    private final int targetSlot;
    private final Predicate<ItemStack> filter;
    private final String label;
    /** {@code >= 0} = 只从容器里的这一格拿（按位置拿潜影盒时用）；{@code -1} = 按 filter 找。 */
    private final int containerSlot;

    private final ContainerSession session;

    private enum Phase {
        OPENING,
        WORK
    }

    /** 拿东西的三步：找一格 → 拿起来 → 放进目标格。 */
    private enum Step {
        FIND,
        PICKED,
        PLACED
    }

    private Phase phase = Phase.OPENING;
    private Step step = Step.FIND;
    private int totalTicks;
    private int stepWaitTicks;

    /** 拿起来的那一叠（搬完按它更新 station_data）。 */
    @Nullable
    private ItemStack taken;

    /**
     * @param targetSlot {@code PlayerInventory} 下标（0~8 快捷栏、9~35 背包）；副手 40 不行
     *                   （容器界面里没有副手格，得先放背包再换过去）
     * @param filter     什么样的东西算「要拿的」
     */
    public MineTaskTakeAction(MineTaskStationData.ContainerInfo container,
                              int targetSlot, Predicate<ItemStack> filter, String label) {
        this(container, targetSlot, -1, filter, label);
    }

    /** 从容器里的**指定一格**拿（不看 filter，只看那一格非空）。 */
    public MineTaskTakeAction(MineTaskStationData.ContainerInfo container,
                              int targetSlot, int containerSlot, String label) {
        this(container, targetSlot, containerSlot, stack -> true, label);
    }

    private MineTaskTakeAction(MineTaskStationData.ContainerInfo container, int targetSlot,
                               int containerSlot, Predicate<ItemStack> filter, String label) {
        this.container = container;
        this.targetSlot = targetSlot;
        this.containerSlot = containerSlot;
        this.filter = filter;
        this.label = label;
        this.session = new ContainerSession(container.pos());
    }

    /** 实际拿走的那一叠；没拿到是 null。 */
    @Nullable
    public ItemStack taken() {
        return taken;
    }

    @Override
    public String name() {
        return "从 " + container.part().display() + " #" + container.id()
            + " 取" + label + " → 物品栏第 " + targetSlot + " 格";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        BlockPos pos = container.pos();
        if (!(mc.level.getBlockEntity(pos) instanceof Container)) {
            fail(pos.toShortString() + " 不是容器（station_data 过期了？重跑 /pc mine station check）");
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("离容器 " + pos.toShortString() + " 太远（" + String.format("%.2f",
                PlayerUtils.eyeDistanceTo(pos)) + " 格，触及 " + String.format("%.2f",
                PlayerUtils.reach()) + "）—— 落点没走到？");
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
            fail("取" + label + "超时（" + totalTicks + " tick）");
            return;
        }

        if (phase == Phase.OPENING) {
            switch (session.awaitOpen(mc)) {
                case READY -> phase = Phase.WORK;
                case TIMEOUT -> fail("打不开 " + container.pos().toShortString() + "（等界面超时）");
                case WAITING -> {
                }
            }
            return;
        }

        if (!session.stillOpen(mc)) {
            fail("容器界面被关掉了（" + container.pos().toShortString() + "）");
            return;
        }

        work(player);
    }

    @Override
    protected void onEnd() {
        if (session.isOpen()) {
            if (taken != null) container.take(taken);
            session.close(Minecraft.getInstance());
        }
        if (taken != null && failureReason() == null) {
            ChatUtils.debug("从 %s #%d 拿了 %s", container.part().display(), container.id(),
                InventoryUtils.describe(taken));
        }
    }

    // ------------------------------------------------------------------

    private void work(LocalPlayer player) {
        Slot target = InventoryUtils.inventorySlot(session.handler(), player.getInventory(), targetSlot);
        if (target == null) {
            fail("容器界面里找不到物品栏第 " + targetSlot + " 格");
            return;
        }

        // 第三步：放进去之后核对一下
        if (step == Step.PLACED) {
            ItemStack stack = target.getItem();
            if (!stack.isEmpty() && filter.test(stack)) {
                taken = stack.copy();
                finish();
                return;
            }
            if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                fail("物品没放进物品栏第 " + targetSlot + " 格");
            }
            return;
        }

        ItemStack targetStack = target.getItem();
        if (!targetStack.isEmpty()) {
            if (filter.test(targetStack)) {
                taken = targetStack.copy();
                finish();
                return;
            }
            fail("物品栏第 " + targetSlot + " 格被 " + InventoryUtils.describe(targetStack)
                + " 占了，放不下" + label);
            return;
        }

        // 第二步：光标上有东西了就放进目标格
        if (step == Step.PICKED) {
            ItemStack cursor = session.handler().getCarried();
            if (cursor.isEmpty()) {
                if (++stepWaitTicks > MAX_STEP_WAIT_TICKS) {
                    fail("拿起" + label + "失败（光标上什么都没有）");
                }
                return;
            }
            if (!session.click(target, 0, ContainerInput.PICKUP,
                "把" + label + "放进物品栏第 " + targetSlot + " 格")) {
                fail("点不了物品栏第 " + targetSlot + " 格");
                return;
            }
            step = Step.PLACED;
            stepWaitTicks = 0;
            return;
        }

        // 第一步：找一叠符合条件的东西
        Slot source = findSource();
        if (source == null) {
            fail(container.part().display() + " #" + container.id() + "（"
                + container.pos().toShortString() + "）里没有" + label
                + "（station_data 里记着 " + container.items() + "）");
            return;
        }

        ChatUtils.debug("第 %d 格是 %s，拿它", source.index, InventoryUtils.describe(source.getItem()));
        if (!session.click(source, 0, ContainerInput.PICKUP, "拿起" + label)) {
            fail("点不了 " + container.pos().toShortString() + " 的第 " + source.index + " 格");
            return;
        }
        step = Step.PICKED;
        stepWaitTicks = 0;
    }

    @Nullable
    private Slot findSource() {
        List<Slot> slots = InventoryUtils.containerSlots(session.handler());
        if (containerSlot >= 0) {
            if (containerSlot >= slots.size()) return null;
            Slot slot = slots.get(containerSlot);
            return slot.getItem().isEmpty() ? null : slot;
        }
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (filter.test(stack)) return slot;
        }
        return null;
    }
}
