package org.six_coin.playerController.client.stock;

import net.minecraft.client.MinecraftClient;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.List;

/**
 * {@code /pc stock task <名字> start}：备货整条流水线。
 *
 * <p>流水线就是下面 {@link #StockTask(String)} 里列出来的那张**有序步骤表**：
 * {@link StepQueue} 一次跑一步、跑完自动接下一步，哪一步失败整条就停。
 * 每一步只管自己那一段，不启动别的步骤，所以整条流程的先后顺序只有这一处。
 *
 * <p>各步之间要传的东西（内存里的 station_data）走 {@link StockRun}。
 *
 * <p>流程（按需求）：
 * <ol>
 *   <li>第一部分阶段1（准备）：检查工作站 → 腾空物品栏到 item_temp → 取一把够耐久的钻石镐 →
 *       容器物品汇总 + 从 {@code material.json} 算出要取哪些货；</li>
 *   <li>第一部分阶段2（取货）：按 {@code final_final.json} 去容器里取；</li>
 *   <li>第一部分阶段3（分盒重装）：按 {@code final_pack.json} 装盒，盒子放去 item_final；</li>
 *   <li>第二部分阶段1（预检查）：再汇总一次容器物品，算出要合成什么（{@code 2_x}、{@code final_steps}）；</li>
 *   <li>第二部分阶段2（取货）：按 {@code final_material.json} 去容器里取；</li>
 *   <li>第二部分阶段3（合成）：按 {@code final_steps.json} 一步步合成并装盒。</li>
 * </ol>
 */
public final class StockTask extends Action {

    /** 整条流水线要求快捷栏第一格放着钻石镐，剩余耐久至少要这么多。 */
    public static final int REQUIRED_PICKAXE_DURABILITY = 1000;

    /** 钻石镐放快捷栏第一格（背包下标 0）。 */
    public static final int PICKAXE_HOTBAR = 0;

    private final String task;
    private final StepQueue steps;

    public StockTask(String task) {
        this.task = task;

        StockRun run = new StockRun();
        this.steps = new StepQueue(List.of(
            new Part1Stage1Prepare(task, run),
            new Part1Stage2Collect(task, run),
            new Part1Stage3Pack(task, run),
            new Part2Stage1Prepare(task, run),
            new Part2Stage2Collect(task, run),
            new Part2Stage3Craft(task, run)));
    }

    @Override
    public String name() {
        return "备货 " + task;
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }
        ChatUtils.info("备货 " + task + "：一共 " + steps.total() + " 步");
    }

    @Override
    protected void tick() {
        Action finished = steps.tick();
        if (finished != null) {
            ChatUtils.info("第 " + steps.number() + "/" + steps.total() + " 步完成：" + finished.name());
        }

        Action failed = steps.failedStep();
        if (failed != null) {
            fail("第 " + steps.number() + "/" + steps.total() + " 步（" + failed.name() + "）失败："
                + failed.failureReason());
            return;
        }

        if (steps.isDone()) {
            ChatUtils.info("备货 " + task + " 全部完成");
            finish();
        }
    }

    @Override
    protected void onEnd() {
        if (failureReason() != null) {
            ChatUtils.error(name() + "没做完：" + failureReason());
        }
    }
}
