package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * **拿剑把一堆格子挨个右键一遍**（同一 tick 批量发），逼服务端把真实方块状态推回来。
 *
 * <p>为什么有用：客户端放方块是本地预测的，可能「客户端看着放上了、服务端其实没放上」。
 * 右键一个方块之后服务端会把这个位置的**真实**方块状态重新发一遍（原版就有的行为），
 * 于是客户端世界里那些「其实没放上」的格子会立刻变回空气 / 水 —— 之后一查就知道漏了哪些。
 *
 * <p>用剑是为了**什么都不做**：剑对着方块右键没有任何交互（不会开箱子、不会放方块），
 * 只是单纯触发那一次「同步真实状态」。
 *
 * <p>发完之后等 {@value #SETTLE_TICKS} tick 让方块更新包回来再结束。
 */
class SingleStagePokeAction extends SingleStageStep {

    /** 一个 tick 最多右键几个。 */
    private static final int MAX_PER_TICK = 128;

    /** 发完之后等几 tick（让服务端的方块更新包回来）。 */
    private static final int SETTLE_TICKS = 5;

    private final List<BlockPos> targets;
    private int index;
    private int waitTicks;
    private boolean hotbarHeld;

    SingleStagePokeAction(SingleStageCtx ctx, List<BlockPos> targets) {
        super(ctx);
        this.targets = new ArrayList<>(targets);
    }

    @Override
    protected String phase() {
        return "右键核对方块（" + targets.size() + " 个）";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            fail("玩家或世界不存在");
            return;
        }
        // 拿剑（右键不会产生任何副作用）
        hotbarHeld = HotbarManager.get().request(Owner.MINE, SingleStageCtx.HOTBAR_SWORD);
        if (!hotbarHeld) {
            // 剑切不过去就不右键了：手上要是粘液块，右键会真的去放方块
            finish();
        }
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (index < targets.size()) {
            int budget = MAX_PER_TICK;
            while (index < targets.size() && budget-- > 0) {
                poke(mc, player, targets.get(index++));
            }
            waitTicks = 0;
            return;
        }

        if (++waitTicks < SETTLE_TICKS) return;
        finish();
    }

    @Override
    protected void onEnd() {
        if (hotbarHeld) {
            HotbarManager.get().release(Owner.MINE);
            hotbarHeld = false;
        }
        RotateManager.get().release(Owner.MINE);
    }

    /** 右键一次某一格（点它自己的上面）。 */
    private static void poke(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        Vec3d hitPos = Vec3d.ofCenter(pos).add(0.0, 0.5, 0.0);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, pos, false);
        RotateManager.get().rotateTo(Owner.MINE, hitPos);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
    }
}
