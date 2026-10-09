package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import org.six_coin.playerController.client.container.ContainerItemsExporter;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.station.StationPrecheck;
import org.six_coin.playerController.client.stock.StockMaterials;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;

/**
 * {@code /pc stock task <名字> start} 的执行体 —— 备货第一部分。
 *
 * <p>按顺序做这些事：
 * <ol>
 *   <li>跑一遍工作站检查（{@link StationCheckAction}，和 {@code /pc station check} 同一个动作），
 *       生成 {@code station/check.json}；</li>
 *   <li>把**整个物品栏**（27 格主背包 + 快捷栏 9 格，也就是 {@code everything_include_hotbar}）
 *       里的东西全放进任务前物品暂存处（item_temp）；</li>
 *   <li>从 item_temp 里拿一把**剩余耐久 ≥ 1000** 的钻石镐，放进快捷栏第一格
 *       （没有这样的镐就终止，拒绝执行）；</li>
 *   <li>跑一遍容器物品汇总（{@link ContainerItemsExporter}，和
 *       {@code /pc container cache get_all_items} 一样），生成 {@code container/all_items.json}；</li>
 *   <li>读 {@code stock/<名字>/material.json}，处理出 {@code 1_1_material_clean.json}、
 *       {@code 1_2_material_optimized.json} 和 {@code final_final.json}（见 {@link StockMaterials}）；</li>
 *   <li>阶段2：取货（{@link StockStage2Action}）；</li>
 *   <li>阶段3：分盒重装（{@link StockStage3Action}）。</li>
 * </ol>
 *
 * <p>第二部分（收集需要合成的材料 / 合成）还没做，做完这里就先结束。
 */
public class StockStartAction extends Action {

    /** 钻石镐至少要剩这么多耐久，不然拒绝执行（需求指定的）。 */
    public static final int REQUIRED_PICKAXE_DURABILITY = 1000;

    /** 钻石镐放快捷栏第一格（背包下标 0）。 */
    public static final int PICKAXE_HOTBAR = 0;

    private enum Stage {
        /** 工作站检查（开容器，多 tick）。 */
        PRECHECK,
        /** 把整个物品栏（含快捷栏）里的东西全放进 item_temp。 */
        PUT_TEMP,
        /** 取钻石镐放进快捷栏第一格。 */
        TAKE_PICKAXE,
        /** 容器物品汇总 + 材料处理。 */
        FINISH_MATERIAL,
        /** 阶段2：取货。 */
        STAGE2,
        /** 阶段3：分盒重装。 */
        STAGE3
    }

    private final String task;
    private StationCheckAction check;
    private ContainerPutAction putTemp;
    private TakeItemAction takePickaxe;
    private StockStage2Action stage2;
    private StockStage3Action stage3;
    private Stage stage = Stage.PRECHECK;

    public StockStartAction(String task) {
        this.task = task;
    }

