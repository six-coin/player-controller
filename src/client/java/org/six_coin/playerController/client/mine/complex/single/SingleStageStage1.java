package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 第 (5) 步的**第一步**：挖含水方块 + 给「壳」补粘液块。
 *
 * <p>流程：
 * <ol>
 *   <li>先看有没有活干：全量目标区域里有没有 {@code waterlogged=true} 的方块、
 *       边缘目标区域（那一圈壳）里有没有水 / 熔岩 / 空气。都没有就整个跳过（连粘液块都不拿）；</li>
 *   <li>从两个粘液块盒子里拿 9 组（优先拿不满的那个盒子，不够再拿另一个）——
 *       9 组正好填满快捷栏 6~8 + 背包 22~27；</li>
 *   <li>在目标区域上面那一层按路线一格一格走，每格把触及范围内的含水方块挖掉、
 *       把壳里的水 / 熔岩 / 空气补成粘液块（1 tick 放 1 个）；</li>
 *   <li>走完 snap 回临时站立点，把身上多余的粘液块放回盒子里（优先放回本来就有粘液块的那个）。</li>
 * </ol>
 *
 * <p>挖含水方块用的是 {@link MineBlockAction}（和备货一样的朴素挖法），不是 mine simple。
 */
class SingleStageStage1 extends SingleStageStep {

    /** 从盒子里拿几组粘液块。 */
    private static final int SLIME_STACKS = 9;

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

    private final int iteration;
    private final int walkLayer;
    private final SingleStageQueue queue = new SingleStageQueue();

    private final List<BlockPos> waterlogged = new ArrayList<>();
    private final List<BlockPos> gaps = new ArrayList<>();

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

        for (BlockPos pos : ctx.wrapCells(iteration, mc.world)) {
            if (isWaterlogged(mc.world.getBlockState(pos))) waterlogged.add(pos);
        }
        for (BlockPos pos : ctx.shellCells(iteration, mc.world)) {
            if (isGap(mc.world.getBlockState(pos))) gaps.add(pos);
        }

        if (waterlogged.isEmpty() && gaps.isEmpty()) {
            ChatUtils.debug(phase() + "：没有含水方块也没有漏洞，跳过");
            finish();
            return;
        }

        ChatUtils.info(phase() + "：含水方块 " + waterlogged.size() + " 个、要补的洞 "
            + gaps.size() + " 个");
        buildPlan();
    }

    @Override
    protected void tick() {
        if (isFinished()) return;
        if (queue.tick()) {
            if (queue.failed() != null) {
                fail(queue.failed().failureReason());
            } else {
                finish();
            }
        }
    }

    // ------------------------------------------------------------------

    private void buildPlan() {
        queue.sync("取粘液块", this::queueTakeSlime);

        for (BlockPos cell : ctx.routeCells(walkLayer)) {
            queue.sync("走到 " + cell.toShortString(), () -> {
                queue.child("snap 到 " + cell.toShortString(),
                    () -> new SingleStageSnapAction(ctx, cell));
                queue.sync("处理 " + cell.toShortString(), () -> processCell(cell));
            });
        }

        queue.sync("回临时站立点", () -> queue.child("snap",
            () -> new SingleStageSnapAction(ctx, ctx.standPoint())));
        queue.sync("把剩下的粘液块放回去", this::queuePutBackSlime);
    }

    // ------------------------------------------------------------------
    // 取 / 放粘液块
    // ------------------------------------------------------------------

    private void queueTakeSlime() {
        List<BlockPos> boxes = new ArrayList<>(List.of(ctx.slimeSpotA(), ctx.slimeSpotB()));
        boxes.sort(Comparator.comparingInt(ctx.data::boxItems));   // 优先拿不满的那个

        List<SingleStageBoxGetAction.Want> wants = emptySlots();
        if (wants.isEmpty()) return;

        BlockPos first = boxes.get(0);
        queue.child("从 " + first.toShortString() + " 取粘液块",
            () -> new SingleStageBoxGetAction(ctx, first, SLIME, wants, "粘液块"));

        BlockPos second = boxes.get(1);
        queue.sync("不够再拿满的", () -> {
            List<SingleStageBoxGetAction.Want> rest = emptySlots();
            if (rest.isEmpty()) return;
            queue.child("从 " + second.toShortString() + " 取粘液块",
                () -> new SingleStageBoxGetAction(ctx, second, SLIME, rest, "粘液块"));
        });
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

    private void queuePutBackSlime() {
        List<BlockPos> boxes = new ArrayList<>(List.of(ctx.slimeSpotA(), ctx.slimeSpotB()));
        boxes.sort(Comparator.comparingInt(ctx.data::boxItems).reversed());   // 先放回有料的那个
        for (BlockPos box : boxes) {
            queue.child("把多余的粘液块放回 " + box.toShortString(),
                () -> new SingleStageBoxPutAction(ctx, box, SLIME, "粘液块"));
        }
    }

    // ------------------------------------------------------------------
    // 每格的处理
    // ------------------------------------------------------------------

    private void processCell(BlockPos cell) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }

        int mined = 0;
        for (BlockPos pos : waterlogged) {
            BlockState state = mc.world.getBlockState(pos);
            if (!isWaterlogged(state)) continue;
            if (!PlayerUtils.isWithinReach(pos)) continue;
            int slot = toolSlotFor(state);
            queue.child("挖含水方块 " + pos.toShortString(), () -> new MineBlockAction(pos, slot));
            mined++;
        }

        List<BlockPos> fill = new ArrayList<>();
        for (BlockPos pos : gaps) {
            if (!isGap(mc.world.getBlockState(pos))) continue;
            // 放方块按 7×7×7 的正方体算，不按 actions.reach（见 SingleStageCtx#canPlaceAt）
            if (!ctx.canPlaceAt(pos)) continue;
            fill.add(pos);
        }
        if (!fill.isEmpty()) {
            queue.child("补粘液块 " + fill.size() + " 个",
                () -> new SingleStagePlaceBatchAction(ctx, fill, SLIME, HOTBAR_SLOTS, MAIN_SLOTS, "粘液块"));
        }

        if (mined + fill.size() > 0) {
            ChatUtils.debug("在 " + cell.toShortString() + " 处理：挖 " + mined + "、补 " + fill.size());
        }
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
