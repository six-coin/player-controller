package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 用**圆石**把 {@code detail} 里配成 {@code cobblestone} 的那几面墙 / 底面填上。
 *
 * <p>为什么要单独一段：黏液块是「挖完还要回收」的，圆石是「填完就留在那儿」的；
 * 两者用的盒子、携带的格子都不一样，混在一起放很容易互相挤掉。
 * 所以每一轮先跑这一段（圆石），再跑 {@link SingleStageStage1}（黏液块 + 含水方块）——
 * Stage1 的「洞」判定里圆石不算洞（圆石不可替换），所以不会把圆石又盖成黏液块。
 *
 * <p>走位和 Stage1 一个套路：在走位层（目标区域上面那一层）上挑「能放的圆石格最多、离得最近」
 * 的那一格，飞过去一次放完，放不动了再挑下一个。圆石从两个圆石盒里取（最多 6 组 = 384 个，
 * 刚好够「四面墙 + 底面」一整轮），放不完的最后放回盒子里。
 */
class SingleStageCobblePass extends SingleStageStep {

    /** 最多挪几次窝（防止空转）。 */
    private static final int MAX_STOPS = 48;

    /** 一次拿几组圆石（6 组 = 背包 22~27 那六格）。 */
    private static final int COBBLE_STACKS = 6;

    /** 放圆石时可以用的快捷栏格（快捷栏 6~8）。 */
    private static final int[] HOTBAR_SLOTS = {SingleStageCtx.HOTBAR_FREE_1,
        SingleStageCtx.HOTBAR_FREE_2, SingleStageCtx.HOTBAR_FREE_3};

    /** 快捷栏用完了从这几格（背包 22~27）补一叠。 */
    private static final int[] MAIN_SLOTS = {SingleStageCtx.FILLER_START,
        SingleStageCtx.FILLER_START + 1, SingleStageCtx.FILLER_START + 2,
        SingleStageCtx.FILLER_START + 3, SingleStageCtx.FILLER_START + 4,
        SingleStageCtx.FILLER_START + 5};

    static final Predicate<ItemStack> COBBLE = stack -> stack.is(Items.COBBLESTONE);

    private enum Phase {
        /** 先回临时站立点（盒子在它旁边，得够得着才打得开）。 */
        HOME,
        /** 从圆石盒里拿圆石。 */
        TAKE,
        /** 飞到下一个位置。 */
        TRAVEL,
        /** 把当前位置能放的圆石格都放掉。 */
        PLACE,
        /** 挑下一个位置。 */
        NEXT,
        /** 回走位层的 1 1（回临时站立点的跳板）。 */
        BACK,
        /** 再从 1 1 回临时站立点。 */
        BACK_HOME,
        /** 把没用完的圆石放回盒子。 */
        PUT_BACK,
        DONE
    }

    private final int iteration;
    private final int walkLayer;

    /** 还没填上的圆石格。 */
    private final List<BlockPos> pending = new ArrayList<>();

    private Phase phase = Phase.HOME;
    @Nullable
    private Action child;
    private Phase afterChild = Phase.TAKE;

    private List<BlockPos> boxes = List.of();
    private int boxIndex = 1;
    /** 放回圆石的时候另算一个下标（{@link #boxIndex} 取货时已经走到头了）。 */
    private int putIndex;
    private BlockPos travelTarget;
    private int stops;
    /** 身上的圆石用完了（别再挑落点了，直接收尾）。 */
    private boolean outOfCobble;

    SingleStageCobblePass(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.walkLayer = ctx.walkLayer(iteration);
    }

    @Override
    protected String phase() {
        return "第 " + (iteration + 1) + "/" + ctx.levels() + " 层：补圆石墙";
    }

    @Override
    protected void onStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        for (BlockPos pos : ctx.cobbleCells(iteration, mc.level)) {
            if (isReplaceable(mc.level.getBlockState(pos))) pending.add(pos);
        }
        if (pending.isEmpty()) {
            ChatUtils.debug(phase() + "：这一层没有要用圆石填的地方，跳过");
            finish();
            return;
        }

