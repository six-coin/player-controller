package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.SnapUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 第 (5) 步的**第一步**：挖含水方块 + 给「壳」补粘液块。
 *
 * <p><b>不再一格一格蛇形走</b>，改成「能放多少放多少、放不动了再挪窝」：
 * <ol>
 *   <li>先从两个粘液块盒子里拿 9 组（优先拿不满的那个盒子，不够再拿另一个）；</li>
 *   <li>snap 到目标区域上面那一层的 1 1（<b>不管安不安全</b>：熔岩 / 水都会在这一阶段被填掉，
 *       就算中途碰到也有食物顶着）；</li>
 *   <li>每到一个位置就：把**这个位置能放的洞全部放完** → 把够得着的含水方块挖掉 →
 *       再放一遍（刚挖出来的新洞）→ 然后挑下一个位置；</li>
 *   <li>「下一个位置」的挑法：在走位层（目标区域上面那一层）的 256 个格子里，
 *       找**能放的洞最多**的那一个；一样多就取**离当前位置最近**的；还要走得过去
 *       （snap 得通）。一个都挑不出来（剩下的洞哪个位置都放不了）就结束这一阶段；</li>
 *   <li>结束后 snap 回临时站立点，把身上多余的粘液块放回盒子里。</li>
 * </ol>
 *
 * <p>挖含水方块用的是 {@link MineBlockAction}（和备货一样的朴素挖法），不是 mine simple。
 */
class SingleStageStage1 extends SingleStageStep {

    /** 从盒子里拿几组粘液块。 */
    private static final int SLIME_STACKS = 9;

    /** 最多挪几次窝（防止「怎么放都放不上」的时候空转）。 */
    private static final int MAX_STOPS = 64;

    /** 放粘液块时可以用的快捷栏格（快捷栏 6~8）。 */
    private static final int[] HOTBAR_SLOTS = {SingleStageCtx.HOTBAR_FREE_1,
        SingleStageCtx.HOTBAR_FREE_2, SingleStageCtx.HOTBAR_FREE_3};

    /** 快捷栏用完了从这几格（背包 22~27）补一叠。 */
    private static final int[] MAIN_SLOTS = {SingleStageCtx.FILLER_START,
        SingleStageCtx.FILLER_START + 1, SingleStageCtx.FILLER_START + 2,
        SingleStageCtx.FILLER_START + 3, SingleStageCtx.FILLER_START + 4,
        SingleStageCtx.FILLER_START + 5};

    /** 粘液块 / 树脂块。 */
    static final Predicate<ItemStack> SLIME = stack ->
        stack.isOf(Items.SLIME_BLOCK) || stack.isOf(Items.RESIN_BLOCK);

    private enum Phase {
        /** 从盒子里拿粘液块。 */
        TAKE,
        /** 飞到下一个位置。 */
        TRAVEL,
        /** 把当前位置能放的洞都放掉。 */
        PLACE,
        /** 挖够得着的含水方块。 */
        MINE,
        /** 挖完再放一遍（新挖出来的洞）。 */
        PLACE_AGAIN,
        /** 挑下一个位置。 */
        NEXT,
        /** 回临时站立点。 */
        BACK,
        /** 把多余的粘液块放回盒子里。 */
        PUT_BACK,
        DONE
    }

    private final int iteration;
    private final int walkLayer;

    /** 这一层的「壳」（已经按相邻子区块跳过不用建的墙）。 */
    private final List<BlockPos> shell = new ArrayList<>();

    private Phase phase = Phase.TAKE;
    private BlockPos travelTarget;

    @Nullable
    private Action child;
    private Phase afterChild;

    /** 取 / 放粘液块时用到的盒子（第 {@link #boxIndex} 个，从 1 开始）。 */
    private int boxIndex = 1;
    private List<BlockPos> boxes = List.of();

    /** 这一轮要挖的含水方块。 */
    private final List<BlockPos> mineList = new ArrayList<>();
    private int mineIndex;
    private boolean minePrepared;

    private int stops;

    SingleStageStage1(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.walkLayer = ctx.walkLayer(iteration);
    }

    @Override
    protected String phase() {
        return "第 " + (iteration + 1) + "/" + SingleStageCtx.ITERATIONS + " 层：挖含水方块 + 补粘液块";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        shell.addAll(ctx.shellCells(iteration, mc.world));
        List<BlockPos> gaps = scanGaps(mc.world);
        int water = countWaterlogged(mc.world);

        if (gaps.isEmpty() && water == 0) {
            ChatUtils.debug(phase() + "：没有含水方块也没有漏洞，跳过");
            finish();
            return;
        }

        ChatUtils.info(phase() + "：要补的洞 " + gaps.size() + " 个、含水方块 " + water
            + " 个；先去 " + ctx.routePoint(walkLayer, 1, 1).toShortString());

        if (gaps.isEmpty()) {
            // 没有洞要补：不用拿粘液块，直接去挖含水方块
            phase = Phase.TRAVEL;
        } else {
            // 优先拿不满的那个盒子，不够再拿另一个
            List<BlockPos> sorted = new ArrayList<>(List.of(ctx.slimeSpotA(), ctx.slimeSpotB()));
            sorted.sort(Comparator.comparingInt(ctx.data::boxItems));
            boxes = sorted;
            boxIndex = 1;
            phase = Phase.TAKE;
        }
        travelTarget = ctx.routePoint(walkLayer, 1, 1);
    }

