package org.six_coin.playerController.client.action;

import net.minecraft.client.Minecraft;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
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

    /**
     * 提交一个动作，排队执行。
     *
     * <p>这里是「命令层」和「实现层」的分界线：只有命令层会调这个方法，所以从这里进来的动作
     * 都算「命令直接要的」，结果照常打到聊天栏；被别的动作内嵌跑的子动作不走这里（直接
     * {@code child.start()}），它们的结果会被丢掉（见 {@link ResultOutput}）。
     */
    public void submit(Action action) {
        action.setOutput(ResultOutput.COMMAND);
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

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            if (current != null) {
                current.fail("玩家或世界已卸载");
                finishCurrent();
            }
            queue.clear();
            releaseScreenSuppressor();
            return;
        }

        if (current == null) {
            current = queue.pollFirst();
            if (current == null) {
                releaseScreenSuppressor();
                return;
            }
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

    /**
     * 兜底：一个任务都没在跑的时候，不该还压着「不显示容器界面」的计数。
     *
     * <p>正常流程每个动作的 {@code onEnd()} 都会 release，但动作要是异常退出（外围 catch 住的那种），
     * 计数就会一直挂着 —— 那之后容器界面全被挡掉，箱子永远打不开。空闲时清一次，
     * 最坏情况也只是「这一 tick 界面本来就不该显示」。
     */
    private void releaseScreenSuppressor() {
        if (ScreenSuppressor.reset()) {
            ChatUtils.debug("没有任务在跑了，兜底解除容器界面抑制");
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
