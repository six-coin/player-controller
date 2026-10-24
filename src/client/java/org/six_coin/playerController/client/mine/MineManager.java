package org.six_coin.playerController.client.mine;

import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.mine.complex.MineComplexData;
import org.six_coin.playerController.client.mine.complex.MineComplexManager;
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
 * <p>另外：**x / z 两个方向的水平速度分别超过上限、或者纵向速度超过上限**的时候一概暂停
 * （在走、在飞、被推、掉得太快 / 升得太快都不发破坏包），站定了才动手。
 * 水平上限是 {@code mine_max_horizontal_speed_blocks_per_tick}（默认 0）、纵向上限是
 * {@code mine_max_vertical_speed_blocks_per_tick}（默认 0.08 格/tick）。
 *
 * <p>**玩家沾水的时候也一概不挖**（见 {@link #touchingWater}）：水里游泳 / 漂浮会让客户端和
 * 服务端的挖掘状态对不上，所以哪怕速度满足条件也先停着，出水再继续。
 * 飞行也拦得住：这里不看原版的 {@code isTouchingWater()} 标志，而是直接查世界里玩家包围盒
 * 覆盖的方块有没有水 —— 那个标志在开飞行之后不可靠（会变成 false，结果在水里飞着照样挖）。
 *
 * <p>和攻击的关系：切快捷栏、转头都走 {@link HotbarManager} / {@link RotateManager}，
 * {@link Owner#MINE} 的优先级比攻击低：攻击正在打的时候不抢快捷栏、也不抢朝向
 * （这一 tick 就先不挖，等攻击打完再继续）。
 *
 * <p>一直挖到 {@code /pc mine cancel} 为止（nuker 是常驻的，附近没方块了也只是停着）。
 * 备货开始前会先把挖掘任务停下来（见 {@code StockTask}，用 {@link #stop()}，缓存留着）。
 *
 * <p>范围会记进 {@code world_<n>/mine/mine.json}，重新进世界之后由 {@link #resumeSaved()}
 * 接回来（{@code /pc mine cancel} 才会把缓存一起清掉）。
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
        SWITCH_WAIT("刚换完工具，等一会儿再挖"),
        MOVING("玩家在动 / 掉得太快，先暂停"),
        IN_WATER("玩家沾水，先暂停"),
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

    /** 开一个新任务（原来有任务的话直接换掉）。范围会顺手记进 mine.json，重进世界还在。 */
    public void start(MineRegion region) {
        release();
        this.region = region;
        this.state = State.STARTING;
        this.target = null;
        this.targetTool = null;
        this.lastTickCount = 0;
        this.switchCooldown = 0;
        MineComplexManager.get().setSimple(MineComplexData.SimpleRegion.of(region));
        ChatUtils.debug("开始挖掘任务：" + region.describe());
    }

    /**
     * 开一个**临时**任务：和 {@link #start} 完全一样，但不写 {@code mine.json}。
     *
     * <p>给「一次性的调试任务」用（{@code /pc mine debug_chunk_miner}）：
     * 那种任务重进世界不该被接回来，也不该盖掉玩家自己的 simple 任务缓存。
     */
    public void startTransient(MineRegion region) {
        release();
        this.region = region;
        this.state = State.STARTING;
        this.target = null;
        this.targetTool = null;
        this.lastTickCount = 0;
        this.switchCooldown = 0;
        ChatUtils.debug("开始临时挖掘任务（不写 mine.json）：" + region.describe());
    }

    /** 取消所有挖掘任务（simple / complex），mine.json 里的 simple 缓存也一起清掉。有没有东西被取消掉。 */
    public boolean cancel() {
        boolean hadSaved = MineComplexManager.get().clearSimple();
        boolean stopped = stop();
        return hadSaved || stopped;
    }

    /**
     * 只停掉正在跑的任务，<b>不动</b> mine.json 里的缓存。
     *
     * <p>给「临时让一让」的场景用（比如备货要自己挖方块，见 {@code StockTask}）：
     * 停完范围还留着，重新进世界照样能接回来。
     */
    public boolean stop() {
        if (region == null) return false;

        ChatUtils.debug("停下挖掘任务：" + region.describe());
        region = null;
        state = State.IDLE;
        target = null;
        targetTool = null;
        lastTickCount = 0;
        switchCooldown = 0;
        release();
        return true;
    }

    /** 进世界 / 断线的时候清一下（不发任何包）。mine.json 里的缓存不动。 */
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

    /**
     * 把 mine.json 里缓存的 simple 任务接回来（重新进世界 / 换世界配置之后用）。
     *
     * <p>缓存里有就接着挖；没有的话把当前这个任务收掉 —— 换世界配置时不能让上一个世界的任务
     * 继续跑、还往新的 mine.json 里写。
     */
    public void resumeSaved() {
        MineComplexData.SimpleRegion saved = MineComplexManager.get().simple();
        if (saved == null || saved.dimension() == null) {
            if (region != null) cancel();
            return;
        }

        MineRegion restored = saved.toRegion(MineFilterConfig.get().filter());
        start(restored);
        ChatUtils.debug("挖掘：已接回 mine.json 里缓存的 simple 任务 " + restored.describe());
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
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (region == null) {
            release();
            setIdle();
            return;
        }

        if (player == null || mc.level == null || mc.gameMode == null) {
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

        // 玩家沾水就一概不挖：水里游泳 / 漂浮会让客户端和服务端的挖掘状态对不上，
        // 而且水里掉得慢，速度判定的余量很容易被绕过
        if (touchingWater(mc, player)) {
            lastTickCount = 0;
            setState(State.IN_WATER);
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

        double reach = PlayerControllerConfig.getReach();
        Vec3 eye = player.getEyePosition();

        MineTools.Kind held = heldTool(player);

        // 手上这件工具附近还挖得动东西吗？挖得动就**不换**：切得越少，服务端那边越不容易对不上
        boolean somethingMineable = false;
        for (BlockPos pos : candidates) {
            if (suitable(mc.level.getBlockState(pos), held)) {
                somethingMineable = true;
                break;
            }
        }

        if (!somethingMineable) {
            // 一个都挖不动了才换：换成最近那个方块要的工具
            BlockPos nearest = candidates.get(0);
            MineTools.Kind desired = MineTools.required(mc.level.getBlockState(nearest));

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
            if (suitable(mc.level.getBlockState(pos), held)) {
                target = pos;
                break;
            }
        }
        if (target == null) {
            target = candidates.get(0);
            targetTool = MineTools.required(mc.level.getBlockState(target));
        }

        int limit = PlayerControllerConfig.getMineMaxBlocksPerTick();
        int mined = 0;
        for (BlockPos pos : candidates) {
            if (mined >= limit) break;

            // 挖着挖着工具坏了：这一 tick 就停，下一 tick 重新判断
            if (mined > 0 && heldTool(player) != held) break;

            BlockState state = mc.level.getBlockState(pos);
            // 只对手上这件工具挖得动的方块发包
            if (!suitable(state, held)) continue;
            // 再怎么也不能超出触及范围
            if (!inRange(eye, pos, reach)) continue;

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
     * 玩家现在是不是「站定了」：x、z 两个方向的水平速度分别不超过
     * {@code mine_max_horizontal_speed_blocks_per_tick}（默认 0），而且纵向速度不超过
     * {@code mine_max_vertical_speed_blocks_per_tick}（默认 0.08 格/tick）。
     *
     * <p>站着不动的时候原版算出来就是 (0, 0, 0)，所以默认值下照常挖；
     * 某一个上限配成 0 就要求那个方向的速度正好是 0。
     */
    private static boolean isStill(LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();

        double horizontalLimit = PlayerControllerConfig.getMineMaxHorizontalSpeedPerTick();
        if (Math.abs(velocity.x) > horizontalLimit) return false;
        if (Math.abs(velocity.z) > horizontalLimit) return false;

        double verticalLimit = PlayerControllerConfig.getMineMaxVerticalSpeedPerTick();
        return Math.abs(velocity.y) <= verticalLimit;
    }

    /**
     * 玩家身上有没有水。
     *
     * <p>不看 {@code player.isInWater()} 那一个标志：开了飞行之后它在水里也会是 false，
     * 结果「水里飞着」照样挖。这里直接查世界：玩家包围盒覆盖到的方块，只要流体状态是水就算沾水。
     * 原版的标志也一并认（谁说是水都算），多拦一点没有坏处。
     */
    private static boolean touchingWater(Minecraft mc, LocalPlayer player) {
        if (player.isInWater()) return true;
        if (mc.level == null) return false;

        // 稍微缩一点，免得贴着水面的时候因为浮点误差把旁边那格水算进来
        AABB box = player.getBoundingBox().deflate(1.0E-4);
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (mc.level.getFluidState(pos).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** 现在手上这件东西算哪一类工具。 */
    @Nullable
    private static MineTools.Kind heldTool(LocalPlayer player) {
        return MineTools.held(player.getInventory().getSelectedItem());
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
    private List<BlockPos> collect(Minecraft mc, LocalPlayer player) {
        double reach = PlayerControllerConfig.getReach();
        int radius = (int) Math.ceil(reach) + 1;

        BlockPos center = BlockPos.containing(player.position());
        Vec3 eye = player.getEyePosition();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        List<BlockPos> found = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!region.contains(mutable)) continue;

                    BlockState state = mc.level.getBlockState(mutable);
                    if (!breakable(mc.level, mutable, state)) continue;
                    if (!region.filter().allows(state.getBlock())) continue;
                    if (!inRange(eye, mutable, reach)) continue;

                    found.add(mutable.immutable());
                }
            }
        }

        found.sort(Comparator.comparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))));
        return found;
    }

    /** 不是空气、不是水 / 岩浆、也不是基岩那种挖不动的。 */
    private static boolean breakable(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.getBlock() instanceof LiquidBlock) return false;
        return state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /**
     * 这个方块够不够得着：眼睛到方块中心的距离不超过 {@code actions.reach}。
     *
     * <p>不再区分看不看得见 / 隔不隔墙（原来那套 range + wall_range 已经合并成 reach 了）。
     */
    private static boolean inRange(Vec3 eye, BlockPos pos, double reach) {
        return eye.distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
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
    private static void mine(Minecraft mc, LocalPlayer player, BlockPos pos) {
        Direction side = PlayerUtils.facingSide(pos);
        ClientLevel level = mc.level;
        ClientPlayerInteractionManagerAccessor accessor =
            (ClientPlayerInteractionManagerAccessor) mc.gameMode;

        accessor.playerController$startPrediction(level, sequence ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side, sequence));
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        accessor.playerController$startPrediction(level, sequence ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, side, sequence));
    }
}
