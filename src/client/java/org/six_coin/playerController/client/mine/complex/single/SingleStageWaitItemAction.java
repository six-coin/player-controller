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
 * <p>认「哪一个盒子」的办法见 {@link SingleStageCtx#findPickedBox}：内容对得上、而且
 * 挖之前那一格不是这个内容。
 */
class SingleStageWaitItemAction extends SingleStageStep {

    /** 最多等 5 秒。 */
    private static final int TIMEOUT_TICKS = 100;

    private final List<ItemStack> contents;
    private final Map<Integer, String> before;
    /** true = 不接受「挖之前就是同样内容」的格子（挖空盒时用，见 {@link SingleStageCtx#findPickedBoxStrict}）。 */
    private final boolean strict;

    private int ticks;
    private int slot = -1;

    SingleStageWaitItemAction(SingleStageCtx ctx, List<ItemStack> contents,
                              Map<Integer, String> before) {
        this(ctx, contents, before, false);
    }

    SingleStageWaitItemAction(SingleStageCtx ctx, List<ItemStack> contents,
                              Map<Integer, String> before, boolean strict) {
        super(ctx);
        this.contents = List.copyOf(contents);
        this.before = Map.copyOf(before);
        this.strict = strict;
    }

    private int find() {
        return strict ? ctx.findPickedBoxStrict(before, contents) : ctx.findPickedBox(before, contents);
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
        slot = find();
        if (slot >= 0) {
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

        slot = find();
        if (slot >= 0) {
            ChatUtils.debug("吸到盒子了（物品栏第 " + slot + " 格，" + (ticks + 1) + " tick）");
            finish();
            return;
        }

        if (++ticks > TIMEOUT_TICKS) {
            fail("等了 " + (TIMEOUT_TICKS / 20) + " 秒还没吸到盒子（盒子里是 "
                + SingleStageCtx.signature(contents) + "）");
        }
    }
}
