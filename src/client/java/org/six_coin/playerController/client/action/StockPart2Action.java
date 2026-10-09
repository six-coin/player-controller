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

/**
 * 备货「第二部分」：收集需要合成的材料。
 *
 * <p>阶段1（预检查）：
 * <ol>
 *   <li>从第一部分阶段3 继承 station_data（单独跑的时候从 {@code debug/station_data.json} 读）；</li>
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
 * <p>阶段3（合成）还没做，这里跑完就先结束。
 */
public class StockPart2Action extends Action {

    private enum Stage {
        /** 阶段1 的同步部分：容器汇总 + 处理材料清单。 */
        PREPARE,
        /** 阶段2：取货。 */
        STAGE2
    }

    private final String task;
    /** 第一部分交过来的 station_data；null = 从 debug/station_data.json 读。 */
    @Nullable
    private final StationState inheritedState;

    private StationState state;
    private StockStage2Action stage2;
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

        // 阶段1 第 1 步：station_data
        if (inheritedState != null) {
            state = inheritedState;
            ChatUtils.debug("第二部分：接着用第一部分阶段3 交过来的 station_data");
        } else {
            state = StationState.loadDebug();
            if (state == null) {
                fail("找不到 debug/station_data.json（单独跑第二部分要先跑一遍第一部分，"
                    + "或者把它删了重新完整跑一次）");
                return;
            }
            ChatUtils.info("第二部分：从 debug/station_data.json 读的 station_data"
                + "（如果这之后手动动过仓库，数据可能对不上）");
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
                ChatUtils.info("第二部分阶段1、阶段2 完成；阶段3（合成）还没做");
                finish();
            }
        }
    }

    @Override
    protected void onEnd() {
        if (failureReason() != null) {
            ChatUtils.error(name() + "没做完：" + failureReason());
            if (state != null) {
                ChatUtils.info("把当前的 station_data 也写一份，方便看进度");
                state.saveDebug();
            }
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

        // 第 3 步：material.json -> 2_x 一堆文件
        try {
            StockProcessMaterials.Result result =
                StockProcessMaterials.process(StockProcessMaterials.filesFor(task), state.storageItems());

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
            ChatUtils.info("2_5 最终要取的原材料：" + result.finalMaterialKinds() + " 种 / "
                + result.finalMaterialTotal() + " 个 → " + result.materialCleanerFile());
            ChatUtils.info("final_material.json（阶段2 照它取货）和 final_process.json（阶段3 合成用）都写好了");

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