    @Override
    public String name() {
        return "备货 " + task + " 第一部分";
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
                startPutTemp();
            }
            case PUT_TEMP -> {
                putTemp.update();
                if (!putTemp.isFinished()) return;

                putTemp.cleanup();
                if (putTemp.failureReason() != null) {
                    fail("把东西放进任务前物品暂存处失败：" + putTemp.failureReason());
                    return;
                }
                if (!putTemp.allCleared()) {
                    fail("任务前物品暂存处放不下了，还有东西没放进去");
                    return;
                }
                startTakePickaxe();
            }
            case TAKE_PICKAXE -> {
                takePickaxe.update();
                if (!takePickaxe.isFinished()) return;

                takePickaxe.cleanup();
                if (takePickaxe.failureReason() != null) {
                    fail("取钻石镐失败：" + takePickaxe.failureReason());
                    return;
                }

                if (!takePickaxe.found()) {
                    fail("任务前物品暂存处里没有能用的钻石镐，终止，拒绝执行："
                        + takePickaxe.notFoundReason()
                        + "（要求剩余耐久 ≥ " + REQUIRED_PICKAXE_DURABILITY + "）");
                    return;
                }
                if (!checkPickaxeReady("取钻石镐")) return;
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
                startStage3();
            }
            case STAGE3 -> {
                stage3.update();
                if (!stage3.isFinished()) return;

                stage3.cleanup();
                if (stage3.failureReason() != null) {
                    fail("阶段3 失败：" + stage3.failureReason());
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

    /** 第 2 步：把**整个物品栏**（27 格主背包 + 快捷栏 9 格）里的东西全放进任务前物品暂存处。 */
    private void startPutTemp() {
        StationPos temp = StationManager.get().single(StationPart.ITEM_TEMP);
        if (temp == null) {
            fail("没有设置任务前物品暂存处（item_temp）");
            return;
        }

        ChatUtils.info("先把整个物品栏（连快捷栏）里的东西都放进任务前物品暂存处 " + temp.coordString());
        putTemp = new ContainerPutAction(temp.pos(), ContainerPutAction.Mode.EVERYTHING_INCLUDE_HOTBAR);
        putTemp.start();
        stage = Stage.PUT_TEMP;
    }

    /** 第 3 步：从 item_temp 里取一把钻石镐，放进快捷栏第一格。 */
    private void startTakePickaxe() {
        StationPos temp = StationManager.get().single(StationPart.ITEM_TEMP);
        if (temp == null) {
            fail("没有设置任务前物品暂存处（item_temp）");
            return;
        }

        ChatUtils.info("从任务前物品暂存处取一把钻石镐（要求剩余耐久 ≥ "
            + REQUIRED_PICKAXE_DURABILITY + "）放进快捷栏第一格");
        takePickaxe = new TakeItemAction(temp.pos(), PICKAXE_HOTBAR,
            StockStartAction::isUsablePickaxe,
            "够耐久的钻石镐",
            stack -> stack.isOf(Items.DIAMOND_PICKAXE)
                ? "钻石镐（剩余耐久 " + remainingDurability(stack) + "）"
                : null);
        takePickaxe.start();
        stage = Stage.TAKE_PICKAXE;
    }

    // ------------------------------------------------------------------
    // 钻石镐
    // ------------------------------------------------------------------

    /** 这一叠算不算「能用的钻石镐」：是钻石镐、而且剩余耐久够。 */
    private static boolean isUsablePickaxe(ItemStack stack) {
        return !stack.isEmpty()
            && stack.isOf(Items.DIAMOND_PICKAXE)
            && remainingDurability(stack) >= REQUIRED_PICKAXE_DURABILITY;
    }

    /** 剩余耐久（没耐久的物品就是 0）。 */
    private static int remainingDurability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamage();
    }

    private static ItemStack inventoryStack(int index) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getInventory().getStack(index);
    }

    /** 确认快捷栏第一格真的就位了。 */
    private boolean checkPickaxeReady(String what) {
        ItemStack stack = inventoryStack(PICKAXE_HOTBAR);
        if (!isUsablePickaxe(stack)) {
            fail(what + "之后，快捷栏第一格里不是能用的钻石镐（现在是 " + describeStack(stack)
                + "，要求剩余耐久 ≥ " + REQUIRED_PICKAXE_DURABILITY + "）");
            return false;
        }
        ChatUtils.info("钻石镐就位：快捷栏第一格，剩余耐久 " + remainingDurability(stack));
        return true;
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Items.DIAMOND_PICKAXE == stack.getItem()
            ? "钻石镐（剩余耐久 " + remainingDurability(stack) + "）"
            : Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }

    /** 第 4、5 步：容器物品汇总 + 材料处理（都是同步的）。 */
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
            ChatUtils.info("第一阶段 阶段1 完成，接着进入阶段2（取货）");
            stage2 = new StockStage2Action(task);
            stage2.start();
            stage = Stage.STAGE2;
        } catch (Exception e) {
            fail("处理材料清单失败：" + e.getMessage());
        }
    }

    /** 阶段3：分盒重装（把收集到的东西装进潜影盒，盒子放去 item_final）。 */
    private void startStage3() {
        ChatUtils.info("阶段2 完成，接着进入阶段3（分盒重装）");
        stage3 = new StockStage3Action(task, stage2 == null ? null : stage2.state());
        stage3.start();
        stage = Stage.STAGE3;
    }

    private static List<String> limit(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
