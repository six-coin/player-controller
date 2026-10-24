package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

/**
 * 把快捷栏某一格里的方块放到某个位置（潜影盒、圆石、黏液块都用它）—— <b>凭空放置</b>。
 *
 * <p><b>做法：直接点目标格自己</b>，面随便挑一个。原因是 {@code ItemPlacementContext}：
 * <pre>
 * canReplaceExisting = level.getBlockState(点的那一格).canReplace(...)
 * getBlockPos()      = canReplaceExisting ? 点的那一格 : 点的那一格.offset(面)
 * </pre>
 * 目标格是空气（或水 / 熔岩 / 草这类可替换的），{@code canReplaceExisting} 就是 {@code true}，
 * 方块正好落在目标格上，不需要旁边有方块垫着。
 *
 * <p>这也是 litematica-printer 的 {@code PLACE_IN_AIR} 做法（{@code Action#queueAction} 里
 * 「凭空放置」分支点的是 {@code blockPos} 自己，而不是 {@code blockPos.relative(side)}）。
 *
 * <p>点错了（点成相邻格）的后果很隐蔽：手上东西会被消耗、方块落到旁边那一格、目标格还是空气，
 * 然后一直空手重试 —— 所以这里只点目标格。
 *
 * <p><b>不查触及范围</b>：需求里说「硬放就行」，服务端那边的距离检查比 {@code actions.reach} 松 1 格。
 * 真放不上去（超出服务端允许的距离 / 丢包）会重试几次，然后报错并带上距离。
 */
class SingleStagePlaceAction extends SingleStageStep {

    /** 一次点击最多等这么多 tick 看它有没有真的放上去。 */
    private static final int MAX_WAIT_TICKS = 10;

    /** 最多点几次（换不同的面，纯属换个命中点，面本身不影响落点）。 */
    private static final int MAX_ATTEMPTS = 6;

    private static final Direction[] FACES = {Direction.UP, Direction.NORTH, Direction.SOUTH,
        Direction.WEST, Direction.EAST, Direction.DOWN};

    private final BlockPos spot;
    private final int hotbarIndex;

    private int attempt;
    private int waitTicks;

    SingleStagePlaceAction(SingleStageCtx ctx, BlockPos spot, int hotbarIndex) {
        super(ctx);
        this.spot = spot.immutable();
        this.hotbarIndex = hotbarIndex;
    }

    @Override
    protected String phase() {
        return "在 " + spot.toShortString() + " 放方块";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockState state = mc.level.getBlockState(spot);
        if (!replaceable(state)) {
            fail("位置 " + spot.toShortString() + " 已经有 " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " 了");
            return;
        }

        ItemStack held = player.getInventory().getItem(hotbarIndex);
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
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (!replaceable(mc.level.getBlockState(spot))) {
            ChatUtils.debug("放好了：" + spot.toShortString());
            finish();
            return;
        }

        if (++waitTicks <= MAX_WAIT_TICKS) return;

        waitTicks = 0;
        attempt++;
        if (attempt >= MAX_ATTEMPTS) {
            fail("在 " + spot.toShortString() + " 放不上去（点了 " + attempt + " 次；离眼睛 "
                + String.format("%.2f", PlayerUtils.eyeDistanceTo(spot)) + " 格，手上是 "
                + SingleStageData.idOf(player.getInventory().getItem(hotbarIndex)) + "）");
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

    /** 这一格现在能不能放方块（空气 / 水 / 熔岩 / 草这类可替换的方块都算）。 */
    static boolean replaceable(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    private void tryPlace(Minecraft mc, LocalPlayer player) {
        Direction side = FACES[attempt % FACES.length];

        // 命中点取目标格的这一面（面本身不影响落点，只影响服务端那边的「点在哪」）
        Vec3 hitPos = Vec3.atCenterOf(spot).add(
            side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, side, spot, false);

        RotateManager.get().rotateTo(Owner.MINE, hitPos);

        ChatUtils.debug("凭空放方块：%s（点它自己的 %s 面，手上 %s）", spot.toShortString(),
            side.getName(), SingleStageData.idOf(player.getInventory().getItem(hotbarIndex)));
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }
}
