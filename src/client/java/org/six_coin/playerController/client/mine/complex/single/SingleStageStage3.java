package org.six_coin.playerController.client.mine.complex.single;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.stock.MineBlockAction;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.ShulkerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 第 (5) 步的**第三步**：捡掉落物 + 卸货。
 *
 * <p>循环：
 * <ol>
 *   <li>这一层没有（要收集的）掉落物了 → 卸一次货，**这一轮就结束**（进第 (6) 步）；</li>
 *   <li>物品栏满了 → 卸货，继续捡；</li>
 *   <li>否则 snap 到最近的那个掉落物上（走过去就会被自动捡起来）。</li>
 * </ol>
 *
 * <p>卸货（{@link #queueUnload}）：
 * <ol>
 *   <li>snap 到 1 14，再 snap 回临时站立点；</li>
 *   <li>圆石 → 圆石盒、黏液块 → 黏液盒、要收集的东西 → 收集盒（都是「先装有的，再装没的」）；</li>
 *   <li>「理应是空位」的格子里还有东西的话，飞到 6 14 6 → 4 14 4、低头全扔掉，再飞回来
 *       （低头用 {@link SingleStageThrowAction}，只发假转头包，客户端视角不动）；</li>
 *   <li>如果有个收集盒满了：挖掉它、把盒子收回对应格、把「收集位 + 2」的备用盒放到挖掉的位置，
 *       收集位 +1。</li>
 * </ol>
 */
class SingleStageStage3 extends SingleStageStep {

    /** 同一个掉落物连着这么多次都捡不到就先跳过（免得死循环）。 */
    private static final int MAX_REPEATS = 5;

    /** 保险丝：一次卸货算一轮，太多轮说明卡住了。 */
    private static final int MAX_UNLOADS = 200;

    private final int iteration;
    private final int bottom;
    private final int top;
    private final SingleStageQueue queue = new SingleStageQueue();

    /** 捡不到的掉落物（按实体 id）。 */
    private final Set<Integer> ignored = new HashSet<>();

    private int lastTarget = -1;
    private int repeats;
    private int unloads;

    /** 这一轮卸货里装满的那个收集盒（没有就是 null）。 */
    @Nullable
    private BlockPos fullBox;

    SingleStageStage3(SingleStageCtx ctx, int iteration) {
        super(ctx);
        this.iteration = iteration;
        this.bottom = ctx.regionBottom(iteration);
        this.top = ctx.regionTop(iteration);
    }

    @Override
    protected String phase() {
        return "第 " + (iteration + 1) + "/" + SingleStageCtx.ITERATIONS + " 层：捡掉落物";
    }

    @Override
    protected void onStart() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            fail("玩家或世界不存在");
            return;
        }
        queue.sync("开始收集", this::collectStep);
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
    // 收集
    // ------------------------------------------------------------------

    private void collectStep() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            queue.cancel();
            return;
        }

        List<ItemEntity> drops = findDrops(mc);
        if (drops.isEmpty()) {
            ChatUtils.debug(phase() + "：这一层没有掉落物了，卸货后结束");
            queueUnload();
            queue.sync("结束收集", this::finish);
            return;
        }

        if (inventoryFull()) {
            ChatUtils.info(phase() + "：物品栏满了，先卸货");
            queueUnload();
            // 卸完货人在临时站立点（初始子区块那边），直接找掉落物会「不可达」：
            // 先回到捡东西那一层的 1 1（进矿洞的那一头），再从那里出发
            queue.child("回到捡东西那层的 1 1",
                () -> new SingleStageSnapAction(ctx, ctx.routePoint(bottom, 1, 1)));
            queue.sync("继续收集", this::collectStep);
            return;
        }

        ItemEntity nearest = nearest(player, drops);
        int id = nearest.getId();

        if (id == lastTarget) {
            if (++repeats >= MAX_REPEATS) {
                ignored.add(id);
                repeats = 0;
                lastTarget = -1;
                ChatUtils.debug("捡不到掉落物 #" + id + "（"
                    + nearest.getBlockPos().toShortString() + "），先跳过它");
                queue.sync("继续收集", this::collectStep);
                return;
            }
        } else {
            lastTarget = id;
            repeats = 0;
        }

        queue.child("去捡 " + nearest.getBlockPos().toShortString(),
            () -> new SingleStagePickupAction(ctx, id));
        queue.sync("继续收集", this::collectStep);
    }

    /**
     * 这一层里「要收集的」掉落物。
     *
     * <p>要收集的 = {@code item_collector} 里的东西 <b>+ 圆石 + 黏液块 / 树脂块</b>：
     * 后两种卸货的时候是要装盒的，以前只认 item_collector，圆石掉在地上就永远不去捡了。
     * 其它东西（泥土、砂砾之类）扔在地上不管，捡了也只能扔掉。
     */
    private List<ItemEntity> findDrops(MinecraftClient mc) {
        List<ItemEntity> drops = new ArrayList<>();
        int minX = ctx.targetMin.getX();
        int minZ = ctx.targetMin.getZ();
        // 稍微放宽一圈：掉落物会滚 / 弹到边缘外一点点
        Box area = new Box(minX - 2, bottom - 2, minZ - 2,
            minX + 18, top + 3, minZ + 18);

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity item)) continue;
            ItemStack stack = item.getStack();
            if (stack.isEmpty()) continue;
            if (!wanted(stack)) continue;
            if (!area.contains(item.getEntityPos())) continue;
            if (ignored.contains(item.getId())) continue;
            drops.add(item);
        }
        return drops;
    }

    /** 两个圆石盒里的圆石加起来到这个数，地上的圆石就不捡了。 */
    private static final int COBBLE_ENOUGH = 3200;

    /** 这种掉落物要不要捡。 */
    private boolean wanted(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if (ctx.collectorItems.contains(id)) return true;
        if ("minecraft:cobblestone".equals(id)) return !cobbleEnough();
        return "minecraft:slime_block".equals(id) || "minecraft:resin_block".equals(id);
    }

    /**
     * 两个圆石盒里的圆石是不是已经够多了。
     *
     * <p>挖这个子区块会掉一大堆圆石，两个盒子早晚装满 —— 装满以后再追着捡纯属浪费时间，
     * 捡了也只能扔掉。够了就只捡 item_collector 和粘液块。
     */
    private boolean cobbleEnough() {
        int total = ctx.data.boxCount(ctx.cobbleSpotA(), Items.COBBLESTONE)
            + ctx.data.boxCount(ctx.cobbleSpotB(), Items.COBBLESTONE);
        return total >= COBBLE_ENOUGH;
    }

    private static ItemEntity nearest(ClientPlayerEntity player, List<ItemEntity> drops) {
        Vec3d here = player.getEntityPos();
        ItemEntity best = drops.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : drops) {
            double distance = here.squaredDistanceTo(drop.getEntityPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = drop;
            }
        }
        return best;
    }

    /** 「理应是空位」的格子里一个空位都没有了。 */
    private boolean inventoryFull() {
        ctx.refresh();
        for (int slot : ctx.expectedEmptySlots()) {
            if (ctx.data.isEmpty(slot)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 卸货
    // ------------------------------------------------------------------

    private void queueUnload() {
        if (++unloads > MAX_UNLOADS) {
            fail("卸货卸了 " + unloads + " 次还是没结束，停下来看看");
            queue.cancel();
            return;
        }
        fullBox = null;

        queue.sync("卸货：回临时站立点", () -> {
            queue.child("snap 到 1 14",
                () -> new SingleStageSnapAction(ctx, ctx.routePoint(bottom, 1, 14)));
            queue.child("snap 到临时站立点",
                () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
        });

        queue.sync("装圆石", () -> {
            ctx.refresh();
            if (ctx.data.count(Items.COBBLESTONE) == 0) return;
            for (BlockPos box : fullerFirst(ctx.cobbleSpotA(), ctx.cobbleSpotB())) {
                queue.child("把圆石装进 " + box.toShortString(),
                    () -> new SingleStageBoxPutAction(ctx, box, COBBLE, "圆石"));
            }
        });

        queue.sync("装粘液块", () -> {
            ctx.refresh();
            if (ctx.data.count(slimeIds()) == 0) return;
            for (BlockPos box : fullerFirst(ctx.slimeSpotA(), ctx.slimeSpotB())) {
                queue.child("把粘液块装进 " + box.toShortString(),
                    () -> new SingleStageBoxPutAction(ctx, box, SingleStageStage1.SLIME, "粘液块"));
            }
        });

        queue.sync("装收集品", () -> {
            ctx.refresh();
            if (ctx.data.count(ctx.collectorItems) == 0) return;
            for (BlockPos box : List.of(ctx.collectSpotA(), ctx.collectSpotB())) {
                queue.child("把收集品装进 " + box.toShortString(),
                    () -> new SingleStageBoxPutAction(ctx, box, collectorFilter(), "收集品"),
                    action -> {
                        if (!((SingleStageBoxPutAction) action).boxFull()) return;
                        if (fullBox == null) {
                            fullBox = box;
                        } else {
                            ChatUtils.error("两个收集盒都满了，这一轮只换一个");
                        }
                    });
            }
        });

        queue.sync("扔垃圾", this::queueThrowJunk);
        queue.sync("换收集盒", this::queueSwapFullBox);
    }

    private static Set<String> slimeIds() {
        return Set.of("minecraft:slime_block", "minecraft:resin_block");
    }

    private static final Predicate<ItemStack> COBBLE = stack -> stack.isOf(Items.COBBLESTONE);

    private Predicate<ItemStack> collectorFilter() {
        return stack -> ctx.collectorItems.contains(Registries.ITEM.getId(stack.getItem()).toString());
    }

    /** 内容多的排前面（「先装有的，再装没的」）。 */
    private List<BlockPos> fullerFirst(BlockPos a, BlockPos b) {
        List<BlockPos> boxes = new ArrayList<>(List.of(a, b));
        boxes.sort(Comparator.comparingInt(ctx.data::boxItems).reversed());
        return boxes;
    }

    // ------------------------------------------------------------------
    // 扔垃圾
    // ------------------------------------------------------------------

    private void queueThrowJunk() {
        queue.sync("扔垃圾", () -> {
            ctx.refresh();
            List<Integer> junk = new ArrayList<>();
            for (int slot : ctx.expectedEmptySlots()) {
                if (!ctx.data.isEmpty(slot)) junk.add(slot);
            }
            if (junk.isEmpty()) return;

            ChatUtils.info("理应是空位的 " + junk.size() + " 格里有东西，去扔掉");
            BlockPos high = ctx.initialMin.add(6, 14, 6);
            BlockPos drop = ctx.initialMin.add(4, 14, 4);

            queue.child("snap 到 6 14 6", () -> new SingleStageSnapAction(ctx, high));
            queue.child("snap 到 4 14 4", () -> new SingleStageSnapAction(ctx, drop));
            // 低头扔（转头只发假包，客户端视角不动）；扔的方向看的是服务端记着的 pitch，
            // 所以用统一的 ThrowAction：它会每 tick 重发一次低头，并在失败时明确报错
            queue.child("低头扔掉这些垃圾",
                () -> new SingleStageThrowAction(ctx, junk, "垃圾"));
            queue.child("snap 回 6 14 6", () -> new SingleStageSnapAction(ctx, high));
            queue.child("snap 回临时站立点", () -> new SingleStageSnapAction(ctx, ctx.standPoint()));
        });
    }

    // ------------------------------------------------------------------
    // 换掉装满的收集盒
    // ------------------------------------------------------------------

    private void queueSwapFullBox() {
        queue.sync("换收集盒", () -> {
            BlockPos broken = fullBox;
            if (broken == null) return;
            fullBox = null;

            List<ItemStack> contents = ctx.data.box(broken);
            Map<Integer, String> before = ctx.boxSignatures();
            int slot = broken.equals(ctx.collectSpotB()) ? ctx.collectSlot + 1 : ctx.collectSlot;

            queue.child("挖掉装满的收集盒 " + broken.toShortString(),
                () -> new MineBlockAction(broken, SingleStageCtx.HOTBAR_PICKAXE));
            queue.child("snap 到 " + broken.toShortString(),
                () -> new SingleStageSnapAction(ctx, broken));
            queue.child("等物品栏吸到盒子",
                () -> new SingleStageWaitItemAction(ctx, contents, before));
            queue.child("snap 回临时站立点",
                () -> new SingleStageSnapAction(ctx, ctx.standPoint()));

            queue.sync("收好挖下来的盒子", () -> {
                int found = ctx.findPickedBox(before, contents);
                if (found < 0) {
                    fail("找不到刚挖下来的收集盒（应该是一个满盒）");
                    queue.cancel();
                    return;
                }
                queue.child("把盒子挪到物品栏第 " + slot + " 格",
                    () -> new SingleStageInventoryAction(ctx,
                        List.of(SingleStageInventoryAction.move(found, slot))));
            });

            queue.sync("放新的收集盒", () -> {
                int source = ctx.collectSlot + 2;
                ItemStack spare = ctx.live(source);
                if (!ShulkerUtils.isShulkerBox(spare)) {
                    fail("物品栏第 " + source + " 格没有备用的收集盒了（现在放着 "
                        + SingleStageData.idOf(spare) + "）");
                    queue.cancel();
                    return;
                }
                int hotbar = firstFreeHotbar();
                if (hotbar < 0) {
                    fail("快捷栏 6~8 都占着，没地方放新的收集盒");
                    queue.cancel();
                    return;
                }

                List<ItemStack> newContents = SingleStageData.contentsOf(spare);
                queue.child("把备用盒挪到快捷栏第 " + (hotbar + 1) + " 格",
                    () -> new SingleStageInventoryAction(ctx,
                        List.of(SingleStageInventoryAction.move(source, hotbar))));
                queue.child("放到 " + broken.toShortString(),
                    () -> new SingleStagePlaceAction(ctx, broken, hotbar));
                queue.sync("记缓存", () -> ctx.data.setBox(broken, newContents));
            });

            queue.sync("收集位 +1", () -> ctx.collectSlot++);
        });
    }

    private int firstFreeHotbar() {
        for (int i = SingleStageCtx.HOTBAR_FREE_1; i <= SingleStageCtx.HOTBAR_FREE_3; i++) {
            if (ctx.live(i).isEmpty()) return i;
        }
        return -1;
    }
}
