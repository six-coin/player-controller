package org.six_coin.playerController.client.mine;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.mixin.ClientPlayerInteractionManagerAccessor;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 挖掘：把 {@code /pc mine simple} 选中的区域里的方块挖掉。
 *
 * <p>做法和 meteor 的 nuker 一样：每 tick 扫一遍自己身边区域里的方块，最近的先挖，
 * 每个方块发一对 START / STOP_DESTROY_BLOCK（packet mine）。服务端收到 STOP 就直接
 * {@code tryBreakBlock}，所以不受挖掘速度限制，一个 tick 能挖很多个。
 *
 * <p>工具：方块要什么工具从原版那四张 {@code mineable} 方块标签推出来（见 {@link MineTools}，
 * 结果会缓存）。每 tick 先按「最近的这个方块」把快捷栏切到对应的工具，然后只挖
 * **手上这件工具挖得动**的方块（only-suitable-tools）—— 不匹配的就跳过。
 *
 * <p>和攻击的关系：切快捷栏、转头都走 {@link HotbarManager} / {@link RotateManager}，
 * {@link Owner#MINE} 的优先级比攻击低：攻击正在打的时候不抢快捷栏、也不抢朝向
 * （这一 tick 就先不挖，等攻击打完再继续）。
 *
 * <p>一直挖到 {@code /pc mine cancel} 为止（nuker 是常驻的，附近没方块了也只是停着）。
 * 备货开始前会先把挖掘任务取消掉（见 {@code StockTask}）。
 */
public final class MineManager {

    private static final MineManager INSTANCE = new MineManager();

    /** 现在是什么情况（{@code /pc mine} 会显示）。 */
    public enum State {

        IDLE("没在挖（没有任务）"),
        STARTING("准备中"),
        NO_WORLD("玩家或世界不存在"),
        NOT_PLAYING("玩家已死亡 / 旁观"),
        WRONG_DIMENSION("不在任务指定的维度"),
        NO_BLOCKS("附近没有可挖的方块"),
        NO_SUITABLE_TOOL("最近的方块要的工具，快捷栏里没有"),
        HOTBAR_DENIED("快捷栏被别的功能占着，这次没切工具"),
        ROTATE_DENIED("朝向被别的功能占着，这次没挖"),
        MINING("正在挖");

        private final String text;

        State(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** 现在的任务，没有就是 null。 */
    @Nullable
    private MineRegion region;

    private State state = State.IDLE;

    /** 这一 tick 挑中的最近方块。 */
    @Nullable
    private BlockPos target;

    /** 那个方块要用的工具（null = 什么工具都行）。 */
    @Nullable
    private MineTools.Kind targetTool;

    /** 这一 tick 挖了几个。 */
    private int lastTickCount;

    /** 快捷栏 / 朝向现在是不是我们占着（还的时候用）。 */
    private boolean hotbarHeld;
    private boolean rotateHeld;

    private MineManager() {
    }

    public static MineManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    @Nullable
    public MineRegion region() {
        return region;
    }

    public State state() {
        return state;
    }

    @Nullable
    public BlockPos target() {
        return target;
    }

    @Nullable
    public MineTools.Kind targetTool() {
        return targetTool;
    }

    public int lastTickCount() {
        return lastTickCount;
    }

    // ------------------------------------------------------------------
    // 开始 / 取消
    // ------------------------------------------------------------------

    /** 开一个新任务（原来有任务的话直接换掉）。 */
    public void start(MineRegion region) {
        release();
        this.region = region;
        this.state = State.STARTING;
        this.target = null;
        this.targetTool = null;
        this.lastTickCount = 0;
        ChatUtils.debug("开始挖掘任务：" + region.describe());
    }

    /** 取消所有挖掘任务（simple / complex）。有没有任务被取消掉。 */
    public boolean cancel() {
        if (region == null) return false;

        ChatUtils.debug("取消挖掘任务：" + region.describe());
        region = null;
        state = State.IDLE;
        target = null;
        targetTool = null;
        lastTickCount = 0;
        release();
        return true;
    }

    /** 进世界 / 断线的时候清一下（不发任何包）。 */
    public void reset() {
        region = null;
        state = State.IDLE;
        target = null;
        targetTool = null;
        lastTickCount = 0;
        MineTools.clearCache();
        release();
    }

    /** 把快捷栏 / 朝向还回去（不是我们占着的时候什么都不做）。 */
    private void release() {
        if (hotbarHeld) {
            HotbarManager.get().release(Owner.MINE);
            hotbarHeld = false;
        }
        if (rotateHeld) {
            RotateManager.get().release(Owner.MINE);
            rotateHeld = false;
        }
    }

    // ------------------------------------------------------------------
    // 每 tick
    // ------------------------------------------------------------------

    public void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        if (region == null) {
            release();
            setIdle();
            return;
        }

        if (player == null || mc.world == null || mc.interactionManager == null) {
            clearTarget();
            release();
            setState(State.NO_WORLD);
            return;
        }

        if (!player.isAlive() || player.isSpectator()) {
            clearTarget();
            release();
            setState(State.NOT_PLAYING);
            return;
        }

        if (!region.dimension().equals(DimensionUtils.current())) {
            clearTarget();
            release();
            setState(State.WRONG_DIMENSION);
            return;
        }

        List<BlockPos> candidates = collect(mc, player);
        if (candidates.isEmpty()) {
            clearTarget();
            release();
            setState(State.NO_BLOCKS);
            return;
        }

        // 最近的这个方块决定用哪件工具
        BlockPos nearest = candidates.get(0);
        target = nearest;
        targetTool = MineTools.required(mc.world.getBlockState(nearest));

        MineTools.Kind held = heldTool(player);
        if (targetTool != null && targetTool != held) {
            int slot = MineTools.hotbarSlot(player.getInventory(), targetTool);
            if (slot < 0) {
                // 快捷栏里没有这件工具：只能挖手上这件工具挖得动的
                setState(State.NO_SUITABLE_TOOL);
            } else if (!HotbarManager.get().request(Owner.MINE, slot)) {
                lastTickCount = 0;
                setState(State.HOTBAR_DENIED);
                return;
            } else {
                hotbarHeld = true;
                held = targetTool;
            }
        }

        int limit = PlayerControllerConfig.getMineSimpleMaxBlocksPerTick();
        int mined = 0;
        for (BlockPos pos : candidates) {
            if (mined >= limit) break;

            // 挖着挖着工具坏了：这一 tick 就停，下一 tick 重新找工具
            if (mined > 0 && heldTool(player) != held) break;

            MineTools.Kind need = MineTools.required(mc.world.getBlockState(pos));
            if (need != null && need != held) continue; // 手上这件工具挖不动，跳过

            if (!RotateManager.get().rotateTo(Owner.MINE, pos)) {
                lastTickCount = mined;
                setState(State.ROTATE_DENIED);
                return;
            }
            rotateHeld = true;

            mine(mc, player, pos);
            mined++;
        }

        lastTickCount = mined;
        if (mined > 0) {
            setState(State.MINING);
        } else if (state != State.NO_SUITABLE_TOOL) {
            setState(State.NO_BLOCKS);
        }
    }

    /** 现在手上这件东西算哪一类工具。 */
    @Nullable
    private static MineTools.Kind heldTool(ClientPlayerEntity player) {
        return MineTools.held(player.getInventory().getSelectedStack());
    }

    private void clearTarget() {
        target = null;
        targetTool = null;
        lastTickCount = 0;
    }

    private void setIdle() {
        target = null;
        targetTool = null;
        lastTickCount = 0;
        setState(State.IDLE);
    }

    /** 状态变了才打一行调试日志（每 tick 都打就太吵了）。 */
    private void setState(State next) {
        if (state == next) return;
        ChatUtils.debug("挖掘：" + state.text() + " → " + next.text());
        state = next;
    }

    // ------------------------------------------------------------------
    // 找方块
    // ------------------------------------------------------------------

    /** 周边区域里这一 tick 能挖的方块，按离眼睛的距离从近到远排。 */
    private List<BlockPos> collect(MinecraftClient mc, ClientPlayerEntity player) {
        double range = PlayerControllerConfig.getMineSimpleRange();
        double wallRange = PlayerControllerConfig.getMineSimpleWallRange();
        int radius = (int) Math.ceil(Math.max(range, wallRange)) + 1;

        BlockPos center = BlockPos.ofFloored(player.getEntityPos());
        Vec3d eye = player.getEyePos();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        List<BlockPos> found = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!region.contains(mutable)) continue;

                    BlockState state = mc.world.getBlockState(mutable);
                    if (!breakable(mc.world, mutable, state)) continue;
                    if (!region.filter().allows(state.getBlock())) continue;
                    if (!inRange(mc, player, eye, mutable, range, wallRange)) continue;

                    found.add(mutable.toImmutable());
                }
            }
        }

        found.sort(Comparator.comparingDouble(pos -> eye.squaredDistanceTo(Vec3d.ofCenter(pos))));
        return found;
    }

    /** 不是空气、不是水 / 岩浆、也不是基岩那种挖不动的。 */
    private static boolean breakable(ClientWorld world, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.getBlock() instanceof FluidBlock) return false;
        return state.getHardness(world, pos) >= 0.0F;
    }

    /**
     * 这个方块够不够得着。
     *
     * <p>看得见的按 {@code mine_simple_range}，被别的方块挡住的按 {@code mine_simple_wall_range}；
     * 两个范围里小的那个先判，用不着每次射线。
     */
    private static boolean inRange(MinecraftClient mc, ClientPlayerEntity player, Vec3d eye,
                                   BlockPos pos, double range, double wallRange) {
        Vec3d center = Vec3d.ofCenter(pos);
        double distanceSq = eye.squaredDistanceTo(center);

        double small = Math.min(range, wallRange);
        if (distanceSq <= small * small) return true;

        double large = Math.max(range, wallRange);
        if (distanceSq > large * large) return false;

        double limit = canSee(mc, player, eye, center, pos) ? range : wallRange;
        return distanceSq <= limit * limit;
    }

    /** 眼睛到方块中心这条线有没有被别的方块挡住（穿过去的也算看得见）。 */
    private static boolean canSee(MinecraftClient mc, ClientPlayerEntity player, Vec3d eye,
                                  Vec3d center, BlockPos pos) {
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, center,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) return true;
        return hit.getBlockPos().equals(pos);
    }

    // ------------------------------------------------------------------
    // 挖
    // ------------------------------------------------------------------

    /**
     * 发包把一个方块打掉：START → 挥手 → STOP。
     *
     * <p>用原版的 {@code sendSequencedPacket} 带 sequence 号（见
     * {@link ClientPlayerInteractionManagerAccessor}）。
     */
    private static void mine(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        Direction side = PlayerUtils.facingSide(pos);
        ClientWorld world = mc.world;
        ClientPlayerInteractionManagerAccessor accessor =
            (ClientPlayerInteractionManagerAccessor) mc.interactionManager;

        accessor.playerController$sendSequencedPacket(world, sequence ->
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side, sequence));
        player.swingHand(Hand.MAIN_HAND);
        accessor.playerController$sendSequencedPacket(world, sequence ->
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side, sequence));
    }
}
