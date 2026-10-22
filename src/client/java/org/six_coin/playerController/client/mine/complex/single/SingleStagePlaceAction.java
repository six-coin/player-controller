package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 把快捷栏某一格里的方块放到某个位置（潜影盒、圆石、黏液块都用它）。
 *
 * <p><b>不查触及范围</b>：需求里明确说了「硬放就行」，这些位置都是按设计算好的，
 * 服务端那边的距离检查比 {@code actions.reach} 松（多给 1 格）。
 *
 * <p>放的时候是「对着相邻方块的那一面右键」：优先点下面那一格（最稳），
 * 放不上去就换别的面再试（平台是浮空的，六个面都可能是空气）。
 */
class SingleStagePlaceAction extends SingleStageStep {

    /** 一个面最多等这么多 tick 看它有没有真的放上去。 */
    private static final int MAX_WAIT_TICKS = 20;

    /** 试哪些面（按这个顺序）。{@code UP} = 点下面那一格的上面。 */
    private static final Direction[] FACES = {Direction.UP, Direction.NORTH, Direction.SOUTH,
        Direction.WEST, Direction.EAST, Direction.DOWN};

    private final BlockPos spot;
    private final int hotbarIndex;

    private int faceIndex;
    private int waitTicks;

    SingleStagePlaceAction(SingleStageCtx ctx, BlockPos spot, int hotbarIndex) {
        super(ctx);
        this.spot = spot.toImmutable();
        this.hotbarIndex = hotbarIndex;
    }

    @Override
    protected String phase() {
        return "在 " + spot.toShortString() + " 放方块";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockState state = mc.world.getBlockState(spot);
        if (!replaceable(state)) {
            fail("位置 " + spot.toShortString() + " 已经有 " + Registries.BLOCK.getId(state.getBlock())
                + " 了");
            return;
        }

        ItemStack held = player.getInventory().getStack(hotbarIndex);
        if (!(held.getItem() instanceof BlockItem)) {
            fail("快捷栏第 " + (hotbarIndex + 1) + " 格里不是方块（是 " + SingleStageData.idOf(held) + "）");
            return;
        }

        if (!HotbarManager.get().request(Owner.MINE, hotbarIndex)) {
            fail("快捷栏第 " + (hotbarIndex + 1) + " 格现在不让切过去（别的功能在用）");
            return;
        }
        tryPlace(mc, player);
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!replaceable(mc.world.getBlockState(spot))) {
            ChatUtils.debug("放好了：" + spot.toShortString());
            finish();
            return;
        }

        if (++waitTicks <= MAX_WAIT_TICKS) return;

        // 这一面没放上去：换一个面再试
        waitTicks = 0;
        faceIndex++;
        if (faceIndex >= FACES.length) {
            fail("在 " + spot.toShortString() + " 放不上去（六个面都试过了）");
            return;
        }
        tryPlace(mc, player);
    }

    @Override
    protected void onEnd() {
        HotbarManager.get().release(Owner.MINE);
        RotateManager.get().release(Owner.MINE);
    }

    // ------------------------------------------------------------------

    /** 这一格现在能不能放方块（空气 / 水 / 熔岩都算能放）。 */
    static boolean replaceable(BlockState state) {
        return state.isAir() || state.getBlock() instanceof FluidBlock;
    }

    private void tryPlace(MinecraftClient mc, ClientPlayerEntity player) {
        Direction side = FACES[faceIndex];
        BlockPos hitBlock = spot.offset(side.getOpposite());

        Vec3d hitPos = Vec3d.ofCenter(hitBlock).add(
            side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, side, hitBlock, false);

        RotateManager.get().rotateTo(Owner.MINE, hitPos);

        ChatUtils.debug("放方块：%s（对着 %s 的 %s 面，手上 %s）", spot.toShortString(),
            hitBlock.toShortString(), side.asString(),
            SingleStageData.idOf(player.getInventory().getStack(hotbarIndex)));
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
    }
}
