package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *   <li>每到一个位置就：先把**够得着的含水方块一次性全打掉**（同时发包、一个都不等，
 *       发完等几 tick 按世界里的方块核对一遍），再把**这个位置能放的洞全部放完**
 *       （放完只等 1 tick 就走）；然后挑下一个位置；</li>
 *   <li><b>两个区域规则不一样</b>：
 *       <ul>
 *         <li>「边缘目标区域」（外面那一圈壳）：**能替换的全替换**（空气、水、熔岩、藤蔓、草…）；</li>
 *         <li>「目标区域」里面：**只替换水和熔岩**（空气 / 草 / 藤蔓不碰，反正马上要挖掉）；</li>
 *       </ul>
 *       含水方块照旧在全量目标区域（区域 + 壳）里找；</li>
 *   <li>粘液块用完了会自动补一趟：先 snap 回走位层的 1 1、再回临时站立点开盒子拿
 *       （**每次只拿 6 组**，装进背包 22~27，快捷栏 6~8 留空），然后飞回原来的位置接着补。
 *       盒子里的不够 6 组就把剩下的全拿出来，再换一盒（背包里那盒最多的）；
 *       换盒前快捷栏 6~8 要是被占着，先跑去圆石那儿把垃圾扔了。多出来的最后会放回盒子；</li>
 *   <li>「下一个位置」的挑法：在走位层（目标区域上面那一层）的 256 个格子里，
 *       找**能放的洞最多**的那一个；一样多就取**离当前位置最近**的；还要走得过去
 *       （snap 得通）。一个都挑不出来（剩下的洞哪个位置都放不了）就结束这一阶段；</li>
 *   <li>结束后 snap 回临时站立点，把身上多余的粘液块放回盒子里。</li>
 * </ol>
 *
 * <p>挖含水方块用的是 {@link MineBlockAction}（和备货一样的朴素挖法），不是 mine simple。
 */
class SingleStageStage1 extends SingleStageStep {

    /**
     * 一次拿几组粘液块。
     *
     * <p>6 组 = 背包 22~27 这六格，**快捷栏 6~8 留空**（放盒子 / 扔垃圾都要用）。
     * 不用估「还剩多少地方要补」，每次拿满 6 组就行，多出来的最后会放回盒子。
     */
    private static final int SLIME_STACKS = 6;

    /** 最多挪几次窝（防止「怎么放都放不上」的时候空转）。 */
    private static final int MAX_STOPS = 96;

    /** 挑落脚点时，一个洞算多少分（含水方块算 1 分）—— 先把洞填完，再补远处的含水方块。 */
    private static final int HOLE_WEIGHT = 4;

    /** 一阶段里最多回盒子补几次粘液块（一次只拿 6 组，所以给得宽一点）。 */
    private static final int MAX_REFILLS = 12;

    /** 一阶段里最多换几次盒子（把放着的空盒换成背包里的备用盒）。 */
    private static final int MAX_SWAPS = 3;

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
        stack.is(Items.SLIME_BLOCK) || stack.is(Items.RESIN_BLOCK);

    private enum Phase {
        /** 从盒子里拿粘液块。 */
        TAKE,
        /** 飞到下一个位置。 */
        TRAVEL,
        /** 把当前位置能放的洞都放掉。 */
        PLACE,
        /** 把够得着的含水方块一次性全打掉。 */
        MINE,
        /** 放完等 1 tick 再走。 */
        SETTLE,
        /** 挑下一个位置。 */
        NEXT,
        /** 粘液块用完了：先飞回走位层的 1 1（回临时站立点的必经之路）。 */
        REFILL_UP,
        /** 再从 1 1 飞回临时站立点。 */
        REFILL_GO,
        /** 补完货：看看盒子是不是已经空了。 */
        REFILL_CHECK,
        /** 快捷栏 6~8 被占着：先跑去圆石那儿把垃圾扔了。 */
        JUNK,
        /** 盒子里的不够了：把放着的那盒换成背包里的备用盒。 */
        SWAP,
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
    /** 壳里那些坐标的打包值（判断某一格属不属于壳，O(1)）。 */
    private final Set<Long> shellKeys = new HashSet<>();
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
    private int swaps;

