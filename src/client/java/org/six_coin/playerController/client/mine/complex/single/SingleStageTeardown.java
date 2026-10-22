package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 需求 (6)：拆掉临时工作站 —— 六个盒子逐个「挖掉 → snap 过去捡起来 → snap 回来 → 装回物品栏」。
 *
 * <p>装回去的顺序：圆石盒和黏液块盒都按内容**从少到多**装进对应那两个格子
 * （背包 6/7、9/10），收集盒同样按内容从少到多装进当前的两个收集位。
 *
 * <p>如果目标格子里本来就有东西（比如「留一个空盒在背包」那个），就找个空格放，不会硬塞。
 */
class SingleStageTeardown extends SingleStageStep {

    private final SingleStageQueue queue = new SingleStageQueue();

    SingleStageTeardown(SingleStageCtx ctx) {
        super(ctx);
    }

    @Override
    protected String phase() {
        return "拆临时工作站";
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
        queue.child("snap 到临时站立点",
            () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

        // 圆石盒 → 背包 6、7
        queue.sync("拆圆石盒", () -> {
            List<BlockPos> boxes = byContent(ctx.cobbleSpotA(), ctx.cobbleSpotB());
            queueBreak(boxes.get(0), SingleStageCtx.COBBLE_BOX_A);
            queueBreak(boxes.get(1), SingleStageCtx.COBBLE_BOX_B);
        });

        // 黏液块盒 → 背包 9、10
        queue.sync("拆粘液块盒", () -> {
            List<BlockPos> boxes = byContent(ctx.slimeSpotA(), ctx.slimeSpotB());
            queueBreak(boxes.get(0), SingleStageCtx.SLIME_BOX_A);
            queueBreak(boxes.get(1), SingleStageCtx.SLIME_BOX_B);
        });

        // 收集盒 → 当前的两个收集位
        queue.sync("拆收集盒", () -> {
            List<BlockPos> boxes = byContent(ctx.collectSpotA(), ctx.collectSpotB());
            queueBreak(boxes.get(0), ctx.collectSlot);
            queueBreak(boxes.get(1), ctx.collectSlot + 1);
        });
    }

    /** 内容少的排前面。 */
    private List<BlockPos> byContent(BlockPos a, BlockPos b) {
        List<BlockPos> boxes = new ArrayList<>(List.of(a, b));
        boxes.sort(Comparator.comparingInt(ctx.data::boxItems));
        return boxes;
    }

    private void queueBreak(BlockPos spot, int targetSlot) {
        queue.sync("拆 " + spot.toShortString(), () -> {
            List<ItemStack> contents = ctx.data.box(spot);

            queue.child("挖掉 " + spot.toShortString(),
                () -> new MineBlockAction(spot, SingleStageCtx.HOTBAR_PICKAXE));
            queue.child("snap 到 " + spot.toShortString(),
                () -> new SingleStageSnapAction(ctx, spot));
            queue.child("snap 回临时站立点",
                () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

            queue.sync("把盒子装回物品栏", () -> {
                int found = findBoxSlot(contents);
                if (found < 0) {
                    fail("找不到刚挖下来的盒子（" + spot.toShortString() + "）");
                    queue.cancel();
                    return;
                }
                int dest = destination(targetSlot);
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

    /** 目标格空着就用它；否则找背包 22~27 里的空格；都没有返回 -1。 */
    private int destination(int targetSlot) {
        if (ctx.live(targetSlot).isEmpty()) return targetSlot;
        for (int slot = SingleStageCtx.FILLER_START; slot <= SingleStageCtx.FILLER_END; slot++) {
            if (ctx.live(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** 物品栏里哪个格子的潜影盒内容和 {@code contents} 一样。 */
    private int findBoxSlot(List<ItemStack> contents) {
        for (int slot = 0; slot <= SingleStageCtx.FILLER_END; slot++) {
            ItemStack stack = ctx.live(slot);
            if (!ShulkerUtils.isShulkerBox(stack)) continue;
            if (sameContents(ShulkerUtils.contents(stack), contents)) return slot;
        }
        return -1;
    }

    private static boolean sameContents(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ItemStack left = a.get(i);
            ItemStack right = b.get(i);
            if (!ItemStack.areItemsAndComponentsEqual(left, right)) return false;
            if (left.getCount() != right.getCount()) return false;
        }
        return true;
    }
}
