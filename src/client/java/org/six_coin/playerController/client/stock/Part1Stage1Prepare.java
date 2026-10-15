package org.six_coin.playerController.client.stock;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.ContainerPutAction;
import org.six_coin.playerController.client.action.StationCheckAction;
import org.six_coin.playerController.client.action.TakeItemAction;
import org.six_coin.playerController.client.container.ContainerItemsExporter;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationPart;
import org.six_coin.playerController.client.station.StationPos;
import org.six_coin.playerController.client.station.StationPrecheck;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.List;

/**
 * 备货第一部分阶段1（准备）：把干活前的场面摆好，并算出这一趟要取哪些货。
 *
 * <p>这一段自己也拆成几步，用同一个 {@link StepQueue} 线性跑：
 * <ol>
 *   <li>检查工作站（{@link StationCheckAction}，和 {@code /pc stock station check} 同一个动作），
 *       生成 {@code stock/station/check.json}；</li>
 *   <li>把**整个物品栏**（27 格主背包 + 快捷栏 9 格，也就是 {@code everything_include_hotbar}）
 *       里的东西全放进任务前物品暂存处（item_temp）；</li>
 *   <li>从 item_temp 里拿一把**剩余耐久 ≥ {@link StockTask#REQUIRED_PICKAXE_DURABILITY}** 的钻石镐，
 *       放进快捷栏第一格（没有这样的镐就终止，拒绝执行）；</li>
 *   <li>汇总容器物品（{@link ContainerItemsExporter}，和 {@code /pc container cache get_all_items} 一样）
 *       生成 {@code container/all_items.json}，再读 {@code stock/tasks/<名字>/material.json}，
 *       处理出 {@code 1_1_material_clean.json}、{@code 1_2_material_optimized.json}、
 *       {@code final_final.json}，以及 {@code 1_3_material_can_access_all.json} /
 *       {@code final_pack.json}（见 {@link StockMaterials}）。</li>
 * </ol>
 */
final class Part1Stage1Prepare extends StockStep {

    /** 这一步内部的小步骤。 */
    private final StepQueue phases;

    Part1Stage1Prepare(String task, StockRun run) {
        super(task, run, "第一部分阶段1（准备）");
        this.phases = new StepQueue(List.of(
            new CheckStation(), new EmptyInventory(), new TakePickaxe(), new MaterialList()));
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("没有玩家或世界");
            return;
        }

        // 备货过程中有一大堆移动：开着路径点编辑模式会顺手记一堆路径点和边，把图搞乱
        if (WaypointManager.get().setEditMode(false)) {
            ChatUtils.info("检测到路径点编辑模式开着，已自动关闭（备货全程不需要它）");
        }

