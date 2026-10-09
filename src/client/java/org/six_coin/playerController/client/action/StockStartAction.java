package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import org.six_coin.playerController.client.container.ContainerItemsExporter;
import org.six_coin.playerController.client.station.StationPrecheck;
import org.six_coin.playerController.client.stock.StockMaterials;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;

/**
 * {@code /pc stock task <名字> start} 的执行体 —— 备货第一部分「阶段1：预检查」。
 *
 * <p>按顺序做三件事：
 * <ol>
 *   <li>跑一遍工作站检查（{@link StationCheckAction}，和 {@code /pc station check} 同一个动作），
 *       生成 {@code station/check.json}；</li>
 *   <li>跑一遍容器物品汇总（{@link ContainerItemsExporter}，和
 *       {@code /pc container cache get_all_items} 一样），生成 {@code container/all_items.json}；</li>
 *   <li>读 {@code stock/<名字>/material.json}，处理出 {@code 1_1_material_clean.json}、
 *       {@code 1_2_material_optimized.json} 和 {@code final_final.json}（见 {@link StockMaterials}）。</li>
 * </ol>
 *
 * <p>后面的阶段（取货、分盒重装、合成）还没做，做完这里就先结束。
 */
public class StockStartAction extends Action {

    private enum Stage {
        /** 工作站检查（开容器，多 tick）。 */
        PRECHECK,
        /** 容器物品汇总 + 材料处理。 */
        FINISH_MATERIAL,
        /** 阶段2：取货。 */
        STAGE2
    }

    private final String task;
    private StationCheckAction check;
    private StockStage2Action stage2;
    private Stage stage = Stage.PRECHECK;

    public StockStartAction(String task) {
        this.task = task;
    }

    @Override
    public String name() {
        return "备货 " + task + " 阶段1";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        // 第 1 步的一半：先做「不用开箱子」的预检查（和 /pc station check 同一套）
        StationPrecheck.Result pre = StationPrecheck.run(mc);
        for (String hint : pre.hints()) ChatUtils.info("提示：" + hint);
        if (!pre.ok()) {
            for (String problem : pre.problems()) ChatUtils.error("  · " + problem);
            fail("工作站预检查没通过（" + pre.problems().size() + " 条）");
            return;
        }

        ChatUtils.info("预检查通过，开始 /pc station check（逐个打开容器，不显示界面，要几秒）");
        check = new StationCheckAction();
        check.start();
    }

    @Override
    protected void tick() {
        switch (stage) {
            case PRECHECK -> {
                check.update();
                if (!check.isFinished()) return;

                check.cleanup();
                if (!check.passed()) {
                    fail("工作站检查没通过（容器内容不对，看上面的错误）");
                    return;
                }
                stage = Stage.FINISH_MATERIAL;
            }
            case FINISH_MATERIAL -> finishMaterial();
            case STAGE2 -> {
                stage2.update();
                if (!stage2.isFinished()) return;

                stage2.cleanup();
                if (stage2.failureReason() != null) {
                    fail("阶段2 失败：" + stage2.failureReason());
                    return;
                }
                finish();
            }
        }
    }

    @Override
    protected void onEnd() {
        if (failureReason() != null) {
            ChatUtils.error(name() + "没做完：" + failureReason());
        }
    }

    // ------------------------------------------------------------------

    /** 第 2、3 步：容器物品汇总 + 材料处理（都是同步的）。 */
    private void finishMaterial() {
        // 第 2 步：/pc container cache get_all_items
        Waypoint start = WaypointManager.get().playerStartWaypoint();
        if (start == null) {
            fail("你现在既不在路径点上，也不在任何边上，算不了容器到这里的路");
            return;
        }

        ContainerItemsExporter.Result gathered = ContainerItemsExporter.export(start);
        if (gathered.file() == null) {
            fail("生成 container/all_items.json 失败");
            return;
        }
        ChatUtils.info("容器物品汇总完成：写入 " + gathered.written() + "/" + gathered.containers()
            + " 个容器，overall " + gathered.overallKinds() + " 种 / " + gathered.overallCount() + " 个 → "
            + gathered.file());

        // 第 3 步：material.json -> 三份文件
        try {
            StockMaterials.Result result = StockMaterials.process(task);
            ChatUtils.info("1_1 材料清单：" + result.cleanKinds() + " 种 / " + result.cleanTotal()
                + " 个 → " + result.cleanFile());
            ChatUtils.info("1_2 还要取货：" + result.optimizedKinds() + " 种 / " + result.optimizedTotal()
                + " 个（仓库里已经有的 " + result.skippedCovered() + " 种被扣掉）→ " + result.optimizedFile());
            if (!result.shortItems().isEmpty()) {
                ChatUtils.info("有 " + result.skippedShort() + " 种仓库 + 容器里都不够，已按规则舍弃："
                    + String.join("、", limit(result.shortItems(), 6)));
            }
            ChatUtils.info("已复制一份到 " + result.finalFile());
            ChatUtils.info("第一部分阶段1 完成，接着进入阶段2（取货）");
            stage2 = new StockStage2Action(task);
            stage2.start();
            stage = Stage.STAGE2;
        } catch (Exception e) {
            fail("处理材料清单失败：" + e.getMessage());
        }
    }

    private static List<String> limit(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
