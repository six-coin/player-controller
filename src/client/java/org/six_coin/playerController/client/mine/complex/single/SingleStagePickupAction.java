package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 捡一个**指定**的掉落物：snap 到它所在的那一格就结束。
 *
 * <p><b>到了之后不等</b>：掉落物是瞬发的（人一到就被吸走），在这儿干等纯属浪费时间，
 * 直接回去继续 snap 下一个就行 —— 下一轮 {@code collectStep} 会重新找最近的那个。
 *
 * <p>走不到**不算失败**：打个 debug 就结束，交给调用方继续。
 */
class SingleStagePickupAction extends SingleStageStep {

    private final int entityId;

    @Nullable
    private Action move;

    SingleStagePickupAction(SingleStageCtx ctx, int entityId) {
        super(ctx);
        this.entityId = entityId;
    }

    @Override
    protected String phase() {
        return "去捡掉落物 #" + entityId;
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        ItemEntity item = find(mc);
        if (item == null) {
            finish();          // 已经不在了（被吸走 / 被别人捡了）
            return;
        }
        move = new SingleStageSnapAction(ctx, item.blockPosition());
        move.start();
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (move == null) {
            finish();
            return;
        }

        move.update();
        if (!move.isFinished()) return;

        Action done = move;
        move = null;
        done.cleanup();
        if (done.failureReason() != null) {
            ChatUtils.debug("走不到掉落物：%s", done.failureReason());
        }
        finish();
    }

    @Override
    protected void onEnd() {
        if (move != null) {
            move.cleanup();
            move = null;
        }
    }

    // ------------------------------------------------------------------

    @Nullable
    private ItemEntity find(Minecraft mc) {
        if (mc.level == null) return null;
        Entity entity = mc.level.getEntity(entityId);
        if (!(entity instanceof ItemEntity item)) return null;
        return item.isRemoved() ? null : item;
    }
}
