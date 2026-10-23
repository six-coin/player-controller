package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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
 *   <li><b>两个区域规则不一样</b>：
 *       <ul>
 *         <li>「边缘目标区域」（外面那一圈壳）：**能替换的全替换**（空气、水、熔岩、藤蔓、草…）；</li>
 *         <li>「目标区域」里面：**只替换水和熔岩**（空气 / 草 / 藤蔓不碰，反正马上要挖掉）；</li>
 *       </ul>
 *       含水方块照旧在全量目标区域（区域 + 壳）里找；</li>
 *   <li>粘液块用完了会自动补一趟：先 snap 回走位层的 1 1、再回临时站立点开盒子拿，
 *       然后飞回原来的位置接着补；</li>
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

    /** 一阶段里最多回盒子补几次粘液块。 */
    private static final int MAX_REFILLS = 6;

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
        /** 粘液块用完了：先飞回走位层的 1 1（回临时站立点的必经之路）。 */
        REFILL_UP,
        /** 再从 1 1 飞回临时站立点。 */
        REFILL_GO,
        /** 补完货先飞回走位层的 1 1。 */
        REFILL_BACK,
        /** 再从 1 1 飞回原来那个位置。 */
        REFILL_RETURN,
        /** 先回走位层的 1 1（回临时站立点的跳板）。 */
        BACK,
        /** 再从 1 1 回临时站立点。 */
        BACK_HOME,
        /** 把多余的粘液块放回盒子里。 */
        PUT_BACK,
        DONE
    }

    private final int iteration;
    private final int walkLayer;

    /** 目标区域（要挖掉的那 16×16×2）：这里**只替换水和熔岩**。 */
    private final List<BlockPos> region = new ArrayList<>();
    /** 边缘目标区域（外面那一圈壳）：这里**能替换的全替换**。 */
    private final List<BlockPos> shell = new ArrayList<>();
    /** 全量目标区域 = 区域 + 壳（找含水方块用）。 */
    private final List<BlockPos> wrap = new ArrayList<>();

    private Phase phase = Phase.TAKE;
    private BlockPos travelTarget;

    @Nullable
    private Action child;
    private Phase afterChild;

    /** 取 / 放粘液块时用到的盒子（第 {@link #boxIndex} 个，从 1 开始）。 */
    private int boxIndex = 1;
    private List<BlockPos> boxes = List.of();
    /** 取完粘液块之后去哪（开局是 {@link Phase#TRAVEL}，补货时是 {@link Phase#REFILL_BACK}）。 */
    private Phase afterTake = Phase.TRAVEL;
    /** 补货时要飞回的位置。 */
    private BlockPos refillReturn;
    private int refills;


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

        region.addAll(ctx.regionCells(iteration));
        shell.addAll(ctx.shellCells(iteration, mc.world));
        wrap.addAll(region);
        wrap.addAll(shell);
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
                    phase = afterTake;
                }
                case TRAVEL -> {
                    startChild(new SingleStageSnapAction(ctx, travelTarget), Phase.PLACE);
                    return;
                }
                case PLACE -> {
                    if (!placeHere(mc, Phase.MINE)) return;
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
                    if (!placeHere(mc, Phase.NEXT)) return;
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
                case REFILL_UP -> {
                    // 直接飞临时站立点可能没有沿轴走得通的路（人在矿洞深处），
                    // 先回到走位层的 1 1 —— 那儿和临时站立点之间一定通
                    startChild(new SingleStageSnapAction(ctx, ctx.routePoint(walkLayer, 1, 1)),
                        Phase.REFILL_GO);
                    return;
                }
                case REFILL_GO -> {
                    // 从 1 1 回临时站立点（盒子在那儿），拿完再飞回来
                    startChild(new SingleStageSnapAction(ctx, ctx.standPoint()), Phase.TAKE);
                    return;
                }
                case REFILL_BACK -> {
                    // 回程一样要经过 1 1：从临时站立点直接飞矿洞深处可能没有沿轴的路
                    startChild(new SingleStageSnapAction(ctx, ctx.routePoint(walkLayer, 1, 1)),
                        Phase.REFILL_RETURN);
                    return;
                }
                case REFILL_RETURN -> {
                    startChild(new SingleStageSnapAction(ctx, refillReturn), Phase.PLACE);
                    return;
                }
                case BACK -> {
                    // 从下面回临时站立点一定要以 1 1 为跳板：直接飞没有沿轴走得通的路
                    startChild(new SingleStageSnapAction(ctx, ctx.routePoint(walkLayer, 1, 1)),
                        Phase.BACK_HOME);
                    return;
                }
                case BACK_HOME -> {
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

    /**
     * 把当前位置能放的洞放掉。
     *
     * @return {@code false} = 起了子动作（等它跑完再继续）
     */
    private boolean placeHere(MinecraftClient mc, Phase next) {
        BlockPos here = PlayerUtils.currentBlockPos();
        List<BlockPos> gaps = holes(here, mc.world);
        if (gaps.isEmpty()) {
            phase = next;
            return true;
        }

        if (slimeCount() <= 0) {
            // 身上的粘液块用完了：盒子里还有的话就回去补一趟
            int left = ctx.data.boxItems(ctx.slimeSpotA()) + ctx.data.boxItems(ctx.slimeSpotB());
            if (left > 0 && refills < MAX_REFILLS) {
                refills++;
                refillReturn = here;
                boxes = boxesLeastFull();
                boxIndex = 1;
                afterTake = Phase.REFILL_BACK;
                phase = Phase.REFILL_UP;
                ChatUtils.info(phase() + "：粘液块用完了，回去补一趟（盒子里还有 " + left + " 个）");
                return true;
            }
            ChatUtils.error("粘液块用完了，还有 " + gaps.size() + " 个洞没补（盒子里也没货了）");
            phase = next;
            return true;
        }

        startChild(new SingleStagePlaceBatchAction(ctx, gaps, SLIME, HOTBAR_SLOTS, MAIN_SLOTS,
            "粘液块"), next);
        return false;
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

        if (candidates.isEmpty()) return false;

        // 走位层是清过的（不会有阻挡），所以不做 snap 检查，直接取排序后的第一个
        Stand best = candidates.get(0);
        travelTarget = best.pos();
        ChatUtils.debug(phase() + "：挪到 " + travelTarget.toShortString()
            + "（那里能放 " + best.cover() + " 个洞，还剩 " + gaps.size() + " 个）");
        return true;
    }

    /** 一个候选落脚点：能放几个洞、离当前位置多远。 */
    private record Stand(BlockPos pos, int cover, double distance) {
    }

    // ------------------------------------------------------------------
    // 扫方块
    // ------------------------------------------------------------------

    /**
     * 现在的「洞」：壳里能替换的都算，区域里只算水和熔岩。
     *
     * @param stand {@code null} = 不看距离（全扫一遍）
     */
    private List<BlockPos> holes(@Nullable BlockPos stand, World world) {
        List<BlockPos> result = new ArrayList<>();

        for (BlockPos pos : shell) {
            if (!isGap(world.getBlockState(pos))) continue;
            if (stand != null && !SingleStageCtx.canPlaceFrom(stand, pos)) continue;
            result.add(pos);
        }
        for (BlockPos pos : region) {
            if (!isFluid(world.getBlockState(pos))) continue;
            if (stand != null && !SingleStageCtx.canPlaceFrom(stand, pos)) continue;
            result.add(pos);
        }
        return result;
    }

    /** 全量目标区域里现在还有哪些洞（不看距离）。 */
    private List<BlockPos> scanGaps(World world) {
        return holes(null, world);
    }

    /** 够得着的含水方块（同样只看全量目标区域）。 */
    private List<BlockPos> reachableWaterlogged(World world) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : wrap) {
            if (!isWaterlogged(world.getBlockState(pos))) continue;
            if (!PlayerUtils.isWithinReach(pos)) continue;
            result.add(pos);
        }
        return result;
    }

    private int countWaterlogged(World world) {
        int total = 0;
        for (BlockPos pos : wrap) {
            if (isWaterlogged(world.getBlockState(pos))) total++;
        }
        return total;
    }

    // ------------------------------------------------------------------

    /** 这个方块是不是 {@code waterlogged=true}。 */
    static boolean isWaterlogged(BlockState state) {
        return state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED);
    }

    /** 这个方块是不是水 / 熔岩（目标区域里只替换这两种）。 */
    static boolean isFluid(BlockState state) {
        return state.getBlock() instanceof FluidBlock;
    }

    /**
     * 这个方块是不是「漏洞」：凡是**能被粘液块直接替换掉**的都算。
     *
     * <p>用原版的 {@code replaceable} 判定（{@code BlockState#isReplaceable}）：
     * 空气、水、熔岩、藤蔓、草、雪片……全都在里面。
     * 只用在「边缘目标区域」（壳）上 —— 目标区域里只替换水和熔岩，见 {@link #isFluid}。
     */
    static boolean isGap(BlockState state) {
        return state.isReplaceable();
    }

    /** 物品栏里还有多少个粘液块 / 树脂块。 */
    private int slimeCount() {
        ClientPlayerEntity player = PlayerUtils.player();
        if (player == null) return 0;
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (SLIME.test(stack)) total += stack.getCount();
        }
        return total;
    }

    /** 两个粘液块盒子里，内容少的排前面（补货先拿快空的那个）。 */
    private List<BlockPos> boxesLeastFull() {
        List<BlockPos> sorted = new ArrayList<>(List.of(ctx.slimeSpotA(), ctx.slimeSpotB()));
        sorted.sort(Comparator.comparingInt(ctx.data::boxItems));
        return sorted;
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
