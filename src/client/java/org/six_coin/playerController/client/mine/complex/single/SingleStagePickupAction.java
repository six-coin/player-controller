package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 捡一个**指定**的掉落物：snap 到它所在的方块，然后贴到它身上等它被吸走。
 *
 * <p>为什么不能只 snap 到方块中心：掉落物可能停在方块边角，玩家站在方块中心时
 * 两个碰撞箱差一点点碰不到，就永远捡不起来（以前会在这里反复重试最后放弃，看起来就是
 * 「还有掉落物却不捡了」）。所以到了同一格之后再把人往物品的 x/z 上贴一下（同一格内的小位移，
 * 发个位置包就行）。
 *
 * <p>捡不到**不算失败**：{@link #picked()} 是 false，由调用方决定换下一个还是先记着。
 */
class SingleStagePickupAction extends SingleStageStep {

    /** 最多追这么久（3 秒）。 */
    private static final int MAX_TICKS = 60;

    /** 贴一次之后等几个 tick 看吸不吸走。 */
    private static final int NUDGE_INTERVAL = 5;

    private final int entityId;

    @Nullable
    private Action move;
    private int ticks;
    private int nudgeCooldown;
    private boolean picked;

    SingleStagePickupAction(SingleStageCtx ctx, int entityId) {
        super(ctx);
        this.entityId = entityId;
    }

    /** 捡到了没有。 */
    boolean picked() {
        return picked;
    }

    @Override
    protected String phase() {
        return "捡掉落物 #" + entityId;
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (find(mc) == null) {
            picked = true;          // 已经不在了（被吸走 / 被别人捡了）
            finish();
        }
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        ItemEntity item = find(mc);
        if (item == null) {
            picked = true;
            finish();
            return;
        }
        if (++ticks > MAX_TICKS) {
            ChatUtils.debug("追了 " + ticks + " tick 还没吸到 " + item.getBlockPos().toShortString()
                + "，先放着");
            finish();
            return;
        }

        if (move != null) {
            move.update();
            if (!move.isFinished()) return;

            Action done = move;
            move = null;
            done.cleanup();
            if (done.failureReason() != null) {
                ChatUtils.debug("走不到 " + item.getBlockPos().toShortString() + "：" + done.failureReason());
                finish();
                return;
            }
        }

        BlockPos here = PlayerUtils.currentBlockPos();
        BlockPos target = item.getBlockPos();
        if (here == null || !here.equals(target)) {
            move = new SingleStageSnapAction(ctx, target);
            move.start();
            return;
        }

        // 已经在同一格了：贴到物品的 x/z 上（同一格内的小位移），等它被吸走
        if (nudgeCooldown-- <= 0) {
            nudge(player, item);
            nudgeCooldown = NUDGE_INTERVAL;
        }
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
    private ItemEntity find(MinecraftClient mc) {
        if (mc.world == null) return null;
        Entity entity = mc.world.getEntityById(entityId);
        if (!(entity instanceof ItemEntity item)) return null;
        return item.isRemoved() ? null : item;
    }

    /** 把玩家贴到物品的 x/z 上（y 不动），并把新位置发给服务端。 */
    private static void nudge(ClientPlayerEntity player, ItemEntity item) {
        double x = item.getX();
        double z = item.getZ();
        double y = player.getY();

        player.setPosition(x, y, z);
        player.setVelocity(Vec3d.ZERO);
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                x, y, z, player.isOnGround(), player.horizontalCollision));
        }
    }
}
