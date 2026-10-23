package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * **一口气**把一堆含水方块打掉：全部立刻发包（START → 挥手 → STOP），一个都不等。
 *
 * <p>为什么要这样：含水方块挨个挖、每挖一个都等它真的变成空气 / 水，太慢了 ——
 * 而且挖水里的海草 / 海带之后那一格是水，本来就不该等。所以改成
 * 「先把这一批全发出去 → 等几 tick 让服务端处理 → 按世界里的方块统一核对一遍」。
 *
 * <p>核对之后还有没打掉的，就再发一轮（最多 {@value #MAX_ROUNDS} 轮），还不行就报一句结束
 * （**不算失败**，交给后面放粘液块那一步兜着）。
 */
class SingleStageMineBatchAction extends SingleStageStep {

    /** 一个 tick 最多发几个方块的包。 */
    private static final int MAX_PER_TICK = 64;

    /** 全部发完之后等几 tick 再核对（服务端要时间处理这些破坏包）。 */
    private static final int VERIFY_TICKS = 5;

    /** 最多补发几轮。 */
    private static final int MAX_ROUNDS = 3;

    private final List<BlockPos> targets;
    private int index;
    private int waitTicks;
    private int rounds;

    SingleStageMineBatchAction(SingleStageCtx ctx, List<BlockPos> targets) {
        super(ctx);
        this.targets = new ArrayList<>(targets);
    }

    @Override
    protected String phase() {
        return "打掉含水方块（" + targets.size() + " 个）";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            fail("玩家或世界不存在");
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

        if (index < targets.size()) {
            sendBatch(mc, player);
            return;
        }

        // 全发完了：等几 tick 让服务端处理，再按世界里的方块核对
        if (++waitTicks < VERIFY_TICKS) return;
        waitTicks = 0;

        List<BlockPos> left = new ArrayList<>();
        for (BlockPos pos : targets) {
            if (SingleStageStage1.isWaterlogged(mc.world.getBlockState(pos))) left.add(pos);
        }
        if (left.isEmpty()) {
            ChatUtils.debug(phase() + "：核对通过（都打掉了）");
            finish();
            return;
        }

        if (++rounds > MAX_ROUNDS) {
            ChatUtils.error("有 " + left.size() + " 个含水方块打不掉（试了 " + rounds + " 轮），先跳过");
            finish();
            return;
        }

        ChatUtils.debug("还有 " + left.size() + " 个含水方块没打掉，再发一轮");
        targets.clear();
        targets.addAll(left);
        index = 0;
    }

    /** 把这一 tick 额度内的目标全部发包（该换工具就换）。 */
    private void sendBatch(MinecraftClient mc, ClientPlayerEntity player) {
        int budget = MAX_PER_TICK;
        while (index < targets.size() && budget-- > 0) {
            BlockPos pos = targets.get(index++);

            BlockState state = mc.world.getBlockState(pos);
            if (!SingleStageStage1.isWaterlogged(state)) continue;      // 已经没了

            int slot = SingleStageStage1.toolSlotFor(state);
            HotbarManager.get().request(Owner.MINE, slot);
            RotateManager.get().rotateTo(Owner.MINE, pos);
            MineBlockAction.breakNow(mc, player, pos);
        }
    }

    @Override
    protected void onEnd() {
        HotbarManager.get().release(Owner.MINE);
        RotateManager.get().release(Owner.MINE);
    }
}