    @Override
    protected void tick() {
        if (isFinished()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
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
    // 主循环
    // ------------------------------------------------------------------

    private void step(MinecraftClient mc) {
        while (!isFinished()) {
            switch (phase) {
                case TAKE -> {
                    if (!takeSlime()) return;
                    phase = Phase.TRAVEL;
                }
                case TRAVEL -> {
                    startChild(new SingleStageSnapAction(ctx, travelTarget), Phase.PLACE);
                    return;
                }
                case PLACE -> {
                    List<BlockPos> gaps = placeable(PlayerUtils.currentBlockPos());
                    if (!gaps.isEmpty()) {
                        startChild(new SingleStagePlaceBatchAction(ctx, gaps, SLIME,
                            HOTBAR_SLOTS, MAIN_SLOTS, "粘液块"), Phase.MINE);
                        return;
                    }
                    phase = Phase.MINE;
                }
                case MINE -> {
                    if (!minePrepared) {
                        mineList.clear();
                        mineList.addAll(reachableWaterlogged(mc.world));
                        mineIndex = 0;
                        minePrepared = true;
                    }
                    if (mineIndex < mineList.size()) {
                        BlockPos pos = mineList.get(mineIndex++);
                        BlockState state = mc.world.getBlockState(pos);
                        startChild(new MineBlockAction(pos, toolSlotFor(state)), Phase.MINE);
                        return;
                    }
                    minePrepared = false;
                    phase = Phase.PLACE_AGAIN;
                }
                case PLACE_AGAIN -> {
                    List<BlockPos> gaps = placeable(PlayerUtils.currentBlockPos());
                    if (!gaps.isEmpty()) {
                        startChild(new SingleStagePlaceBatchAction(ctx, gaps, SLIME,
                            HOTBAR_SLOTS, MAIN_SLOTS, "粘液块"), Phase.NEXT);
                        return;
                    }
                    phase = Phase.NEXT;
                }
                case NEXT -> {
                    if (pickNext(mc)) {
                        phase = Phase.TRAVEL;
                    } else {
                        // 剩下的洞哪个位置都放不了：这一阶段结束，回去把粘液块放好
                        List<BlockPos> sorted =
                            new ArrayList<>(List.of(ctx.slimeSpotA(), ctx.slimeSpotB()));
                        sorted.sort(Comparator.comparingInt(ctx.data::boxItems).reversed());
                        boxes = sorted;
                        boxIndex = 1;
                        phase = Phase.BACK;
                    }
                }
                case BACK -> {
                    startChild(new SingleStageSnapAction(ctx, ctx.standPoint()), Phase.PUT_BACK);
                    return;
                }
                case PUT_BACK -> {
                    if (!putBackSlime()) return;
                    phase = Phase.DONE;
                }
                case DONE -> {
                    finish();
                    return;
                }
            }
        }
    }

    private void startChild(Action action, Phase next) {
        child = action;
        afterChild = next;
        action.start();
    }

    // ------------------------------------------------------------------
    // 取 / 放粘液块
    // ------------------------------------------------------------------

    /** @return true = 拿完了（继续往下走） */
    private boolean takeSlime() {
        if (boxIndex > boxes.size()) return true;

        List<SingleStageBoxGetAction.Want> wants = emptySlots();
        if (wants.isEmpty()) return true;

        BlockPos box = boxes.get(boxIndex - 1);
        boxIndex++;
        startChild(new SingleStageBoxGetAction(ctx, box, SLIME, wants, "粘液块"), Phase.TAKE);
        return false;
    }

    /** @return true = 放完了 */
    private boolean putBackSlime() {
        if (boxIndex > boxes.size()) return true;

        ctx.refresh();
        if (ctx.data.count(slimeIds()) == 0) return true;

        BlockPos box = boxes.get(boxIndex - 1);
        boxIndex++;
        startChild(new SingleStageBoxPutAction(ctx, box, SLIME, "粘液块"), Phase.PUT_BACK);
        return false;
    }

    private static Set<String> slimeIds() {
        return Set.of("minecraft:slime_block", "minecraft:resin_block");
    }

    /** 拿粘液块用的那 9 格：快捷栏 6~8 + 背包 22~27，只挑现在空着的。 */
    private List<SingleStageBoxGetAction.Want> emptySlots() {
        ctx.refresh();
        List<SingleStageBoxGetAction.Want> wants = new ArrayList<>();
        for (int slot : slimeSlots()) {
            if (wants.size() >= SLIME_STACKS) break;
            if (ctx.data.isEmpty(slot)) wants.add(SingleStageBoxGetAction.Want.whole(slot));
        }
        return wants;
    }

    private static List<Integer> slimeSlots() {
        List<Integer> slots = new ArrayList<>();
        slots.add(SingleStageCtx.HOTBAR_FREE_1);
        slots.add(SingleStageCtx.HOTBAR_FREE_2);
        slots.add(SingleStageCtx.HOTBAR_FREE_3);
        for (int i = SingleStageCtx.FILLER_START; i <= SingleStageCtx.FILLER_END; i++) slots.add(i);
        return slots;
    }

    // ------------------------------------------------------------------
    // 挑下一个位置
    // ------------------------------------------------------------------

    /**
     * 挑下一个要去的格子，设置 {@link #travelTarget}。
     *
     * @return false = 剩下的洞哪个位置都放不了（这一阶段该结束了）
     */
    private boolean pickNext(MinecraftClient mc) {
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) return false;

        List<BlockPos> gaps = scanGaps(mc.world);
        if (gaps.isEmpty()) return false;
        if (++stops > MAX_STOPS) {
            ChatUtils.error("补粘液块挪了 " + MAX_STOPS + " 次还是没补完，先停下（还剩 "
                + gaps.size() + " 个洞）");
            return false;
        }

        List<Stand> candidates = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos stand = ctx.routePoint(walkLayer, x, z);
                if (stand.equals(here)) continue;
                int cover = 0;
                for (BlockPos gap : gaps) {
                    if (SingleStageCtx.canPlaceFrom(stand, gap)) cover++;
                }
                if (cover == 0) continue;
                candidates.add(new Stand(stand, cover, here.getSquaredDistance(stand)));
            }
        }

