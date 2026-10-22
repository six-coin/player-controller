package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.stock.MineBlockAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 需求 (6)：拆掉临时工作站。
 *
 * <ol>
 *   <li>六个盒子逐个「挖掉 → snap 到它的位置 → <b>等物品栏把它吸进来</b> → snap 回临时站立点 → 装回物品栏」。
 *       装回去的顺序：圆石盒和黏液块盒都按内容**从少到多**装进对应那两个格子（背包 6/7、9/10），
 *       收集盒同样按内容从少到多装进当前的两个收集位；</li>
 *   <li>最后把 4×5 的圆石平台拆掉（悬停在平台正上方拆，20 格都在触及范围内）。
 *       <b>拆完不捡掉落物</b> —— 所以拆的时候人站在平台上方两格，圆石就留在地上。</li>
 * </ol>
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

        // 平台：拆掉，但不捡圆石（站上面两格，掉落物吸不到）
        queue.sync("拆平台", () -> {
            queue.child("挪到平台上方",
                () -> new SingleStageSnapAction(ctx, ctx.platformHover()));
            for (BlockPos cell : ctx.platform()) {
                queue.child("挖掉平台 " + cell.toShortString(),
                    () -> new MineBlockAction(cell, SingleStageCtx.HOTBAR_PICKAXE));
            }
            queue.child("回临时站立点",
                () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
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
            Map<Integer, String> before = ctx.boxSignatures();

            queue.child("挖掉 " + spot.toShortString(),
                () -> new MineBlockAction(spot, SingleStageCtx.HOTBAR_PICKAXE));
            queue.child("snap 到 " + spot.toShortString(),
                () -> new SingleStageSnapAction(ctx, spot));
            queue.child("等物品栏吸到盒子",
                () -> new SingleStageWaitItemAction(ctx, contents, before));
            queue.child("snap 回临时站立点",
                () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

            queue.sync("把盒子装回物品栏", () -> {
                int found = ctx.findPickedBox(before, contents);
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
}
