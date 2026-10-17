package org.six_coin.playerController.client.stock;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 用快捷栏指定格里的工具，把某个方块挖掉。
 *
 * <p>需求里点名：**不要模拟**（不要去假装按键），直接发包。这里走的是原版客户端那套挖掘流程 ——
 * {@code attackBlock}（发 START_DESTROY_BLOCK）→ 每 tick {@code updateBlockBreakingProgress}
 * （累进度，够了客户端发 STOP_DESTROY_BLOCK 并把方块设成空气）。
 *
 * <p>挖之前会通过 {@link HotbarManager} 把工具那一格切过去、通过 {@link RotateManager}
 * 让服务端以为我们看着这个方块（客户端视角不动）。
 */
public class MineBlockAction extends Action {

    /** 挖一个方块最多等这么多 tick（潜影盒用钻石镐大概 8 tick）。 */
    private static final int MAX_MINE_TICKS = 200;

    private final BlockPos pos;
    private final int hotbarIndex;

    private Direction side = Direction.UP;
    private int mineTicks;

    public MineBlockAction(BlockPos pos, int hotbarIndex) {
        this.pos = pos.toImmutable();
        this.hotbarIndex = hotbarIndex;
    }

    @Override
    public String name() {
        return "挖掉 " + pos.toShortString() + "（用快捷栏第 " + (hotbarIndex + 1) + " 格）";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (mc.interactionManager == null) {
            fail("拿不到 interactionManager");
            return;
        }

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) {
            ChatUtils.debug(pos.toShortString() + " 本来就是空气，不用挖");
            finish();
            return;
        }
        if (!PlayerUtils.isWithinReach(pos)) {
            fail("方块 " + pos.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(pos))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }

        if (!HotbarManager.get().request(Owner.STOCK, hotbarIndex)) {
            fail("快捷栏第 " + (hotbarIndex + 1) + " 格现在不让切过去（别的功能在用）");
            return;
        }

        ItemStack tool = player.getInventory().getStack(hotbarIndex);
        side = PlayerUtils.facingSide(pos);
        RotateManager.get().rotateTo(Owner.STOCK, pos);

        ChatUtils.debug("开始挖 %s（%s，面 %s，手上 %s）",
            pos.toShortString(), Registries.BLOCK.getId(state.getBlock()), side.asString(), describe(tool));
        if (!mc.interactionManager.attackBlock(pos, side)) {
            fail("挖不动 " + pos.toShortString() + "（够不着？）");
        }
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) {
            ChatUtils.debug(pos.toShortString() + " 已经挖掉了（用了 " + mineTicks + " tick）");
            finish();
            return;
        }

        if (++mineTicks > MAX_MINE_TICKS) {
            fail("挖 " + pos.toShortString() + " 超时（" + mineTicks + " tick，还是 "
                + Registries.BLOCK.getId(state.getBlock()) + "）");
            return;
        }

        mc.interactionManager.updateBlockBreakingProgress(pos, side);
    }

    @Override
    protected void onEnd() {
        HotbarManager.get().release(Owner.STOCK);
        RotateManager.get().release(Owner.STOCK);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null || mc.world == null) return;
        if (!mc.world.getBlockState(pos).isAir()) {
            mc.interactionManager.cancelBlockBreaking();
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空手";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