        // 能放的洞最多的优先；一样多就取离得最近的
        candidates.sort(Comparator.comparingInt(Stand::cover).reversed()
            .thenComparingDouble(Stand::distance));

        for (Stand candidate : candidates) {
            if (SnapUtils.plan(mc, here, candidate.pos()) == null) continue;
            travelTarget = candidate.pos();
            ChatUtils.debug(phase() + "：挪到 " + travelTarget.toShortString()
                + "（那里能放 " + candidate.cover() + " 个洞，还剩 " + gaps.size() + " 个）");
            return true;
        }
        return false;
    }

    /** 一个候选落脚点：能放几个洞、离当前位置多远。 */
    private record Stand(BlockPos pos, int cover, double distance) {
    }

    // ------------------------------------------------------------------
    // 扫方块
    // ------------------------------------------------------------------

    /** 壳里现在还能被粘液块替换掉的格子（就是「洞」）。 */
    private List<BlockPos> scanGaps(World world) {
        List<BlockPos> gaps = new ArrayList<>();
        for (BlockPos pos : shell) {
            if (isGap(world.getBlockState(pos))) gaps.add(pos);
        }
        return gaps;
    }

    /** 站在 {@code stand} 的时候能放的那些洞。 */
    private List<BlockPos> placeable(@Nullable BlockPos stand) {
        List<BlockPos> result = new ArrayList<>();
        if (stand == null) return result;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return result;

        for (BlockPos pos : shell) {
            if (!isGap(mc.world.getBlockState(pos))) continue;
            if (!SingleStageCtx.canPlaceFrom(stand, pos)) continue;
            result.add(pos);
        }
        return result;
    }

    /** 够得着的含水方块。 */
    private List<BlockPos> reachableWaterlogged(World world) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : ctx.wrapCells(iteration, world)) {
            if (!isWaterlogged(world.getBlockState(pos))) continue;
            if (!PlayerUtils.isWithinReach(pos)) continue;
            result.add(pos);
        }
        return result;
    }

    private int countWaterlogged(World world) {
        int total = 0;
        for (BlockPos pos : ctx.wrapCells(iteration, world)) {
            if (isWaterlogged(world.getBlockState(pos))) total++;
        }
        return total;
    }

    // ------------------------------------------------------------------

    /** 这个方块是不是 {@code waterlogged=true}。 */
    static boolean isWaterlogged(BlockState state) {
        return state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED);
    }

    /**
     * 这个方块是不是「漏洞」：凡是**能被粘液块直接替换掉**的都算。
     *
     * <p>用原版的 {@code replaceable} 判定（{@code BlockState#isReplaceable}）：
     * 空气、水、熔岩、藤蔓、草、雪片……全都在里面。以前只认「空气 / 流体」，
     * 藤蔓、草这类也一样会漏水，却漏掉了。
     */
    static boolean isGap(BlockState state) {
        return state.isReplaceable();
    }

    /** 挖这个方块该用快捷栏第几格（剑镐斧铲锄，下标 0~4）。 */
    static int toolSlotFor(BlockState state) {
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) return SingleStageCtx.HOTBAR_PICKAXE;
        if (state.isIn(BlockTags.AXE_MINEABLE)) return SingleStageCtx.HOTBAR_AXE;
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) return SingleStageCtx.HOTBAR_SHOVEL;
        if (state.isIn(BlockTags.HOE_MINEABLE)) return SingleStageCtx.HOTBAR_HOE;
        return SingleStageCtx.HOTBAR_PICKAXE;
    }
}
