package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 需求 (3) + (4)：snap 到临时站立点，然后把「临时工作站」搭起来。
 *
 * <p>顺序（和需求里略有出入，都是为了放得上去）：
 * <ol>
 *   <li>snap 到临时站立点 6 4 6；</li>
 *   <li>放两个粘液块盒（7 3 7 / 7 3 8）和两个圆石盒（8 3 7 / 8 3 8）；</li>
 *   <li>从圆石盒里取 21 个圆石放到快捷栏 6；</li>
 *   <li>用这 21 个圆石搭 4×5 的平台（6 2 6 ~ 9 2 10），再在 4 5 4 放一个
 *       —— 搭平台的时候先挪到平台正上方（{@link SingleStageCtx#platformHover()}）：
 *       从临时站立点看，最远的那个角差不多 6 格，超出服务端允许的交互距离，放不上去；</li>
 *   <li>最后放两个收集盒（9 3 7 / 9 3 8）—— 放到平台之后，盒子下面就有东西垫着了。</li>
 * </ol>
 *
 * <p>盒子怎么挑（需求里的「有空的，就放剩下的两个」）：
 * <ul>
 *   <li>三个盒子里有空盒 → <b>留一个空盒在背包</b>，把另外两个放出去（所以剩下那两个里可能还有一个空的）；</li>
 *   <li>都不空 → 放内容最少的两个。</li>
 * </ul>
 */
class SingleStageSetup extends SingleStageStep {

    private static final int PLATFORM_COBBLE = 21;

    private final SingleStageQueue queue = new SingleStageQueue();

    SingleStageSetup(SingleStageCtx ctx) {
        super(ctx);
    }

    @Override
    protected String phase() {
        return "搭临时工作站";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
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
        queue.sync("刷新物品栏", ctx::refresh);
        queue.child("snap 到临时站立点",
            () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

        queue.sync("放粘液块盒", () -> {
            List<Integer> slots = chooseTwo(SingleStageCtx.SLIME_BOX_A,
                SingleStageCtx.SLIME_BOX_B, SingleStageCtx.SLIME_BOX_FULL);
            queuePlaceBox(ctx.slimeSpotA(), slots.get(0), "粘液块", true);
            queuePlaceBox(ctx.slimeSpotB(), slots.get(1), "粘液块", true);
        });

        queue.sync("放圆石盒", () -> {
            List<Integer> slots = chooseTwo(SingleStageCtx.COBBLE_BOX_A,
                SingleStageCtx.COBBLE_BOX_B, SingleStageCtx.COBBLE_BOX_FULL);
            queuePlaceBox(ctx.cobbleSpotA(), slots.get(0), "圆石", true);
            queuePlaceBox(ctx.cobbleSpotB(), slots.get(1), "圆石", true);
        });

        queue.sync("取圆石搭平台", this::queueTakeCobble);

        queue.sync("搭平台", () -> {
            // 从临时站立点放不到最远的那个角（超出服务端允许的交互距离），先挪到平台正上方
            queue.child("挪到平台上方", () -> new SingleStageSnapAction(ctx, ctx.platformHover()));
            queue.child("放平台 20 格", () -> new SingleStagePlaceBatchAction(ctx, ctx.platform(),
                COBBLE, new int[]{SingleStageCtx.HOTBAR_FREE_1}, new int[0], "圆石"));
            queue.child("回临时站立点", () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
            // 4 5 4 那个单独放的圆石（从站立点够得着）
            queue.child("放 4 5 4 的那个圆石", () -> new SingleStagePlaceBatchAction(ctx,
                List.of(ctx.marker()), COBBLE, new int[]{SingleStageCtx.HOTBAR_FREE_1},
                new int[0], "圆石"));
        });

        queue.sync("放收集盒", this::queuePlaceCollectBoxes);
    }

    /**
     * 三个盒子里挑两个放出去。
     *
     * <p>有空盒 → 留一个空盒在背包、另外两个放出去；都不空 → 放内容最少的两个。
     */
    private List<Integer> chooseTwo(int slotA, int slotB, int slotFull) {
        List<Integer> all = new ArrayList<>(List.of(slotA, slotB, slotFull));

        int keep = -1;
        for (int slot : all) {
            if (isEmptyBox(slot)) {
                keep = slot;
                break;
            }
        }

        List<Integer> place = new ArrayList<>();
        for (int slot : all) {
            if (slot != keep) place.add(slot);
        }
        if (keep < 0) {
            place.sort(Comparator.comparingInt(this::boxItems));
            place = new ArrayList<>(place.subList(0, 2));
        }
        ChatUtils.debug("挑两个盒子放出去：" + place + "（留在背包：" + keep + "）");
        return place;
    }

    private boolean isEmptyBox(int slot) {
        ItemStack stack = ctx.live(slot);
        return ShulkerUtils.isShulkerBox(stack) && ShulkerUtils.contents(stack).isEmpty();
    }

    private int boxItems(int slot) {
        int total = 0;
        for (ItemStack stack : ShulkerUtils.contents(ctx.live(slot))) total += stack.getCount();
        return total;
    }

    // ------------------------------------------------------------------
    // 放一个盒子
    // ------------------------------------------------------------------

    private void queuePlaceBox(BlockPos spot, int inventorySlot, String what, boolean recordSlot) {
        queue.sync("放" + what + "盒到 " + spot.toShortString(), () -> {
            ItemStack box = ctx.live(inventorySlot);
            if (!ShulkerUtils.isShulkerBox(box)) {
                fail("物品栏第 " + inventorySlot + " 格里不是潜影盒（是 "
                    + SingleStageData.idOf(box) + "）");
                queue.cancel();
                return;
            }
            int hotbar = firstFreeHotbar();
            if (hotbar < 0) {
                fail("快捷栏 6~8 都占着，没法放" + what + "盒");
                queue.cancel();
                return;
            }

            List<ItemStack> contents = SingleStageData.contentsOf(box);
            queue.child("把盒子挪到快捷栏第 " + (hotbar + 1) + " 格",
                () -> new SingleStageInventoryAction(ctx,
                    List.of(SingleStageInventoryAction.move(inventorySlot, hotbar))));
            queue.child("放到 " + spot.toShortString(),
                () -> new SingleStagePlaceAction(ctx, spot, hotbar));
            queue.sync("记缓存", () -> {
                ctx.data.setBox(spot, contents);
                if (recordSlot && !ctx.placedBoxSlots.contains(inventorySlot)) {
                    ctx.placedBoxSlots.add(inventorySlot);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 取圆石 + 放收集盒
    // ------------------------------------------------------------------

    private void queueTakeCobble() {
        List<BlockPos> boxes = new ArrayList<>(List.of(ctx.cobbleSpotA(), ctx.cobbleSpotB()));
        boxes.sort(Comparator.comparingInt(
            (BlockPos box) -> ctx.data.boxCount(box, Items.COBBLESTONE)).reversed());

        BlockPos first = boxes.get(0);
        BlockPos second = boxes.get(1);
        int available = Math.min(PLATFORM_COBBLE, ctx.data.boxCount(first, Items.COBBLESTONE));

        if (available > 0) {
            queue.child("从 " + first.toShortString() + " 取 " + available + " 个圆石",
                () -> new SingleStageBoxGetAction(ctx, first, COBBLE,
                    List.of(SingleStageBoxGetAction.Want.exactly(SingleStageCtx.HOTBAR_FREE_1, available)),
                    "圆石"));
        }

        queue.sync("不够再拿另一个盒子", () -> {
            int need = PLATFORM_COBBLE - ctx.live(SingleStageCtx.HOTBAR_FREE_1).getCount();
            if (need <= 0) return;
            queue.child("从 " + second.toShortString() + " 再取 " + need + " 个圆石",
                () -> new SingleStageBoxGetAction(ctx, second, COBBLE,
                    List.of(SingleStageBoxGetAction.Want.exactly(SingleStageCtx.HOTBAR_FREE_1, need)),
                    "圆石"));
        });
    }

    private static final java.util.function.Predicate<ItemStack> COBBLE =
        stack -> stack.isOf(Items.COBBLESTONE);

    private void queuePlaceCollectBoxes() {
        queue.sync("挑收集盒", () -> {
            ctx.refresh();
            int slot = pickCollectSlot();
            if (slot < 0) {
                fail("背包 13~21 里既没有半满的潜影盒，也没有空的潜影盒");
                queue.cancel();
                return;
            }
            ctx.collectSlot = slot;
            ChatUtils.info("收集盒用物品栏第 " + slot + "、" + (slot + 1) + " 格"
                + "（收集位从 " + slot + " 开始）");
            queuePlaceBox(ctx.collectSpotA(), slot, "收集", false);
            queuePlaceBox(ctx.collectSpotB(), slot + 1, "收集", false);
        });
    }

    /** 从背包 13~21 里挑第一个半满的；没有就挑第一个空的。 */
    private int pickCollectSlot() {
        int empty = -1;
        for (int slot = SingleStageCtx.COLLECT_START; slot <= SingleStageCtx.COLLECT_END; slot++) {
            ItemStack stack = ctx.live(slot);
            if (!ShulkerUtils.isShulkerBox(stack)) continue;
            SingleStageCheck.Fill fill = SingleStageCheck.fillOf(stack);
            if (fill == SingleStageCheck.Fill.HALF) return slot;
            if (fill == SingleStageCheck.Fill.EMPTY && empty < 0) empty = slot;
        }
        return empty;
    }

    private int firstFreeHotbar() {
        for (int i = SingleStageCtx.HOTBAR_FREE_1; i <= SingleStageCtx.HOTBAR_FREE_3; i++) {
            if (ctx.live(i).isEmpty()) return i;
        }
        return -1;
    }
}
