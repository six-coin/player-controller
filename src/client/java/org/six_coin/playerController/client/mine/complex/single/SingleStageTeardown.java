package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.mine.MineRegion;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.stock.WaitAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 需求 (6)：拆掉临时工作站。
 *
 * <ol>
 *   <li>六个盒子逐个「挖掉 → snap 到它的位置 → <b>等物品栏把它吸进来</b> → snap 回临时站立点 → 装回物品栏」。
 *       装回去时**内容多的排在前面**：圆石盒 → 背包 6 / 7 / 8（先找空着的那一格），
 *       黏液块盒 → 背包 9 / 10 / 11，收集盒 → 当前的两个收集位。
 *       这样补货检查看到的顺序还是「满、半、空」，和开局的要求一致；</li>
 *   <li>倒熔岩（{@link SingleStageLavaAction}）：站到 6 6 6，假转头到 yaw 135 / pitch 30，
 *       用背包 12 的熔岩桶放一次、收一次（把放垃圾那地方的东西烧掉），然后把桶归位；</li>
 *   <li>平台 + 4 5 4 那个圆石改成**快挖**：把它们一起框进 mine simple 的范围，等它挖完再停
 *       （{@link SingleStageCtx#platformRegion()}）。<b>拆完不捡掉落物</b>。</li>
 * </ol>
 */
class SingleStageTeardown extends SingleStageStep {

    /** 圆石盒的三个位置（多的往前放）。 */
    private static final int[] COBBLE_SLOTS = {SingleStageCtx.COBBLE_BOX_A,
        SingleStageCtx.COBBLE_BOX_B, SingleStageCtx.COBBLE_BOX_FULL};

    /** 黏液块盒的三个位置（多的往前放）。 */
    private static final int[] SLIME_SLOTS = {SingleStageCtx.SLIME_BOX_A,
        SingleStageCtx.SLIME_BOX_B, SingleStageCtx.SLIME_BOX_FULL};

    /** 等快挖挖完最多等多久。 */
    private static final int MAX_DIG_WAIT_TICKS = 20 * 120;

    /** 盒子顺序最多整理几轮（每轮换完都会复核一次）。 */
    private static final int MAX_ORDER_ROUNDS = 3;

    private final SingleStageQueue queue = new SingleStageQueue();

    /** 是不是我们自己开的 mine simple（关的时候只关自己开的那个）。 */
    private boolean mining;
    /** 盒子顺序整理了几轮。 */
    private int orderRounds;

    SingleStageTeardown(SingleStageCtx ctx) {
        super(ctx);
    }

    @Override
    protected String phase() {
        return "拆临时工作站";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }
        buildPlan();
    }

    @Override
    protected void tick() {
        if (isFinished()) return;
        if (queue.tick()) {
            if (queue.failed() != null) {
                fail(queue.failed().failureReason());
            } else {
                finish();
            }
        }
    }

    // ------------------------------------------------------------------

    private void buildPlan() {
        queue.child("snap 到临时站立点",
            () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

        // 圆石盒 → 背包 6 / 7 / 8（多的在前）
        queue.sync("拆圆石盒", () -> {
            List<BlockPos> boxes = byContent(ctx.cobbleSpotA(), ctx.cobbleSpotB());
            queueBreak(boxes.get(0), COBBLE_SLOTS);
            queueBreak(boxes.get(1), COBBLE_SLOTS);
        });

        // 黏液块盒 → 背包 9 / 10 / 11（多的在前）
        queue.sync("拆粘液块盒", () -> {
            List<BlockPos> boxes = byContent(ctx.slimeSpotA(), ctx.slimeSpotB());
            queueBreak(boxes.get(0), SLIME_SLOTS);
            queueBreak(boxes.get(1), SLIME_SLOTS);
        });

        // 收集盒 → 当前的两个收集位（多的在前）
        queue.sync("拆收集盒", () -> {
            List<BlockPos> boxes = byContent(ctx.collectSpotA(), ctx.collectSpotB());
            queueBreak(boxes.get(0), new int[]{ctx.collectSlot, ctx.collectSlot + 1});
            queueBreak(boxes.get(1), new int[]{ctx.collectSlot, ctx.collectSlot + 1});
        });

        // 拆完之后再确认一遍「多的在前」
        queue.sync("整理盒子顺序", this::queueFixOrder);

        // 拆平台之前先把熔岩桶用掉（烧掉放垃圾那地方的东西）
        queue.child("走到倒岩浆的位置",
            () -> new SingleStageSnapAction(ctx, ctx.lavaStandPoint()));
        queue.child("倒熔岩", () -> new SingleStageLavaAction(ctx));

        // 平台 + 4 5 4：快挖，挖完不捡掉落物
        queue.sync("快挖平台", this::queueFastBreakPlatform);
    }

    /**
     * 拆完再核对一遍：这几组盒子里的东西必须是**单调递减**的，不是就把两格换过来。
     *
     * <p>换的时候用 `SlotActionType.SWAP`（点格子 + 按数字键）三下完成一次交换：
     * `a↔临时快捷栏格`、`b↔临时格`、`a↔临时格` —— 全程不碰光标，比「拿起 / 放下」稳。
     * 换完再复核一遍（最多 {@value #MAX_ORDER_ROUNDS} 轮）。
     */
    private void queueFixOrder() {
        if (orderRounds >= MAX_ORDER_ROUNDS) return;
        orderRounds++;

        List<SingleStageInventoryAction.Op> ops = new ArrayList<>();
        List<String> report = new ArrayList<>();
        ops.addAll(orderOps(new int[]{SingleStageCtx.COBBLE_BOX_A, SingleStageCtx.COBBLE_BOX_B,
            SingleStageCtx.COBBLE_BOX_FULL}, report));
        ops.addAll(orderOps(new int[]{SingleStageCtx.SLIME_BOX_A, SingleStageCtx.SLIME_BOX_B,
            SingleStageCtx.SLIME_BOX_FULL}, report));
        ops.addAll(orderOps(new int[]{ctx.collectSlot, ctx.collectSlot + 1}, report));

        if (ops.isEmpty()) {
            ChatUtils.debug("盒子顺序本来就是递减的（" + String.join("，", report) + "）");
            return;
        }

        ChatUtils.info("盒子数量不是递减的，调换一下顺序（" + String.join("，", report) + "）");
        queue.child("调换盒子顺序", () -> new SingleStageInventoryAction(ctx, ops));
        // 换完再复核一遍
        queue.sync("复核盒子顺序", this::queueFixOrder);
    }

    /**
     * 让这几格按「多 → 少」排好，返回要做的交换操作（冒泡，几格而已）。
     *
     * @param report 顺便把「第几格 = 多少个」记下来，日志里好定位
     */
    private List<SingleStageInventoryAction.Op> orderOps(int[] slots, List<String> report) {
        List<SingleStageInventoryAction.Op> ops = new ArrayList<>();
        int n = slots.length;
        int[] cur = slots.clone();
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) counts[i] = boxItems(cur[i]);

        StringBuilder line = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) line.append('/');
            line.append(cur[i]).append('=').append(counts[i]);
        }
        report.add(line.toString());

        for (int pass = 0; pass < n; pass++) {
            boolean swapped = false;
            for (int i = 0; i + 1 < n; i++) {
                if (counts[i] >= counts[i + 1]) continue;

                List<SingleStageInventoryAction.Op> swap = swapOps(cur[i], cur[i + 1]);
                if (swap.isEmpty()) return ops;      // 没地方周转，先这样

                ops.addAll(swap);
                int c = counts[i];
                counts[i] = counts[i + 1];
                counts[i + 1] = c;
                int s = cur[i];
                cur[i] = cur[i + 1];
                cur[i + 1] = s;
                swapped = true;
            }
            if (!swapped) break;
        }
        return ops;
    }

    /**
     * 交换物品栏里两格的三个操作（用 SWAP 点格子，不碰光标）：
     * {@code a↔h}、{@code b↔h}、{@code a↔h}，{@code h} 是一格空的快捷栏。
     */
    private List<SingleStageInventoryAction.Op> swapOps(int a, int b) {
        int hotbar = firstFreeHotbar();
        if (hotbar < 0) return List.of();
        return List.of(
            SingleStageInventoryAction.swap(a, hotbar),
            SingleStageInventoryAction.swap(b, hotbar),
            SingleStageInventoryAction.swap(a, hotbar));
    }

    private int boxItems(int slot) {
        int total = 0;
        for (ItemStack stack : ShulkerUtils.contents(ctx.live(slot))) total += stack.getCount();
        return total;
    }

    private int firstFreeHotbar() {
        for (int slot = SingleStageCtx.HOTBAR_FREE_1; slot <= SingleStageCtx.HOTBAR_FREE_3; slot++) {
            if (ctx.live(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** 内容多的排前面。 */
    private List<BlockPos> byContent(BlockPos a, BlockPos b) {
        List<BlockPos> boxes = new ArrayList<>(List.of(a, b));
        boxes.sort(Comparator.comparingInt(ctx.data::boxItems).reversed());
        return boxes;
    }

    // ------------------------------------------------------------------
    // 拆一个盒子
    // ------------------------------------------------------------------

    /**
     * 挖掉一个盒子、等物品栏吸进来、再装到 {@code candidates} 里第一个空着的位置。
     *
     * <p>候选格都占着（很少见）就退到背包 22~27 的空格，实在没有就报错。
     */
    private void queueBreak(BlockPos spot, int[] candidates) {
        queue.sync("拆 " + spot.toShortString(), () -> {
            List<ItemStack> contents = ctx.data.box(spot);
            Map<Integer, String> before = ctx.boxSignatures();
            int boxesBefore = ctx.shulkerBoxCount();

            queue.child("快挖掉 " + spot.toShortString(),
                () -> MineBlockAction.fastEmpty(spot));
            queue.child("snap 到 " + spot.toShortString(),
                () -> new SingleStageSnapAction(ctx, spot));
            // 必须等物品栏真的把盒子吸进来（个数 +1 且认得出是哪一格）才回临时站立点，
            // 不然会提前飞回去，盒子留在原地（5 秒等不到就算失败）
            queue.child("等物品栏吸到盒子",
                () -> new SingleStageWaitItemAction(ctx, contents, before, boxesBefore, false));
            queue.child("snap 回临时站立点",
                () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

            queue.sync("把盒子装回物品栏", () -> {
                int found = ctx.findPickedBox(before, contents);
                if (found < 0) {
                    fail("找不到刚挖下来的盒子（" + spot.toShortString() + "）");
                    queue.cancel();
                    return;
                }
                int dest = destination(candidates);
                if (dest < 0) {
                    fail("物品栏里没有空格放回 " + spot.toShortString() + " 的盒子了");
                    queue.cancel();
                    return;
                }
                queue.child("挪到物品栏第 " + dest + " 格",
                    () -> new SingleStageInventoryAction(ctx,
                        List.of(SingleStageInventoryAction.move(found, dest))));
            });
        });
    }

    /** 候选格里第一个空着的；都占着就找背包 22~27；都没有返回 -1。 */
    private int destination(int[] candidates) {
        for (int slot : candidates) {
            if (ctx.live(slot).isEmpty()) return slot;
        }
        for (int slot = SingleStageCtx.FILLER_START; slot <= SingleStageCtx.FILLER_END; slot++) {
            if (ctx.live(slot).isEmpty()) return slot;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 快挖平台
    // ------------------------------------------------------------------

    private void queueFastBreakPlatform() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            fail("世界不存在");
            return;
        }

        MineRegion region = ctx.platformRegion();
        MineManager.get().startTransient(region);
        mining = true;
        ChatUtils.info("拆平台：mine simple 范围设为 " + region.describe());

        // 平台从正上方挖（站在临时站立点够不到最远的角），4 5 4 再回站立点挖
        queue.child("挪到平台上方", () -> new SingleStageSnapAction(ctx, ctx.platformHover()));
        queue.child("等平台挖完", () -> new WaitAction("平台挖完",
            () -> platformCleared(), MAX_DIG_WAIT_TICKS, false));
        queue.child("回临时站立点", () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
        queue.child("等 4 5 4 挖完", () -> new WaitAction("4 5 4 挖完",
            this::markerCleared, MAX_DIG_WAIT_TICKS, false));
        queue.sync("停掉 mine simple", () -> {
            MineManager.get().stop();
            mining = false;
        });
    }

    /** 平台 20 格是不是都空了。 */
    private boolean platformCleared() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        for (BlockPos cell : ctx.platform()) {
            if (!cleared(mc.level.getBlockState(cell))) return false;
        }
        return true;
    }

    /** 4 5 4 那个圆石是不是挖掉了。 */
    private boolean markerCleared() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null || cleared(mc.level.getBlockState(ctx.marker()));
    }

    /**
     * 这一格算不算「挖掉了」。
     *
     * <p>空气或者流体都算：圆石挖掉之后如果被水 / 熔岩填了，就不是空气了，
     * 只看空气会一直等到超时。
     */
    private static boolean cleared(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    @Override
    protected void onEnd() {
        // 半路失败 / 被取消也要把 mine simple 停掉（不然它会一直挖下去）
        if (mining) {
            MineManager.get().stop();
            mining = false;
        }
    }
}
