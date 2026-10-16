package org.six_coin.playerController.client.container;

import net.minecraft.screen.slot.Slot;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把物品栏里若干格子里的整叠东西用 QuickMove 搬进容器。
 *
 * <p>放哪儿、能放多少**都由服务端决定**（QuickMove 就是 shift 点击）：这里只管
 * 「一轮点一遍 → 等两 tick 核对 → 逐格记下哪几格搬得动」。
 *
 * <ul>
 *   <li><b>一轮</b>：把这一轮要点的格子（有东西、而且还没被判成搬不动的）连着点一遍。
 *       点击是本地预测、立即生效的，一 tick 里点几十下没问题，服务端会按顺序处理；</li>
 *   <li><b>核对</b>：等 {@value #SETTLE_TICKS} tick 让服务端的同步回来，再逐格比
 *       「点之前有几个 / 现在有几个」。少了就是搬动了，没少就记进「这格搬不动」；</li>
 *   <li><b>满没满</b>：整轮一格都没搬动时，先把「搬不动」清掉再试一轮确认；连着两轮都没搬动
 *       才算容器满了 —— 免得一次同步抖动就被当成满了，跑去换下一个容器。</li>
 * </ul>
 *
 * <p>搬走一部分、还剩一些的格子会留在候选里，下一轮接着点（QuickMove 一次会把容器里能塞的都塞完，
 * 所以剩下的一定是「再也塞不下了」）。
 *
 * <p>调用方每一 tick 把「候选的源格」给一遍就行：哪些格子、什么算「要搬的东西」由调用方决定
 * （见 {@code ContainerPutAction}、{@code PutHotbarItemAction}）。
 */
public final class ContainerStash {

    /** 这一批搬得怎么样了。 */
    public enum Result {
        /** 还在搬：下一 tick 接着调。 */
        WORKING,
        /** 候选里已经没有要搬的东西了。 */
        CLEARED,
        /** 连着两轮一点都没搬动：容器装不下了。 */
        FULL
    }

    /** 一轮点完之后等几 tick 再核对（服务端的同步要一个来回）。 */
    private static final int SETTLE_TICKS = 2;

    private final ContainerSession session;

    /** 这一轮点过的格子；{@link #batchBefore} 里是每格点之前的数量。 */
    private final List<Slot> batch = new ArrayList<>();
    private final List<Integer> batchBefore = new ArrayList<>();

    /** 点过、一点都没搬动的格子（{@code Slot.id}）。 */
    private final Set<Integer> tried = new HashSet<>();

    /** 已经有一整轮没搬动了：下一轮还是没搬动就当容器满了。 */
    private boolean noMoveRound;

    private int settleTicks;
    private int clicks;
    private int stacks;
    /** 有哪一下点击根本没发出去（界面没了之类）：这时候「没搬动」不能当成容器满，调用方要停下来报错。 */
    private boolean rejected;

    public ContainerStash(ContainerSession session) {
        this.session = session;
    }

    /** 一共点了几下（日志用）。 */
    public int clicks() {
        return clicks;
    }

    /** 一共搬空了几格（日志用）。 */
    public int stacks() {
        return stacks;
    }

    /** 有没有点击根本没发出去（是的话调用方应该停下来报错，别当成容器满了）。 */
    public boolean rejected() {
        return rejected;
    }

    /**
     * 每次 tick 调一次。
     *
     * @param candidates 这一轮看着可以考虑的源格（每次都给一遍；里面有东西的才算数）
     */
    public Result tick(List<Slot> candidates) {
        if (settleTicks > 0) {
            settleTicks--;
            return settleTicks == 0 ? verify() : Result.WORKING;
        }

        boolean anything = false;
        batch.clear();
        batchBefore.clear();

        // 一 tick 最多点这么多下（actions.items_per_tick 就是为这种连点准备的）
        int budget = PlayerControllerConfig.getItemsPerTick();
        for (Slot slot : candidates) {
            if (slot.getStack().isEmpty()) continue;
            anything = true;
            if (tried.contains(slot.id) || batch.size() >= budget) continue;

            batch.add(slot);
            batchBefore.add(slot.getStack().getCount());
            if (!ContainerClicks.quickMoveQuiet(session.handler(), slot)) rejected = true;
            clicks++;
        }

        if (!anything) return Result.CLEARED;
        if (batch.isEmpty()) return confirmOrGiveUp("能试的格子都试过了");
        if (batch.size() > 1) ChatUtils.debug("一轮 QuickMove %d 格", batch.size());
        settleTicks = SETTLE_TICKS;
        return Result.WORKING;
    }

    /** 核对上一轮：逐格比数量。 */
    private Result verify() {
        int clicked = batch.size();
        int moved = 0;
        int emptied = 0;

        for (int i = 0; i < clicked; i++) {
            Slot slot = batch.get(i);
            if (slot.getStack().getCount() < batchBefore.get(i)) {
                moved++;
                tried.remove(slot.id);
                if (slot.getStack().isEmpty()) emptied++;
            } else {
                tried.add(slot.id);
            }
        }

        batch.clear();
        batchBefore.clear();
        stacks += emptied;

        if (moved > 0) {
            noMoveRound = false;
            ChatUtils.debug("核对了 %d 格：搬动 %d 格（搬空 %d 格）", clicked, moved, emptied);
            return Result.WORKING;
        }

        ChatUtils.debug("核对了 %d 格：一格都没搬动", clicked);
        return confirmOrGiveUp("一轮都没搬动");
    }

    /** 一点都没搬动的处理：先清掉「搬不动」再确认一轮，还是这样才算容器满了。 */
    private Result confirmOrGiveUp(String why) {
        if (!noMoveRound) {
            noMoveRound = true;
            tried.clear();
            ChatUtils.debug("%s，再确认一轮", why);
            return Result.WORKING;
        }
        ChatUtils.debug("%s，连着两轮都这样，容器满了", why);
        return Result.FULL;
    }
}