        // 不用开箱子就能查的那些先查（和 /pc stock station check 同一套）
        StationPrecheck.Result pre = StationPrecheck.run(mc);
        for (String hint : pre.hints()) ChatUtils.info("提示：" + hint);
        if (!pre.ok()) {
            for (String problem : pre.problems()) ChatUtils.error("  · " + problem);
            fail("工作站预检查没通过（" + pre.problems().size() + " 条）");
        }
    }

    @Override
    protected void tick() {
        Action finished = phases.tick();
        if (finished != null) {
            ChatUtils.debug("准备第 " + phases.number() + "/" + phases.total() + " 段完成：" + finished.name());
        }

        Action failed = phases.failedStep();
        if (failed != null) {
            // 每一步的失败原因自己就说得清（「取钻石镐失败：…」这种），这里不再包一层
            fail(failed.failureReason());
            return;
        }
        if (phases.isDone()) {
            finish();
        }
    }

    // ------------------------------------------------------------------
    // 这一步内部的小步骤
    // ------------------------------------------------------------------

    /** 打开工作站里那几个容器挨个数一遍（不显示界面）。 */
    private final class CheckStation extends Action {

        private final StationCheckAction check = new StationCheckAction();

        @Override
        public String name() {
            return "检查工作站";
        }

        @Override
        protected void onStart() {
            ChatUtils.info("开始逐个打开容器数内容（物品暂存处、空潜影盒提供处、存储区、产物区；不显示界面，要几秒）");
            check.start();
        }

        @Override
        protected void tick() {
            check.update();
            if (!check.isFinished()) return;

            check.cleanup();
            if (!check.passed()) {
                fail("工作站检查没通过（容器内容不对，看上面的错误）");
                return;
            }
            ChatUtils.info("工作站检查通过，报告见 stock/station/check.json");
            finish();
        }
    }

    /** 把整个物品栏（连快捷栏）都放进任务前物品暂存处。 */
    private final class EmptyInventory extends Action {

        private ContainerPutAction put;

        @Override
        public String name() {
            return "腾空物品栏";
        }

        @Override
        protected void onStart() {
            StationPos temp = StationManager.get().single(StationPart.ITEM_TEMP);
            if (temp == null) {
                fail("没有设置任务前物品暂存处（item_temp）");
                return;
            }

            ChatUtils.info("先把整个物品栏（连快捷栏）里的东西都放进任务前物品暂存处 " + temp.coordString());
            put = new ContainerPutAction(temp.pos(), ContainerPutAction.Mode.EVERYTHING_INCLUDE_HOTBAR);
            put.start();
        }

        @Override
        protected void tick() {
            put.update();
            if (!put.isFinished()) return;

            put.cleanup();
            if (put.failureReason() != null) {
                fail("把东西放进任务前物品暂存处失败：" + put.failureReason());
                return;
            }
            if (!put.allCleared()) {
                fail("任务前物品暂存处放不下了，还有东西没放进去");
                return;
            }
            finish();
        }
    }

    /** 从 item_temp 里取一把够耐久的钻石镐，放进快捷栏第一格。 */
    private final class TakePickaxe extends Action {

        private TakeItemAction take;

        @Override
        public String name() {
            return "取钻石镐";
        }

        @Override
        protected void onStart() {
            StationPos temp = StationManager.get().single(StationPart.ITEM_TEMP);
            if (temp == null) {
                fail("没有设置任务前物品暂存处（item_temp）");
                return;
            }

            ChatUtils.info("从任务前物品暂存处取一把钻石镐（要求剩余耐久 ≥ "
                + StockTask.REQUIRED_PICKAXE_DURABILITY + "）放进快捷栏第一格");
            take = new TakeItemAction(temp.pos(), StockTask.PICKAXE_HOTBAR,
                Part1Stage1Prepare::isUsablePickaxe,
                "够耐久的钻石镐",
                stack -> stack.isOf(Items.DIAMOND_PICKAXE)
                    ? "钻石镐（剩余耐久 " + remainingDurability(stack) + "）"
                    : null);
            take.start();
        }

        @Override
        protected void tick() {
            take.update();
            if (!take.isFinished()) return;

            take.cleanup();
            if (take.failureReason() != null) {
                fail("取钻石镐失败：" + take.failureReason());
                return;
            }
            if (!take.found()) {
                fail("任务前物品暂存处里没有能用的钻石镐，终止，拒绝执行："
                    + take.notFoundReason()
                    + "（要求剩余耐久 ≥ " + StockTask.REQUIRED_PICKAXE_DURABILITY + "）");
                return;
            }
            if (!checkPickaxeReady("取钻石镐")) return;
            finish();
        }
    }

    /** 容器物品汇总 + 材料清单（都是同步的，一 tick 做完）。 */
    private final class MaterialList extends Action {

        @Override
        public String name() {
            return "材料清单";
        }

        @Override
        protected void onStart() {
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
                StockMaterials.Result result = StockMaterials.process(task);
                ChatUtils.info("1_1 材料清单：" + result.cleanKinds() + " 种 / " + result.cleanTotal()
                    + " 个 → " + result.cleanFile());
                ChatUtils.info("1_2 还要取货：" + result.optimizedKinds() + " 种 / " + result.optimizedTotal()
                    + " 个（仓库里已经有的 " + result.skippedCovered() + " 种被扣掉）→ " + result.optimizedFile()
                    + "，复制成 " + result.finalFile());
                if (!result.shortItems().isEmpty()) {
                    ChatUtils.info("有 " + result.skippedShort() + " 种仓库 + 容器里都不够，已按规则舍弃："
                        + String.join("、", limit(result.shortItems(), 6)));
                }
                ChatUtils.info("1_3 能全量装盒：" + result.packKinds() + " 种 / " + result.packTotal()
                    + " 个（完整需求量，不是只装取回来的那部分）→ " + result.canAccessAllFile()
                    + "，复制成 " + result.finalPackFile());
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
    }

    // ------------------------------------------------------------------
    // 钻石镐
    // ------------------------------------------------------------------

    /** 这一叠算不算「能用的钻石镐」：是钻石镐、而且剩余耐久够。 */
    private static boolean isUsablePickaxe(ItemStack stack) {
        return !stack.isEmpty()
            && stack.isOf(Items.DIAMOND_PICKAXE)
            && remainingDurability(stack) >= StockTask.REQUIRED_PICKAXE_DURABILITY;
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
        ItemStack stack = inventoryStack(StockTask.PICKAXE_HOTBAR);
        if (!isUsablePickaxe(stack)) {
            fail(what + "之后，快捷栏第一格里不是能用的钻石镐（现在是 " + describeStack(stack)
                + "，要求剩余耐久 ≥ " + StockTask.REQUIRED_PICKAXE_DURABILITY + "）");
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

    private static List<String> limit(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
