package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * **一次放一堆方块**（没有速率限制，一个 tick 里放很多个）。
 *
 * <p>和 {@link SingleStagePlaceAction} 一样是凭空放置（点目标格自己），区别是：
 * <ul>
 *   <li>一 tick 最多放 {@value #MAX_PER_TICK} 个，不是「放一个等两 tick」；</li>
 *   <li>手上那叠用完了会**自动补**：先找别的快捷栏格，再点物品栏格子把背包里的挪一叠过来
 *       （见 {@link #ensureHand}）；</li>
 *   <li>已经不用放了的位置（被别人放了 / 变成水了）直接跳过；放不上去的重试几次，
 *       还不行就记一笔失败继续往下放，不会因为一个格子卡死整段。</li>
 * </ul>
 *
 * <p>点击是本地预测、立即生效的，所以放完当场就能查方块状态，不用等。
 */
class SingleStagePlaceBatchAction extends SingleStageStep {

    /** 一个 tick 最多放几个（够快了，也不至于一 tick 发几百个交互包）。 */
    private static final int MAX_PER_TICK = 64;

    /** 同一个位置最多试几次。 */
    private static final int MAX_ATTEMPTS = 4;

    private final List<BlockPos> pending;
    private final Predicate<ItemStack> blockFilter;
    /** 手上可以用的快捷栏格（补货也只往这几格里补）。 */
    private final int[] hotbarSlots;
    /** 不够的时候从这几格（主背包）挪一叠过来；空数组 = 不补货。 */
    private final int[] mainSlots;
    private final String what;

    private final Map<Long, Integer> attempts = new LinkedHashMap<>();

    private int placed;
    private int failed;
    private int skipped;
    private boolean outOfBlocks;
    private boolean hotbarHeld;

    SingleStagePlaceBatchAction(SingleStageCtx ctx, List<BlockPos> spots,
                                Predicate<ItemStack> blockFilter,
                                int[] hotbarSlots, int[] mainSlots, String what) {
        super(ctx);
        this.pending = new ArrayList<>(spots);
        this.blockFilter = blockFilter;
        this.hotbarSlots = hotbarSlots;
        this.mainSlots = mainSlots;
        this.what = what;
    }

    @Override
    protected String phase() {
        return "放" + what + "（" + pending.size() + " 个）";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            fail("玩家或世界不存在");
            return;
        }
        if (pending.isEmpty()) finish();
    }

    @Override
    protected void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            fail("玩家或世界不存在");
            return;
        }

        int budget = MAX_PER_TICK;
        Iterator<BlockPos> iterator = pending.iterator();
        while (iterator.hasNext() && budget > 0) {
            BlockPos pos = iterator.next();

            if (!SingleStagePlaceAction.replaceable(mc.level.getBlockState(pos))) {
                iterator.remove();
                skipped++;
                continue;
            }
            if (!ensureHand(player)) {
                outOfBlocks = true;
                break;
            }

            budget--;
            if (place(mc, player, pos)) {
                iterator.remove();
                placed++;
            } else {
                int count = attempts.merge(pos.asLong(), 1, Integer::sum);
                if (count >= MAX_ATTEMPTS) {
                    iterator.remove();
                    failed++;
                    ChatUtils.debug("放不上去，跳过：" + pos.toShortString());
                }
            }
        }

        if (pending.isEmpty() || outOfBlocks) finish();
    }

    @Override
    protected void onEnd() {
        if (hotbarHeld) {
            HotbarManager.get().release(Owner.MINE);
            hotbarHeld = false;
        }
        RotateManager.get().release(Owner.MINE);

        if (failureReason() != null) return;
        ChatUtils.debug("放%s：成功 %d、跳过 %d、失败 %d%s", what, placed, skipped, failed,
            outOfBlocks ? "（" + what + "用完了）" : "");
        if (outOfBlocks && !pending.isEmpty()) {
            ChatUtils.error(what + "用完了，还有 " + pending.size() + " 个位置没放");
        }
        if (failed > 0) {
            ChatUtils.error("有 " + failed + " 个" + what + "放不上去");
        }
    }

    // ------------------------------------------------------------------

    /**
     * 保证手上（选中的快捷栏格）有一叠能放的方块；没有就从背包挪一叠过来。
     *
     * @return false = 身上没得放了
     */
    private boolean ensureHand(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        if (contains(hotbarSlots, selected)
            && blockFilter.test(player.getInventory().getItem(selected))) {
            return true;
        }

        // 别的快捷栏格里有
        for (int slot : hotbarSlots) {
            if (!blockFilter.test(player.getInventory().getItem(slot))) continue;
            if (!HotbarManager.get().request(Owner.MINE, slot)) return false;
            hotbarHeld = true;
            return true;
        }

        // 从背包补一叠（目标快捷栏格必须是空的，别把东西挤到背包里）
        for (int main : mainSlots) {
            ItemStack stack = player.getInventory().getItem(main);
            if (stack.isEmpty() || !blockFilter.test(stack)) continue;

            int free = freeHotbar(player);
            if (free < 0) {
                // 快捷栏 6~8 被别的东西占着（挖着挖着吸进来的杂物）：
                // 跟它换一下（容器操作，点格子），把占用物换到背包这一格，粘液块换到快捷栏
                free = occupiedHotbar(player);
                if (free < 0) return false;
                if (!SingleStageInventoryAction.swapNow(player, main, free)) return false;
                if (!HotbarManager.get().request(Owner.MINE, free)) return false;
                hotbarHeld = true;
                ChatUtils.debug("快捷栏第 " + (free + 1) + " 格被占了，和背包第 " + main
                    + " 格的" + what + "换了");
                return true;
            }
            if (!SingleStageInventoryAction.swapNow(player, main, free)) return false;
            if (!HotbarManager.get().request(Owner.MINE, free)) return false;
            hotbarHeld = true;
            ChatUtils.debug("把第 " + main + " 格的" + what + "挪到快捷栏第 " + (free + 1) + " 格");
            return true;
        }
        return false;
    }

    private int freeHotbar(LocalPlayer player) {
        for (int slot : hotbarSlots) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** 快捷栏那几格里第一个「被别的东西占着」的格子（不是空的、也不是要放的方块）。 */
    private int occupiedHotbar(LocalPlayer player) {
        for (int slot : hotbarSlots) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (blockFilter.test(stack)) continue;
            return slot;
        }
        return -1;
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) return true;
        }
        return false;
    }

    /** 凭空放一个：点目标格自己（面随便），放上了返回 true。 */
    private boolean place(Minecraft mc, LocalPlayer player, BlockPos pos) {
        Direction side = Direction.UP;
        Vec3 hitPos = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0);
        BlockHitResult hit = new BlockHitResult(hitPos, side, pos, false);

        RotateManager.get().rotateTo(Owner.MINE, hitPos);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

        return !SingleStagePlaceAction.replaceable(mc.level.getBlockState(pos));
    }
}