        boxes = boxesFullestFirst();
        boxIndex = 1;
        travelTarget = ctx.routePoint(walkLayer, 1, 1);
        ChatUtils.info(phase() + "：要放 " + pending.size() + " 个圆石，先从盒子里取");
    }

    @Override
    protected void tick() {
        if (isFinished()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        if (child != null) {
            child.update();
            if (!child.isFinished()) return;

            Action done = child;
            child = null;
            done.cleanup();
            if (done.failureReason() != null) {
                fail(done.failureReason());
                return;
            }
            phase = afterChild;
            return;
        }

        step(mc);
    }

    @Override
    protected void onEnd() {
        if (child != null) {
            child.cleanup();
            child = null;
        }
    }

    // ------------------------------------------------------------------

    private void startChild(Action action, Phase next) {
        child = action;
        afterChild = next;
        action.start();
    }

    private void step(Minecraft mc) {
        while (!isFinished()) {
            switch (phase) {
                case HOME -> {
                    startChild(new SingleStageSnapAction(ctx, ctx.standPoint()), Phase.TAKE);
                    return;
                }
                case TAKE -> {
                    if (!takeCobble()) {
                        if (cobbleCount() <= 0) {
                            ChatUtils.error("圆石盒里没有圆石了，这一层还有 " + pending.size()
                                + " 个位置要用圆石填（先用黏液块顶上了）");
                            phase = Phase.BACK;
                        } else {
                            phase = Phase.TRAVEL;
                        }
                    } else {
                        return;
                    }
                }
                case TRAVEL -> {
                    startChild(new SingleStageSnapAction(ctx, travelTarget), Phase.PLACE);
                    return;
                }
                case PLACE -> {
                    if (!placeHere(mc)) return;
                    phase = Phase.NEXT;
                }
                case NEXT -> {
                    if (!outOfCobble && pickNext(mc)) {
                        phase = Phase.TRAVEL;
                    } else {
                        phase = Phase.BACK;
                    }
                }
                case BACK -> {
                    startChild(new SingleStageSnapAction(ctx, ctx.routePoint(walkLayer, 1, 1)),
                        Phase.BACK_HOME);
                    return;
                }
                case BACK_HOME -> {
                    startChild(new SingleStageSnapAction(ctx, ctx.standPoint()), Phase.PUT_BACK);
                    return;
                }
                case PUT_BACK -> {
                    if (!putBackCobble()) {
                        phase = Phase.DONE;
                    } else {
                        return;
                    }
                }
                case DONE -> {
                    finish();
                    return;
                }
            }
        }
    }

    /** @return false = 拿完了（继续往下走） */
    private boolean takeCobble() {
        if (boxIndex > boxes.size()) return false;

        ctx.refresh();
        List<SingleStageBoxGetAction.Want> wants = emptySlots();
        if (wants.isEmpty()) return false;

        BlockPos box = boxes.get(boxIndex - 1);
        boxIndex++;
        startChild(new SingleStageBoxGetAction(ctx, box, COBBLE, wants, "圆石"), Phase.TAKE);
        return true;
    }

    /** @return false = 放完了（继续往下走） */
    private boolean putBackCobble() {
        if (putIndex >= boxes.size()) return false;

        ctx.refresh();
        if (ctx.data.count(COBBLE_IDS) == 0) return false;

        BlockPos box = boxes.get(putIndex);
        putIndex++;
        startChild(new SingleStageBoxPutAction(ctx, box, COBBLE, "圆石"), Phase.PUT_BACK);
        return true;
    }

    private static final java.util.Set<String> COBBLE_IDS = java.util.Set.of("minecraft:cobblestone");

    private List<SingleStageBoxGetAction.Want> emptySlots() {
        List<SingleStageBoxGetAction.Want> wants = new ArrayList<>();
        for (int slot : MAIN_SLOTS) {
            if (wants.size() >= COBBLE_STACKS) break;
            if (ctx.data.isEmpty(slot)) wants.add(SingleStageBoxGetAction.Want.whole(slot));
        }
        return wants;
    }

    /**
     * 把当前位置能放的圆石格放掉。
     *
     * @return {@code false} = 起了子动作
     */
    private boolean placeHere(Minecraft mc) {
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) return true;

        pending.removeIf(pos -> !isReplaceable(mc.level.getBlockState(pos)));
        if (pending.isEmpty()) return true;

        if (cobbleCount() <= 0) {
            outOfCobble = true;
            ChatUtils.error("圆石用完了，还有 " + pending.size() + " 个位置没填（盒子里的也不够）");
            return true;
        }

        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : pending) {
            if (SingleStageCtx.canPlaceFrom(here, pos)) targets.add(pos);
        }
        if (targets.isEmpty()) return true;

        startChild(new SingleStagePlaceBatchAction(ctx, targets, COBBLE, HOTBAR_SLOTS, MAIN_SLOTS,
            "圆石"), Phase.NEXT);
        return false;
    }

    /** 身上（物品栏）还有几个圆石。 */
    private int cobbleCount() {
        var player = PlayerUtils.player();
        if (player == null) return 0;
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.COBBLESTONE)) total += stack.getCount();
        }
        return total;
    }

    /**
     * 挑下一个要去的格子。
     *
     * @return false = 剩下的圆石格哪个位置都放不了（该收尾了）
     */
    private boolean pickNext(Minecraft mc) {
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) return false;

        pending.removeIf(pos -> !isReplaceable(mc.level.getBlockState(pos)));
        if (pending.isEmpty()) return false;

        if (++stops > MAX_STOPS) {
            ChatUtils.error("补圆石墙挪了 " + MAX_STOPS + " 次还是没弄完，先停下（还剩 "
                + pending.size() + " 个）");
            return false;
        }

        BlockPos best = null;
        int bestCover = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos stand = ctx.routePoint(walkLayer, x, z);
                if (stand.equals(here)) continue;

                int cover = 0;
                for (BlockPos pos : pending) {
                    if (SingleStageCtx.canPlaceFrom(stand, pos)) cover++;
                }
                if (cover == 0) continue;

                double distance = here.distSqr(stand);
                if (cover > bestCover || (cover == bestCover && distance < bestDistance)) {
                    best = stand;
                    bestCover = cover;
                    bestDistance = distance;
                }
            }
        }

        if (best == null) return false;

        travelTarget = best;
        ChatUtils.debug(phase() + "：挪到 " + travelTarget.toShortString()
            + "（能放 " + bestCover + " 个，还剩 " + pending.size() + " 个）");
        return true;
    }

    /** 两个圆石盒，圆石多的排前面。 */
    private List<BlockPos> boxesFullestFirst() {
        List<BlockPos> sorted = new ArrayList<>(List.of(ctx.cobbleSpotA(), ctx.cobbleSpotB()));
        sorted.sort(Comparator.comparingInt(
            (BlockPos box) -> ctx.data.boxCount(box, Items.COBBLESTONE)).reversed());
        return sorted;
    }

    private static boolean isReplaceable(BlockState state) {
        return SingleStagePlaceAction.replaceable(state);
    }
}
