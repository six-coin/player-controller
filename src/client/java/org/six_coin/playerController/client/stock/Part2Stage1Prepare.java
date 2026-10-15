package org.six_coin.playerController.client.stock;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.container.ContainerItemsExporter;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationPart;
import org.six_coin.playerController.client.stock.station.StationPos;
import org.six_coin.playerController.client.stock.station.StationState;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 备货第二部分阶段1（预检查）：算清楚这一部分要合成什么、还要去取哪些原材料。
 *
 * <p>按需求：
 * <ol>
 *   <li>接着用前面的 station_data（{@link StockRun}，内存里传的）；</li>
 *   <li>再跑一遍 {@code /pc container cache get_all_items}，重新生成 {@code container/all_items.json}
 *       （第一部分取过货的容器，缓存已经刷新过了）；</li>
 *   <li>读 {@code stock/tasks/<名字>/material.json}（跟第一部分同一份），按附件 2.1.3 处理出
 *       {@code 2_1_process_raw.json} … {@code 2_5_material_cleaner.json}、
 *       {@code final_material.json}、{@code final_process.json}、{@code final_steps.json} 和
 *       {@code unreachable.json}（见 {@link StockProcessMaterials}）。</li>
 * </ol>
 *
 * <p>这一步全是同步计算，一 tick 就做完。
 */
final class Part2Stage1Prepare extends StockStep {

    Part2Stage1Prepare(String task, StockRun run) {
        super(task, run, "第二部分阶段1（预检查）");
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        StationState state = run.state();
        if (state == null) {
            fail("station_data 只在这一轮任务的内存里传，得有前面取货 / 分盒那几步交过来才行");
            return;
        }

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

        try {
            // current_item_storage_and_shulker_boxes：仓库里的（散装 + 盒子里的）**加上**
            // 摆放处上那些盒子里的（id=1 的暂存盒除外）—— 那些东西也就在手边，随手能掏出来用
            Map<String, Integer> currentItemStorageAndShulkerBoxes =
                new TreeMap<>(state.storageItems());
            for (Map.Entry<String, Integer> entry : state.placementItemsExcept(
                Part2Stage3Craft.STAGING_ID).entrySet()) {
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
        } catch (Exception e) {
            fail("处理材料清单失败：" + e.getMessage());
            return;
        }

        finish();
    }

    @Override
    protected void tick() {
        // 同步做完：活都在 onStart 里干完了
    }

    // ------------------------------------------------------------------

    private static String join(List<String> list) {
        int max = 6;
        if (list.size() <= max) return String.join("、", list);
        return String.join("、", list.subList(0, max)) + " 等 " + list.size() + " 个";
    }
}
