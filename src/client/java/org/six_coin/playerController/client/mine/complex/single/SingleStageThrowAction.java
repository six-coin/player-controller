package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 低头（pitch 90°）把指定几格里的东西整叠扔出去。
 *
 * <p>用在拆完平台之后：那时候快捷栏第 6 格基本一定吸上来了几块圆石，扔掉再去做补货检查
 * （不然「快捷栏 6 必须是空的」这一条永远过不了）。
 *
 * <p>「低头」只是给服务端发一个转头包（见 {@link RotateManager}），客户端视角不动；
 * 服务端按它看到的 pitch 决定往哪儿丢，所以低头丢就是往脚下丢。
 *
 * <p><b>低头之后要等 {@value #WAIT_TICKS} tick 再扔</b>：转头包和服务端处理之间有延迟，
 * 同一 tick 就扔的话服务端可能还在用旧 pitch，东西会斜着飞出去（扔到矿洞里被重新捡回来）。
 */
class SingleStageThrowAction extends SingleStageStep {

    /** 低头之后等几个 tick 再扔（写死）。 */
    private static final int WAIT_TICKS = 5;

    private final List<Integer> slots;
    private final String what;

    @Nullable
    private SingleStageInventoryAction child;
    private boolean rotated;
    private int waitTicks;
    private boolean throwing;

    SingleStageThrowAction(SingleStageCtx ctx, List<Integer> slots, String what) {
        super(ctx);
        this.slots = List.copyOf(slots);
        this.what = what;
    }

    @Override
    protected String phase() {
        return "低头扔掉" + what;
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        List<Integer> nonEmpty = new ArrayList<>();
        for (int slot : slots) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty()) nonEmpty.add(slot);
        }
        if (nonEmpty.isEmpty()) {
            ChatUtils.debug(what + " 是空的，不用扔");
            finish();
            return;
        }

        if (!RotateManager.get().rotateTo(Owner.MINE, player.getYaw(), 90.0f)) {
            fail("现在不让转头（别的功能在用），没法低头扔东西");
            return;
        }
        rotated = true;
        ChatUtils.debug("低头扔" + what + "：" + nonEmpty.size() + " 叠，等 " + WAIT_TICKS + " tick");
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            fail("玩家不存在");
            return;
        }

        if (!throwing) {
            if (++waitTicks < WAIT_TICKS) return;
            throwing = true;

            List<SingleStageInventoryAction.Op> ops = new ArrayList<>();
            for (int slot : slots) {
                if (!player.getInventory().getStack(slot).isEmpty()) {
                    ops.add(SingleStageInventoryAction.throwStack(slot));
                }
            }
            if (ops.isEmpty()) {
                finish();
                return;
            }
            ChatUtils.info("低头扔掉" + what + "里的 " + ops.size() + " 叠东西");

            child = new SingleStageInventoryAction(ctx, ops);
            child.start();
            return;
        }

        if (child == null) {
            finish();
            return;
        }
        child.update();
        if (!child.isFinished()) return;

        SingleStageInventoryAction done = child;
        child = null;
        done.cleanup();
        if (done.failureReason() != null) {
            fail(done.failureReason());
            return;
        }
        finish();
    }

    @Override
    protected void onEnd() {
        if (child != null) {
            child.cleanup();
            child = null;
        }
        if (rotated) {
            RotateManager.get().release(Owner.MINE);
            rotated = false;
        }
    }
}
