package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.List;
import java.util.Map;

/**
 * 挖掉一个潜影盒之后，**等物品栏把它吸进来**。
 *
 * <p>为什么要等：挖掉的方块变成掉落物之后，是服务端下一 tick 才把它挪进物品栏的，
 * 我们这边 snap 过去的那一瞬间经常还什么都没吸到 —— 直接去「找盒子」就会报
 * 「找不到刚挖下来的盒子」。所以这里盯着物品栏，吸到了就走，{@value #TIMEOUT_TICKS}
 * tick（5 秒）还没吸到才算失败。
 *
 * <p><b>怎么算「吸进来了」</b>：两个条件都满足才算 ——
 * <ol>
 *   <li>物品栏里潜影盒的**个数比挖之前多了一个**（这一条最硬：光看内容认不出
 *       「本来就有一个一样的空盒」这种情况，会误以为已经吸到、提前飞回站立点）；</li>
 *   <li>用 {@link SingleStageCtx#findPickedBox} 找得到是**哪一格**（内容对得上、
 *       而且挖之前那一格不是这个内容）。</li>
 * </ol>
 */
class SingleStageWaitItemAction extends SingleStageStep {

    /** 最多等 5 秒。 */
    private static final int TIMEOUT_TICKS = 100;

    private final List<ItemStack> contents;
    private final Map<Integer, String> before;
    /** 挖之前物品栏里有几个潜影盒（挖完必须比这个多一个）。 */
    private final int boxesBefore;
    /** true = 不接受「挖之前就是同样内容」的格子（挖空盒时用，见 {@link SingleStageCtx#findPickedBoxStrict}）。 */
    private final boolean strict;

    private int ticks;
    private int slot = -1;

    /**
     * @param boxesBefore 挖之前物品栏里有几个潜影盒（在**挖之前**拍，别在构造的时候现数）
     * @param strict      true = 不接受「挖之前就是同样内容」的格子（挖空盒时用）
     */
    SingleStageWaitItemAction(SingleStageCtx ctx, List<ItemStack> contents,
                              Map<Integer, String> before, int boxesBefore, boolean strict) {
        super(ctx);
        this.contents = List.copyOf(contents);
        this.before = Map.copyOf(before);
        this.boxesBefore = boxesBefore;
        this.strict = strict;
    }

    private int find() {
        return strict ? ctx.findPickedBoxStrict(before, contents) : ctx.findPickedBox(before, contents);
    }

    /** 吸到了没有：个数多了一个，而且找得到是哪一格。 */
    private boolean picked() {
        if (ctx.shulkerBoxCount() <= boxesBefore) return false;
        slot = find();
        return slot >= 0;
    }

    /** 吸到的那一格；没吸到是 -1。 */
    int slot() {
        return slot;
    }

    @Override
    protected String phase() {
        return "等物品栏吸到盒子";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            fail("玩家不存在");
            return;
        }
        if (picked()) {
            ChatUtils.debug("盒子已经在物品栏第 " + slot + " 格了");
            finish();
        }
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            fail("玩家不存在");
            return;
        }

        if (picked()) {
            ChatUtils.debug("吸到盒子了（物品栏第 " + slot + " 格，" + (ticks + 1) + " tick）");
            finish();
            return;
        }

        if (++ticks > TIMEOUT_TICKS) {
            fail("等了 " + (TIMEOUT_TICKS / 20) + " 秒还没吸到盒子（盒子里是 "
                + SingleStageCtx.signature(contents) + "，物品栏里的潜影盒 "
                + boxesBefore + " → " + ctx.shulkerBoxCount() + " 个）");
        }
    }
}
