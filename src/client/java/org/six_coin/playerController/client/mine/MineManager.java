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
 * 结果会缓存）。切工具的规则是**尽量不切**：
 * <ul>
 *   <li>手上这件工具附近还挖得动方块，就一直用它挖（哪怕更近的方块要别的工具）；</li>
 *   <li>只有「附近已经一个都挖不动了」才换，换成最近那个方块要的工具；</li>
 *   <li>换完之后先空 {@code mine_tool_switch_interval_ticks}（默认 4）个 tick 再动手；</li>
 *   <li>发包之前再确认一次：只对**触及范围内**而且**手上这件工具挖得动**的方块发破坏请求。</li>
 * </ul>
 * 切得越少，客户端和服务端越不容易对不上（来回切工具那套会把两边的挖掘状态搅乱）。
 *
 * <p>另外：**水平速度不是 0、或者纵向速度不小于上限**的时候一概暂停
 * （在走、在飞、被推、掉得太快 / 升得太快都不发破坏包），站定了才动手。
 * 纵向速度的上限是 {@code mine_max_vertical_speed_blocks_per_tick}（默认 1 格/tick）。
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

    /**
     * 「速度为 0」的判定余量：**水平**速度平方小于这个数就算不动（约 0.001 格/tick）。
     *
     * <p>站着不动的时候原版算出来就是 (0, 0, 0)，这里留一点余量只是怕浮点残渣。
     * 纵向速度不算：往下掉 / 往上飘的时候照样挖。
     */
    private static final double STILL_EPSILON_SQUARED = 1.0E-6;

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
        SWITCH_WAIT("刚换完工具，等一会儿再挖"),
        MOVING("玩家在动 / 掉得太快，先暂停"),
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

    /** 刚换完工具，还要空几个 tick 才动手（见 {@code mine_tool_switch_interval_ticks}）。 */
    private int switchCooldown;

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
        this.switchCooldown = 0;
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
        switchCooldown = 0;
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
        switchCooldown = 0;
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

        // 刚换完工具：先空几个 tick 再动手，让服务端那边的选中格先稳定下来
        if (switchCooldown > 0) {
            switchCooldown--;
            lastTickCount = 0;
            setState(State.SWITCH_WAIT);
            return;
        }

        // 玩家还在走，或者掉 / 升得太快：一概暂停，一个破坏包都不发
        if (!isStill(player)) {
            lastTickCount = 0;
            setState(State.MOVING);
            return;
        }

        List<BlockPos> candidates = collect(mc, player);
        if (candidates.isEmpty()) {
            clearTarget();
            release();
            setState(State.NO_BLOCKS);
            return;
        }

        double range = PlayerControllerConfig.getMineSimpleRange();
        double wallRange = PlayerControllerConfig.getMineSimpleWallRange();
        Vec3d eye = player.getEyePos();

        MineTools.Kind held = heldTool(player);

        // 手上这件工具附近还挖得动东西吗？挖得动就**不换**：切得越少，服务端那边越不容易对不上
        boolean somethingMineable = false;
        for (BlockPos pos : candidates) {
            if (suitable(mc.world.getBlockState(pos), held)) {
                somethingMineable = true;
                break;
            }
        }

        if (!somethingMineable) {
            // 一个都挖不动了才换：换成最近那个方块要的工具
            BlockPos nearest = candidates.get(0);
            MineTools.Kind desired = MineTools.required(mc.world.getBlockState(nearest));

            if (desired != null && desired != held) {
                int slot = MineTools.hotbarSlot(player.getInventory(), desired);
                if (slot < 0) {
                    target = nearest;
                    targetTool = desired;
                    lastTickCount = 0;
                    setState(State.NO_SUITABLE_TOOL);
                    return;
                }
                if (!HotbarManager.get().request(Owner.MINE, slot)) {
                    target = nearest;
                    targetTool = desired;
                    lastTickCount = 0;
                    setState(State.HOTBAR_DENIED);
                    return;
                }
                hotbarHeld = true;
                held = desired;
                target = nearest;
                targetTool = desired;
                ChatUtils.debug("挖掘：手上这件工具挖不动附近的方块了，换成" + desired.label());

                // 换完工具先等一会儿（换工具这一 tick 也算一 tick）
                int wait = PlayerControllerConfig.getMineToolSwitchIntervalTicks();
                if (wait > 0) {
                    switchCooldown = wait - 1;
                    lastTickCount = 0;
                    setState(State.SWITCH_WAIT);
                    return;
                }
            }
        }

        // 这一 tick 要挖的第一个方块（显示用）
        target = null;
        targetTool = held;
        for (BlockPos pos : candidates) {
            if (suitable(mc.world.getBlockState(pos), held)) {
                target = pos;
                break;
            }
        }
        if (target == null) {
            target = candidates.get(0);
            targetTool = MineTools.required(mc.world.getBlockState(target));
        }

        int limit = PlayerControllerConfig.getMineSimpleMaxBlocksPerTick();
        int mined = 0;
        for (BlockPos pos : candidates) {
            if (mined >= limit) break;

            // 挖着挖着工具坏了：这一 tick 就停，下一 tick 重新判断
            if (mined > 0 && heldTool(player) != held) break;

            BlockState state = mc.world.getBlockState(pos);
            // 只对手上这件工具挖得动的方块发包
            if (!suitable(state, held)) continue;
            // 再怎么也不能超出触及范围
            if (!inRange(mc, player, eye, pos, range, wallRange)) continue;

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
        } else {
            setState(State.NO_BLOCKS);
        }
    }

    /** 手上这件工具（{@code null} = 空手）挖不挖得动这个方块。 */
    private static boolean suitable(BlockState state, @Nullable MineTools.Kind held) {
        MineTools.Kind need = MineTools.required(state);
        return need == null || need == held;
    }

    /**
     * 玩家现在是不是「站定了」：水平速度约等于 0，而且纵向速度小于上限。
     *
     * <p>水平：速度平方小于 {@link #STILL_EPSILON_SQUARED} 就算 0（约 0.001 格/tick）：
     * 站着不动的时候原版算出来就是 (0, 0, 0)，这里留一点余量只是怕浮点残渣。
     *
     * <p>纵向：绝对值要**小于** {@code mine_max_vertical_speed_blocks_per_tick}
     * （默认 1 格/tick）；配成 0 就要求纵向速度正好是 0。
     */
    private static boolean isStill(ClientPlayerEntity player) {
        Vec3d velocity = player.getVelocity();
        if (velocity.horizontalLengthSquared() >= STILL_EPSILON_SQUARED) return false;

        double limit = PlayerControllerConfig.getMineMaxVerticalSpeedPerTick();
        double vertical = Math.abs(velocity.y);
        return limit <= 0.0 ? vertical == 0.0 : vertical < limit;
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
