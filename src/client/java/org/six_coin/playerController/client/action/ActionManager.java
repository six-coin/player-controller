package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * 动作调度器：同一时间只跑一个动作，其余排队。
 *
 * <p>由 {@code ClientTickEvents.START_CLIENT_TICK} 驱动，也就是在原版 tick 世界之前，
 * 这样移动类动作写入的速度能在这一 tick 生效。
 */
public final class ActionManager {

    private static final ActionManager INSTANCE = new ActionManager();

    private final Deque<Action> queue = new ArrayDeque<>();
    private Action current;

    private ActionManager() {
    }

    public static ActionManager get() {
        return INSTANCE;
    }

    public boolean isBusy() {
        return current != null || !queue.isEmpty();
    }

    public Action currentAction() {
        return current;
    }

    /** 提交一个动作，排队执行。 */
    public void submit(Action action) {
        if (isBusy()) {
            ChatUtils.debug("已排队（前面还有任务）: " + action.name());
        }
        queue.addLast(action);
    }

    /** 取消所有待执行动作，并终止当前动作。 */
    public void cancelAll() {
        queue.clear();
        if (current != null) {
            current.fail("被取消");
        }
    }

    /** 取消当前和排队的移动任务，返回取消的数量。 */
    public int cancelMoves() {
        int count = 0;

        Iterator<Action> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isMovement()) {
                iterator.remove();
                count++;
            }
        }

        if (current != null && current.isMovement()) {
            current.fail("被 /pc move cancel 取消");
            count++;
        }
        return count;
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            if (current != null) {
                current.fail("玩家或世界已卸载");
                finishCurrent();
            }
            queue.clear();
            return;
        }

        if (current == null) {
            current = queue.pollFirst();
            if (current == null) return;
            current.start();
            if (current.isFinished()) {
                finishCurrent();
                return;
            }
        }

        current.update();
        if (current.isFinished()) {
            finishCurrent();
        }
    }

    private void finishCurrent() {
        Action done = current;
        current = null;
        if (done != null) {
            done.cleanup();
        }
    }
}
