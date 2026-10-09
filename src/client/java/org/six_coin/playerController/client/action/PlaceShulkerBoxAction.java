package org.six_coin.playerController.client.action;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

/**
 * 把**快捷栏指定格**里的潜影盒放到某个潜影盒摆放处（放成方块）。
 *
 * <p>摆放处本身必须是空气、下面那格不是空气（见 {@code StationPrecheck}），
 * 所以这里的做法是对着**下面那格**的上面右键：潜影盒就会被放到摆放处那一格。
 *
 * <p>放之前会把手上那一格切过去（{@code selectedSlot}），放完检查摆放处是不是真的变成潜影盒了。
 */
public class PlaceShulkerBoxAction extends Action {

    /** 右键之后等方块同步过来最久。 */
    private static final int MAX_WAIT_TICKS = 60;

    private final BlockPos spot;
    private final int hotbarIndex;

    private int waitTicks;
    private boolean placed;

    public PlaceShulkerBoxAction(BlockPos spot, int hotbarIndex) {
        this.spot = spot.toImmutable();
        this.hotbarIndex = hotbarIndex;
    }

    /** 摆放处真的变成潜影盒了没。 */
    public boolean placed() {
        return placed;
    }

    @Override
    public String name() {
        return "把快捷栏第 " + (hotbarIndex + 1) + " 格的潜影盒放到 " + spot.toShortString();
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

        BlockState state = mc.world.getBlockState(spot);
        if (!state.isAir()) {
            fail("摆放处 " + spot.toShortString() + " 已经有方块了（"
                + Registries.BLOCK.getId(state.getBlock()) + "）");
            return;
        }
        if (mc.world.getBlockState(spot.down()).isAir()) {
            fail("摆放处 " + spot.toShortString() + " 下面那格是空气，没地方放潜影盒");
            return;
        }

        ItemStack held = player.getInventory().getStack(hotbarIndex);
        if (!ShulkerUtils.isShulkerBox(held)) {
            fail("快捷栏第 " + (hotbarIndex + 1) + " 格里不是潜影盒（是 "
                + describe(held) + "）");
            return;
        }

        if (!PlayerUtils.isWithinReach(spot.down())) {
            fail("摆放处 " + spot.toShortString() + " 超出触及范围（距离 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(spot.down()))
                + "，触及范围 " + String.format("%.2f", PlayerUtils.reach()) + "）");
            return;
        }

        player.getInventory().setSelectedSlot(hotbarIndex);
        PlayerUtils.lookAt(spot.down());
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                player.getYaw(), player.getPitch(), player.isOnGround(), player.horizontalCollision));
        }

        Vec3d hitPos = Vec3d.ofCenter(spot).add(0.0, -0.5, 0.0);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, spot.down(), false);

        ChatUtils.debug("放置潜影盒：手上 %s（快捷栏第 %d 格），对着 %s 的上面右键",
            describe(held), hotbarIndex + 1, spot.down().toShortString());
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockState state = mc.world.getBlockState(spot);
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            placed = true;
            ChatUtils.debug("潜影盒已经放到 " + spot.toShortString() + " 了（"
                + Registries.BLOCK.getId(state.getBlock()) + "）");
            finish();
            return;
        }

        if (++waitTicks > MAX_WAIT_TICKS) {
            fail("放了潜影盒但 " + spot.toShortString() + " 那里还是 "
                + Registries.BLOCK.getId(state.getBlock()) + "（没放上去？）");
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }
}
