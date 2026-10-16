package org.six_coin.playerController.client.container;

import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 点格子的那一下：查一下能不能点、说一句日志、然后 {@code clickSlot}。
 *
 * <p>所有跟容器 / 背包打交道的动作都从这里点，别再各自写一遍（连日志格式都不一样）。
 *
 * <p><b>点击是本地预测、立即生效的</b>：{@code clickSlot} 回来的那一刻，界面里的数量和光标就已经变了。
 * 所以「点之前是多少」这种数字**必须在调用这里之前读**，点完再读读到的是点之后的值，
 * 拿它当「点之前」用会判断不出这一下到底有没有搬动东西。
 */
public final class ContainerClicks {

    private ContainerClicks() {
    }

    /**
     * 点某个格子。
     *
     * @param action 日志里这一下在干什么（「拿起源格整叠」这种）
     * @return 点出去了返回 true；格子不存在 / 没有交互管理器返回 false（调用方自己决定怎么办）
     */
    public static boolean click(@Nullable ScreenHandler handler, @Nullable Slot slot, int button,
                                SlotActionType type, String action) {
        return doClick(handler, slot, button, type, action);
    }

    /**
     * 跟 {@link #click} 一样，但**不打日志**。
     *
     * <p>「一个一个往目标格放」那种循环会点几十上百次，每次都刷一行日志没有意义（放完会有汇总）。
     */
    public static boolean clickQuiet(@Nullable ScreenHandler handler, @Nullable Slot slot, int button,
                                     SlotActionType type) {
        return doClick(handler, slot, button, type, null);
    }

    /**
     * Shift 点击：让**服务端**自己决定这一叠放哪儿、能放多少。
     *
     * <p>容器满没满、能收下多少，只有这一下点出去才知道 —— 别自己先猜（服务端才知道真实结果）。
     */
    public static boolean quickMove(@Nullable ScreenHandler handler, @Nullable Slot slot, String action) {
        return doClick(handler, slot, 0, SlotActionType.QUICK_MOVE, action);
    }

    /** 跟 {@link #quickMove} 一样，但**不打日志**（一轮点很多下时用，点完有汇总）。 */
    public static boolean quickMoveQuiet(@Nullable ScreenHandler handler, @Nullable Slot slot) {
        return doClick(handler, slot, 0, SlotActionType.QUICK_MOVE, null);
    }

    private static boolean doClick(@Nullable ScreenHandler handler, @Nullable Slot slot, int button,
                                   SlotActionType type, @Nullable String action) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (handler == null || slot == null || mc.player == null || mc.interactionManager == null) {
            String why = handler == null ? "现在没有打开的界面"
                : slot == null ? "找不到要点的格子" : "拿不到玩家或 interactionManager";
            // 点不出去是异常情况（跟「一格一格点很多下」不一样），不打日志的话调用方会把它当成「搬不动」
            ChatUtils.debug("点不了格子%s：%s", action == null ? "" : "（" + action + "）", why);
            return false;
        }

        if (action != null) {
            ChatUtils.debug("点击：%s（slot=%d，%s，光标=%s）", action, slot.id, type,
                InventoryUtils.describe(handler.getCursorStack()));
        }
        mc.interactionManager.clickSlot(handler.syncId, slot.id, button, type, mc.player);
        return true;
    }

    /**
     * 用**槽位号**点（{@code ScreenHandler.slots} 的下标就是槽位号）。
     *
     * <p>有些地方本来就是拿着槽位号在算（比如合成：格子在界面里的位置是按编号算出来的）。
     */
    public static boolean click(@Nullable ScreenHandler handler, int slotId, int button,
                                SlotActionType type, @Nullable String action) {
        return doClick(handler, slot(handler, slotId), button, type, action);
    }

    /** 用槽位号点，但不打日志。 */
    public static boolean clickQuiet(@Nullable ScreenHandler handler, int slotId, int button,
                                     SlotActionType type) {
        return doClick(handler, slot(handler, slotId), button, type, null);
    }

    /** 点界面上的按钮（切石机那一排配方）。 */
    public static boolean clickButton(@Nullable ScreenHandler handler, int buttonId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (handler == null || mc.interactionManager == null) {
            ChatUtils.debug("点不了按钮 #%d：没有打开的界面或拿不到 interactionManager", buttonId);
            return false;
        }
        mc.interactionManager.clickButton(handler.syncId, buttonId);
        return true;
    }

    @Nullable
    private static Slot slot(@Nullable ScreenHandler handler, int slotId) {
        if (handler == null || slotId < 0 || slotId >= handler.slots.size()) return null;
        return handler.slots.get(slotId);
    }
}