    /** 换盒子那一小串步骤（挖掉 → 吸进来 → 放备用盒 → 换格）；每次换都新建一个。 */
    @Nullable
    private SingleStageQueue swapQueue;
    private boolean swapping;
    private BlockPos swapSpot;
    private List<ItemStack> swapContents = List.of();
    private Map<Integer, String> swapBefore = Map.of();
    private int swapReserve = -1;

    /** 扔垃圾那一小串步骤；每次扔都新建一个。 */
    @Nullable
    private SingleStageQueue junkQueue;
    private boolean junking;

    /** 放完之后等了几 tick。 */
    private int settleTicks;



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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        region.addAll(ctx.regionCells(iteration));
        shell.addAll(ctx.shellCells(iteration, mc.level));
        for (BlockPos pos : shell) shellKeys.add(pos.asLong());
        wrap.addAll(region);
        wrap.addAll(shell);
        List<BlockPos> gaps = scanGaps(mc.level);
        int water = countWaterlogged(mc.level);

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
    // 主循环
    // ------------------------------------------------------------------

    private void step(Minecraft mc) {
        while (!isFinished()) {
            switch (phase) {
                case TAKE -> {
                    if (!takeSlime()) return;
                    phase = afterTake;
                }
                case TRAVEL -> {
                    startChild(new SingleStageSnapAction(ctx, travelTarget), Phase.MINE);
                    return;
                }
                case MINE -> {
                    // 先把够得着的含水方块一次性全打掉（同时发包，不等），再统一放粘液块
                    List<BlockPos> targets = reachableWaterlogged(mc.level);
                    if (!targets.isEmpty()) {
                        startChild(new SingleStageMineBatchAction(ctx, targets), Phase.PLACE);
                        return;
                    }
                    phase = Phase.PLACE;
                }
                case PLACE -> {
                    if (!placeHere(mc, Phase.SETTLE)) return;
                }
                case SETTLE -> {
                    // 只等 1 tick，然后就走（不再做二次检测）
                    if (settleTicks++ < 1) return;
                    settleTicks = 0;
                    phase = Phase.NEXT;
                }
                case NEXT -> {
                    if (pickNext(mc)) {
                        phase = Phase.TRAVEL;
                    } else {
                        // 收尾前把「还剩什么」报出来（够不着的地方会留下来，挖到那层就冒水）
                        List<BlockPos> leftHoles = scanGaps(mc.level);
                        List<BlockPos> leftWater = unminedWaterlogged(mc.level);
                        if (!leftHoles.isEmpty() || !leftWater.isEmpty()) {
                            ChatUtils.error("补粘液块收尾：还有 " + leftHoles.size() + " 个洞、"
                                + leftWater.size() + " 个含水方块够不着（走位层上放不到 / 挖不到）");
                        }
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
                case REFILL_CHECK -> {
                    if (!shortAfterTake() || swaps >= MAX_SWAPS || fullestSlimeBoxSlot() < 0) {
                        // 拿满了 / 换过太多次 / 背包里没备用盒了 → 回去接着补
                        phase = Phase.REFILL_BACK;
                    } else if (firstFreeHotbar() < 0) {
                        // 快捷栏 6~8 被占着（放备用盒要一格空的）：先把垃圾扔了
                        phase = Phase.JUNK;
                    } else {
                        swaps++;
                        phase = Phase.SWAP;
                    }
                }
                case JUNK -> {
                    if (!junking) {
                        junking = true;
                        buildJunkPlan();
                    }
                    if (!junkQueue.tick()) return;
                    if (junkQueue.failed() != null) {
                        fail(junkQueue.failed().failureReason());
                        return;
                    }
                    junking = false;
                    swaps++;
                    phase = Phase.SWAP;
                }
                case SWAP -> {
                    if (!swapping) {
                        swapping = true;
                        buildSwapPlan();
                    }
                    if (!swapQueue.tick()) return;
                    if (swapQueue.failed() != null) {
                        fail(swapQueue.failed().failureReason());
                        return;
                    }
                    swapping = false;
                    // 换上新盒子之后再拿一次
                    boxes = boxesLeastFull();
                    boxIndex = 1;
                    afterTake = Phase.REFILL_CHECK;
                    phase = Phase.TAKE;
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
    private boolean placeHere(Minecraft mc, Phase next) {
        BlockPos here = PlayerUtils.currentBlockPos();
        List<BlockPos> gaps = holes(here, mc.level);
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
                afterTake = Phase.REFILL_CHECK;
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

    /** 拿粘液块用的格子：只有背包 22~27（6 组），快捷栏 6~8 留着别占。 */
    private static List<Integer> slimeSlots() {
        List<Integer> slots = new ArrayList<>();
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
    private boolean pickNext(Minecraft mc) {
        BlockPos here = PlayerUtils.currentBlockPos();
        if (here == null) return false;

        List<BlockPos> gaps = scanGaps(mc.level);
        // **还没打掉的含水方块也要算**：它们只有站在够得着的地方才能打，
        // 光按「能放几个洞」挑落脚点的话，离得远的含水方块（海底的 kelp 之类）会一直没人管，
        // 留在那儿就是一条水路，挖到那层就冒水。
        List<BlockPos> water = unminedWaterlogged(mc.level);
        if (gaps.isEmpty() && water.isEmpty()) return false;
        if (++stops > MAX_STOPS) {
            ChatUtils.error("补粘液块挪了 " + MAX_STOPS + " 次还是没弄完，先停下（还剩 "
                + gaps.size() + " 个洞、" + water.size() + " 个含水方块）");
            return false;
        }

        List<Stand> candidates = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos stand = ctx.routePoint(walkLayer, x, z);
                if (stand.equals(here)) continue;
                int cover = 0;
                for (BlockPos gap : gaps) {
                    if (SingleStageCtx.canPlaceFrom(stand, gap)) cover += HOLE_WEIGHT;
                }
                for (BlockPos pos : water) {
                    if (canMineFrom(stand, pos)) cover++;
                }
                if (cover == 0) continue;
                candidates.add(new Stand(stand, cover, here.distSqr(stand)));
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
            + "（覆盖分 " + best.cover() + "；还剩 " + gaps.size() + " 个洞、"
            + water.size() + " 个含水方块）");
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
    private List<BlockPos> holes(@Nullable BlockPos stand, Level level) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : wrap) {
            if (!isHole(pos, level.getBlockState(pos))) continue;
            if (stand != null && !SingleStageCtx.canPlaceFrom(stand, pos)) continue;
            result.add(pos);
        }
        return result;
    }

    /**
     * 这一格现在算不算「洞」：**壳里**能替换的都算，**区域里**只算水和熔岩。
     *
     * <p>（区域里那些空气 / 草 / 藤蔓不填 —— 反正马上要挖掉。）
     */
    private boolean isHole(BlockPos pos, BlockState state) {
        if (shellKeys.contains(pos.asLong())) return isGap(state);
        return isFluid(state);
    }

    /** 全量目标区域里现在还有哪些洞（不看距离）。 */
    private List<BlockPos> scanGaps(Level level) {
        return holes(null, level);
    }

    /**
     * 站在 {@code stand} 够得着 {@code pos} 吗（按触及距离算，和 {@link PlayerUtils#isWithinReach} 一致）。
     */
    private static boolean canMineFrom(BlockPos stand, BlockPos pos) {
        double reach = PlayerUtils.reach();
        double dx = pos.getX() + 0.5 - (stand.getX() + 0.5);
        double dy = pos.getY() + 0.5 - (stand.getY() + PlayerUtils.EYE_HEIGHT);
        double dz = pos.getZ() + 0.5 - (stand.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** 全量目标区域里**还没打掉**的含水方块（不管够不够得着）。 */
    private List<BlockPos> unminedWaterlogged(Level level) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : wrap) {
            if (isWaterlogged(level.getBlockState(pos))) result.add(pos);
        }
        return result;
    }

    /** 够得着的含水方块（同样只看全量目标区域）。 */
    private List<BlockPos> reachableWaterlogged(Level level) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : wrap) {
            if (!isWaterlogged(level.getBlockState(pos))) continue;
            if (!PlayerUtils.isWithinReach(pos)) continue;
            result.add(pos);
        }
        return result;
    }

    private int countWaterlogged(Level level) {
        int total = 0;
        for (BlockPos pos : wrap) {
            if (isWaterlogged(level.getBlockState(pos))) total++;
        }
        return total;
    }

    // ------------------------------------------------------------------

    /**
     * 这个方块是不是「含水方块」（要当成含水方块挖掉的那种）。
     *
     * <p>除了带 {@code waterlogged=true} 的，还**硬编码**了海草和 kelp：
     * 它们没有 waterlogged 这个状态，但一定泡在水里 —— 不挖掉的话水会顺着它们漏进区域。
     */
    static boolean isWaterlogged(BlockState state) {
        if (state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
            || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** 这个方块是不是水 / 熔岩（目标区域里只替换这两种）。 */
    static boolean isFluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    /**
     * 这个方块是不是「漏洞」：凡是**能被粘液块直接替换掉**的都算。
     *
     * <p>用原版的 {@code replaceable} 判定（{@code BlockState#isReplaceable}）：
     * 空气、水、熔岩、藤蔓、草、雪片……全都在里面。
     * 只用在「边缘目标区域」（壳）上 —— 目标区域里只替换水和熔岩，见 {@link #isFluid}。
     */
    static boolean isGap(BlockState state) {
        return state.canBeReplaced();
    }

    /** 物品栏里还有多少个粘液块 / 树脂块。 */
    private int slimeCount() {
        LocalPlayer player = PlayerUtils.player();
        if (player == null) return 0;
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
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

    // ------------------------------------------------------------------
    // 盒子空了：把放着的那盒换成背包里的备用盒
    // ------------------------------------------------------------------

    /**
     * 这一轮没拿满 6 组（盒子里的不够了）。
     *
     * <p>判据很土：拿完之后背包 22~27 还有空格 → 说明没拿够。
     */
    private boolean shortAfterTake() {
        for (int slot = SingleStageCtx.FILLER_START; slot <= SingleStageCtx.FILLER_END; slot++) {
            if (ctx.live(slot).isEmpty()) return true;
        }
        return false;
    }

    /** 快捷栏 6~8 里还有东西 → 先扔垃圾腾地方（换盒子要用一格空的快捷栏）。 */
    private void buildJunkPlan() {
        junkQueue = new SingleStageQueue();

        List<Integer> junk = new ArrayList<>();
        for (int slot = SingleStageCtx.HOTBAR_FREE_1; slot <= SingleStageCtx.HOTBAR_FREE_3; slot++) {
            if (!ctx.live(slot).isEmpty()) junk.add(slot);
        }
        if (junk.isEmpty()) return;

        ChatUtils.info(phase() + "：快捷栏 6~8 被占着，先去把垃圾扔了");
        BlockPos high = ctx.initialMin.offset(6, 14, 6);
        BlockPos drop = ctx.initialMin.offset(4, 14, 4);

        junkQueue.child("snap 到 6 14 6", () -> new SingleStageSnapAction(ctx, high));
        junkQueue.child("snap 到 4 14 4", () -> new SingleStageSnapAction(ctx, drop));
        junkQueue.child("低头扔掉", () -> new SingleStageThrowAction(ctx, junk, "快捷栏 6~8 的东西"));
        junkQueue.child("snap 回 6 14 6", () -> new SingleStageSnapAction(ctx, high));
        junkQueue.child("snap 回临时站立点",
            () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
    }

    /** 背包 9 / 10 / 11 里内容最多的那盒粘液块；都空就返回 -1。 */
    private int fullestSlimeBoxSlot() {
        int best = -1;
        int bestItems = 0;
        for (int slot : new int[]{SingleStageCtx.SLIME_BOX_A, SingleStageCtx.SLIME_BOX_B,
            SingleStageCtx.SLIME_BOX_FULL}) {
            int items = 0;
            for (ItemStack stack : ShulkerUtils.contents(ctx.live(slot))) items += stack.getCount();
            if (items > bestItems) {
                bestItems = items;
                best = slot;
            }
        }
        return best;
    }

    private int firstFreeHotbar() {
        for (int slot = SingleStageCtx.HOTBAR_FREE_1; slot <= SingleStageCtx.HOTBAR_FREE_3; slot++) {
            if (ctx.live(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** 换盒子的那一串：挖掉最空的那盒 → 吸进来 → 把背包里最多的那盒放上去 → 挖下来的装回背包。 */
    private void buildSwapPlan() {
        swapQueue = new SingleStageQueue();
        swapSpot = boxesLeastFull().get(0);
        swapContents = new ArrayList<>(ctx.data.box(swapSpot));
        swapBefore = ctx.boxSignatures();
        swapReserve = fullestSlimeBoxSlot();

        ChatUtils.info(phase() + "：盒子里的粘液块不够了，把 " + swapSpot.toShortString()
            + " 换成背包第 " + swapReserve + " 格那盒");

        swapQueue.child("挖掉 " + swapSpot.toShortString(),
            () -> new MineBlockAction(swapSpot, SingleStageCtx.HOTBAR_PICKAXE));
        swapQueue.child("snap 到 " + swapSpot.toShortString(),
            () -> new SingleStageSnapAction(ctx, swapSpot));
        swapQueue.child("等物品栏吸到盒子",
            () -> new SingleStageWaitItemAction(ctx, swapContents, swapBefore, true));
        swapQueue.child("snap 回临时站立点",
            () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
        swapQueue.sync("换上新盒子", this::queuePlaceReserve);
    }

    private void queuePlaceReserve() {
        int found = ctx.findPickedBoxStrict(swapBefore, swapContents);
        if (found < 0) {
            fail("找不到刚挖下来的粘液块盒（" + swapSpot.toShortString() + "）");
            swapQueue.cancel();
            return;
        }
        if (swapReserve < 0) {
            fail("背包里没有能顶上的粘液块盒了");
            swapQueue.cancel();
            return;
        }
        int hotbar = firstFreeHotbar();
        if (hotbar < 0) {
            fail("快捷栏 6~8 都占着，没地方放备用盒");
            swapQueue.cancel();
            return;
        }

        List<ItemStack> newContents = SingleStageData.contentsOf(ctx.live(swapReserve));
        swapQueue.child("把备用盒挪到快捷栏第 " + (hotbar + 1) + " 格",
            () -> new SingleStageInventoryAction(ctx,
                List.of(SingleStageInventoryAction.move(swapReserve, hotbar))));
        swapQueue.child("放到 " + swapSpot.toShortString(),
            () -> new SingleStagePlaceAction(ctx, swapSpot, hotbar));
        swapQueue.sync("记缓存", () -> ctx.data.setBox(swapSpot, newContents));
        swapQueue.child("把挖下来的盒子挪到背包第 " + swapReserve + " 格",
            () -> new SingleStageInventoryAction(ctx,
                List.of(SingleStageInventoryAction.move(found, swapReserve))));
    }

    /** 挖这个方块该用快捷栏第几格（剑镐斧铲锄，下标 0~4）。 */
    static int toolSlotFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return SingleStageCtx.HOTBAR_PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return SingleStageCtx.HOTBAR_AXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return SingleStageCtx.HOTBAR_SHOVEL;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return SingleStageCtx.HOTBAR_HOE;
        return SingleStageCtx.HOTBAR_PICKAXE;
    }
}
