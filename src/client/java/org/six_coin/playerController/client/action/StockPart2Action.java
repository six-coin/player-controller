package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.ContainerItemsExporter;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.station.StationState;
import org.six_coin.playerController.client.stock.StockManager;
import org.six_coin.playerController.client.stock.StockProcessMaterials;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 备货「第二部分」：收集需要合成的材料。
 *
 * <p>阶段1（预检查）：
 * <ol>
 *   <li>从第一部分阶段3 继承 station_data（不落盘，只在内存里传）；</li>
 *   <li>再跑一遍 {@code /pc container cache get_all_items}，重新生成 {@code container/all_items.json}
 *       （第一部分取过货的容器，缓存已经刷新过了）；</li>
 *   <li>读 {@code stock/<名字>/material.json}（跟第一部分同一份），按附件 2.1.3 处理出
 *       {@code 2_1_process_raw.json} … {@code 2_5_material_cleaner.json}、
 *       {@code final_material.json}、{@code final_process.json}（见 {@link StockProcessMaterials}）。</li>
 * </ol>
 *
 * <p>阶段2（取货）：读 {@code final_material.json}，跟第一部分阶段2 走同一套
 * （{@link StockStage2Action}），取回来的东西都放 item_storage。
 *
 * <p>阶段3（合成）：读 {@code final_steps.json} 一步步合成（{@link StockPart2Stage3Action}）。
 */
public class StockPart2Action extends Action {

    private enum Stage {
        /** 阶段1 的同步部分：容器汇总 + 处理材料清单。 */
        PREPARE,
        /** 阶段2：取货。 */
        STAGE2,
        /** 阶段3：合成。 */
        STAGE3
    }

    private final String task;
    /** 第一部分交过来的 station_data（必须有：它不落盘，传 null 就直接失败）。 */
    @Nullable
    private final StationState inheritedState;

    private StationState state;
    private StockStage2Action stage2;
    private StockPart2Stage3Action stage3;
    private Stage stage = Stage.PREPARE;

    public StockPart2Action(String task) {
        this(task, null);
    }

    public StockPart2Action(String task, @Nullable StationState inheritedState) {
        this.task = task;
        this.inheritedState = inheritedState;
    }

    @Override
    public String name() {
        return "备货 " + task + " 第二部分";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        // 阶段1 第 1 步：station_data（只从第一部分传过来，不落盘）
        if (inheritedState == null) {
            fail("station_data 不落盘（只在一次任务的内存里传），第二部分只能从 "
                + "/pc stock task <名字> start 一路跑下来");
            return;
        }
        state = inheritedState;
        ChatUtils.debug("第二部分：接着用第一部分阶段3 交过来的 station_data");

        // 第二部分的容器操作都在站立点上做，人不在就先提醒一句（不然会以「超出触及范围」失败）
        StationPos stand = StationManager.get().single(StationPart.STAND_POINT);
        if (stand != null) {
            BlockPos here = PlayerUtils.currentBlockPos();
            if (here == null || !here.equals(stand.pos())) {
                ChatUtils.error("你现在不在站立点 " + stand.coordString() + " 上（在 "
                    + (here == null ? "?" : here.toShortString()) + "）；第二部分的容器都在站立点的触及范围内，"
                    + "建议先用 /pc move to_position " + stand.coordString() + " 过去");
            }
        }
    }

    @Override
    protected void tick() {
        switch (stage) {
            case PREPARE -> prepare();
            case STAGE2 -> {
                stage2.update();
                if (!stage2.isFinished()) return;

                stage2.cleanup();
                if (stage2.failureReason() != null) {
                    fail("第二阶段2 失败：" + stage2.failureReason());
                    return;
                }
                ChatUtils.info("第二阶段2 完成，接着进入阶段3（合成）");
                stage3 = new StockPart2Stage3Action(task, state);
                stage3.start();
                stage = Stage.STAGE3;
            }
            case STAGE3 -> {
                stage3.update();
                if (!stage3.isFinished()) return;

                stage3.cleanup();
                if (stage3.failureReason() != null) {
                    fail("第二阶段3 失败：" + stage3.failureReason());
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

    /** 阶段1：容器汇总 + 材料清单处理（都是同步的）。 */
    private void prepare() {
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

        // 第 3 步：material.json -> 2_x 一堆文件 + final_steps + unreachable
        try {
            // current_item_storage_and_shulker_boxes：仓库里的（散装 + 盒子里的）**加上**
            // 摆放处上那些盒子里的（id=1 的暂存盒除外）—— 那些东西也就在手边，随手能掏出来用
            Map<String, Integer> currentItemStorageAndShulkerBoxes =
                new TreeMap<>(state.storageItems());
            for (Map.Entry<String, Integer> entry : state.placementItemsExcept(
                StockPart2Stage3Action.STAGING_ID).entrySet()) {
                currentItemStorageAndShulkerBoxes.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }

            StockProcessMaterials.Result result = StockProcessMaterials.process(
                StockProcessMaterials.filesFor(task), currentItemStorageAndShulkerBoxes);

            ChatUtils.info("2_1 去掉收过的最终产物，剩 " + result.rawProducts() + " 个 → " + result.processRawFile());
            ChatUtils.info("2_2 原材料：" + result.rawMaterialKinds() + " 种 / "
                + result.rawMaterialTotal() + " 个 → " + result.materialRawFile());
            ChatUtils.info("2_3 仓库 + 容器凑得齐的原材料：" + result.cleanMaterialKinds() + " 种 → "
                + result.materialCleanFile());
            if (result.shortMaterialKinds() > 0) {
                ChatUtils.info("有 " + result.shortMaterialKinds() + " 种凑不齐，已舍弃："
                    + join(result.shortItems()));
            }
            ChatUtils.info("2_4 能全量合成的最终产物：" + result.keptProducts() + " 个 → "
                + result.processCleanFile());
            if (!result.droppedProducts().isEmpty()) {
                ChatUtils.info("（丢掉的最终产物 " + result.droppedProducts().size() + " 个："
                    + join(result.droppedProducts()) + "）");
            }
            ChatUtils.info("2_5 原材料（合成要用的全部）：" + result.cleanerMaterialKinds() + " 种 / "
                + result.cleanerMaterialTotal() + " 个 → " + result.materialCleanerFile());
            ChatUtils.info("2_6 扣掉仓库里已经有的，还要去取：" + result.finalMaterialKinds() + " 种 / "
                + result.finalMaterialTotal() + " 个 → " + result.materialFinalFile()
                + "，复制成 " + result.finalMaterialFile());
            ChatUtils.info("final_process.json（阶段3 合成用）也写好了 → " + result.finalProcessFile());
            ChatUtils.info("final_steps.json：" + result.stepCount() + " 步 → " + result.finalStepsFile());
            ChatUtils.info("unreachable.json（整个过程拿不到的最终产物）："
                + result.unreachableKinds() + " 种 / " + result.unreachableTotal() + " 个 → "
                + result.unreachableFile());
            if (!result.unreachable().isEmpty()) {
                ChatUtils.info("  拿不到的：" + join(result.unreachable()));
            }

            // 阶段2：取货（用第二部分自己的文件，卸货都进 item_storage）
            stage2 = new StockStage2Action(task, "第二阶段2（取货）",
                StockManager.get().currentFinalMaterialFile(task),
                StationPart.ITEM_STORAGE, StationPart.ITEM_STORAGE, state);
            stage2.start();
            stage = Stage.STAGE2;
        } catch (Exception e) {
            fail("处理材料清单失败：" + e.getMessage());
        }
    }

    private static String join(List<String> list) {
        int max = 6;
        if (list.size() <= max) return String.join("、", list);
        return String.join("、", list.subList(0, max)) + " 等 " + list.size() + " 个";
    }
}
