package org.six_coin.playerController.client.mine;

import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>两处**只给复杂挖掘**用的额外规则（{@code /pc mine simple} 不受影响）：
 * <ul>
 *   <li><b>重力方块保护</b>（{@link #startTransient(MineRegion, boolean)}）：
 *       上面还压着别的重力方块的，先不挖 —— 挖掉下面那个，上面那一摞会落进刚挖空的洞里；</li>
 *   <li><b>debug 工具策略</b>（{@link #setDebugToolPolicy(java.util.Collection)}）：
 *       掉落物不要、又（原工具秒破不了 或 空手能秒破）的方块，适合的工具算「空手」，
 *       切到快捷栏 9 去挖。</li>
 * </ul>
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

    /**
     * debug_chunk_miner 里「空手」对应哪一格：快捷栏 9（食物那一格）。
     *
     * <p>见 {@link #setDebugToolPolicy(Collection)}：那些「空手就能秒破」的方块切到这一格去挖。
     */
    public static final int DEBUG_HAND_HOTBAR = 8;

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

    /**
     * 重力方块保护（只在复杂挖掘里开，见 {@link #startTransient(MineRegion, boolean)}）。
     *
     * <p>开着的时候：**上面还有重力方块的**重力方块不能当挖掘目标 —— 先挖上面那个，
     * 让它落下来，再挖落下来的这个。不然挖掉下面那个，上面那一摞会掉进已经挖空的洞里。
     */
    private boolean gravityGuard;

    /** 这一份「工具策略」是不是 debug_chunk_miner 的（见 {@link #setDebugToolPolicy(java.util.Collection)}）。 */
    private boolean debugToolPolicy;

    /** debug_chunk_miner 的收集清单（物品 id），判断「掉落物要不要」用。 */
    private final Set<String> debugCollectorItems = new HashSet<>();

    /**
     * debug_chunk_miner 的「每种方块该用什么」缓存。
     *
     * <p>需求里特意要求**只算一次、只放内存里**：这个判断要查附魔、查掉落物，
     * 每种方块都算一遍太贵，但也不值得落盘（换个任务、换份工具就可能不一样）。
     */
    private final Map<Block, DebugNeed> debugNeeds = new HashMap<>();

    /** 快捷栏 / 朝向现在是不是我们占着（还的时候用）。 */
    private boolean hotbarHeld;
    private boolean rotateHeld;

    /**
     * 这一份任务是不是临时的（{@link #startTransient} 起的）。
     *
     * <p>临时任务不自动取消：复杂挖掘靠「没东西可挖」这个状态推进流程，自己 cancel 会把它卡死。
     */
    private boolean transientTask;

    /** 连续几 tick 没东西可挖了（见 {@link #maybeAutoCancel}）。 */
    private int emptyTicks;

    /** 连续这么多 tick 没东西可挖，才开始看「整个区域是不是空了」。 */
    private static final int CLEAR_SETTLE_TICKS = 20;

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
        this.gravityGuard = false;
        this.transientTask = false;
        this.emptyTicks = 0;
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
        startTransient(region, false);
    }

    /**
     * 开一个临时任务，并指定要不要开「重力方块保护」。
     *
     * <p>复杂挖掘（{@code /pc mine complex ...}、{@code debug_chunk_miner}）挖的是整块区域，
     * 一摞沙砾 / 沙子从上面挖会把下面刚挖空的地方埋了，所以**上面还有重力方块的**那个不挖
     * （先挖最上面那个，落下来再挖）。{@code /pc mine simple} 不开这个保护。
     */
    public void startTransient(MineRegion region, boolean gravityGuard) {
        release();
        this.region = region;
        this.state = State.STARTING;
        this.target = null;
        this.targetTool = null;
        this.lastTickCount = 0;
        this.switchCooldown = 0;
        this.gravityGuard = gravityGuard;
        this.transientTask = true;
        this.emptyTicks = 0;
        ChatUtils.debug("开始临时挖掘任务（不写 mine.json"
            + (gravityGuard ? "，开重力方块保护" : "") + "）：" + region.describe());
    }

    /** 这一份挖掘任务要不要「上面还有重力方块的不挖」。 */
    public boolean gravityGuard() {
        return gravityGuard;
    }

    // ------------------------------------------------------------------
    // debug_chunk_miner 的工具策略
    // ------------------------------------------------------------------

    /**
     * 换成 {@code debug_chunk_miner} 的那套工具策略（只影响这一次任务，不落盘）。
     *
     * <p>规则（需求原文）：
     * <pre>
     * (掉落物没有一种在 item_collector_list 里) 且 ((原本适合的工具不能秒破) 或 (空手能秒破))
     *     → 这个方块「适合的工具」就是**空手**（快捷栏 9 那一格）
     * </pre>
     * 也就是：掉落物不要的方块，只要「原工具秒不掉」或者「空手就能秒」，就切到空手去挖；
     * 掉落物要的方块一律按原来的工具规则来（哪怕空手也能秒破）。
     *
     * @param collectorItems item_collector_list 里的物品 id
     */
    public void setDebugToolPolicy(Collection<String> collectorItems) {
        debugCollectorItems.clear();
        for (String id : collectorItems) {
            if (id != null) debugCollectorItems.add(id);
        }
        debugNeeds.clear();
        debugToolPolicy = true;
        ChatUtils.debug("挖掘：启用 debug 工具策略（收集清单 " + debugCollectorItems.size() + " 种）");
    }

    /** 关掉 debug 工具策略（缓存一起清掉）。 */
    public void clearDebugToolPolicy() {
        debugToolPolicy = false;
        debugNeeds.clear();
        debugCollectorItems.clear();
    }

    /** 一种方块在 debug 工具策略下的结论。 */
    private enum DebugNeed {
        /** 按原来的规则（原版那四张 mineable 表）。 */
        NORMAL,
        /** 适合的工具是空手（快捷栏 9）。 */
        HAND
    }

    /** 一个方块对「手上要拿什么」的要求。 */
    private record ToolNeed(@Nullable MineTools.Kind kind, boolean hand) {

        static final ToolNeed ANY = new ToolNeed(null, false);
    }

    /** 这个方块对工具的要求（debug 策略开着的时候会走缓存）。 */
    private ToolNeed toolNeed(BlockState state) {
        if (!debugToolPolicy) {
            MineTools.Kind kind = MineTools.required(state);
            return kind == null ? ToolNeed.ANY : new ToolNeed(kind, false);
        }

        DebugNeed need = debugNeeds.get(state.getBlock());
        if (need == null) {
            need = computeDebugNeed(state);
            debugNeeds.put(state.getBlock(), need);
        }

        return switch (need) {
            case HAND -> new ToolNeed(null, true);
            case NORMAL -> {
                MineTools.Kind kind = MineTools.required(state);
                yield kind == null ? ToolNeed.ANY : new ToolNeed(kind, false);
            }
        };
    }

    /** 算一次「这种方块在 debug 策略下算什么」（结果会缓存）。 */
    private DebugNeed computeDebugNeed(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) return DebugNeed.NORMAL;

        float hardness = state.getDestroySpeed(mc.level, BlockPos.containing(player.position()));

        // (掉落物没有一种在 item_collector_list 里)
        boolean wanted = dropWanted(state);
        if (wanted) return DebugNeed.NORMAL;

        // 且 ((原本适合的工具不能秒破) 或 (空手能秒破))
        boolean instaByHand = MineTools.canInstaMine(state, null, player, hardness);
        MineTools.Kind kind = MineTools.required(state);
        boolean instaWithTool = MineTools.canInstaMine(state, toolStack(player, kind), player, hardness);

        if (instaByHand || !instaWithTool) {
            debugNeed("掉落物不要，且" + (instaByHand ? "空手就能秒破" : "原工具也秒破不了"), state);
            return DebugNeed.HAND;
        }
        return DebugNeed.NORMAL;
    }

    private static void debugNeed(String why, BlockState state) {
        ChatUtils.debug("debug 工具策略：" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
            + " → " + why);
    }

    /**
     * 快捷栏里那件「这一类工具」的样品（debug 布局里工具固定放在快捷栏 1~5）。
     *
     * <p>找不到就返回 {@code null}（按空手算）。
     */
    @Nullable
    private static ItemStack toolStack(@Nullable LocalPlayer player, @Nullable MineTools.Kind kind) {
        if (player == null || kind == null) return null;
        Inventory inventory = player.getInventory();
        int slot = MineTools.hotbarSlot(inventory, kind);
        if (slot < 0) return null;
        return inventory.getItem(slot);
    }

    /**
     * 这个方块的掉落物里有没有 {@code item_collector_list} 里的东西。
     *
     * <p><b>客户端拿不到战利品表</b>（{@code LootParams} 要 {@code ServerLevel}），所以这里用
     * 「方块自己的物品形态 + 常见转换表」近似：石头 → 圆石、深板岩 → 深板岩圆石、矿石 → 产物……
     * 近似方向是**宁可多算**（多算 = 认为掉落物有用 = 保持原来的工具），不会因为漏算而把
     * 有价值的方块跳过。
     */
    private boolean dropWanted(BlockState state) {
        if (debugCollectorItems.isEmpty()) return false;

        Block block = state.getBlock();
        for (Item item : dropsOf(block)) {
            if (debugCollectorItems.contains(BuiltInRegistries.ITEM.getKey(item).toString())) {
                return true;
            }
        }
        return false;
    }

    /** 一个方块「可能掉什么」（近似，见 {@link #dropWanted}）。 */
    private static Set<Item> dropsOf(Block block) {
        Set<Item> drops = new LinkedHashSet<>();
        Item self = block.asItem();
        if (self != Items.AIR) drops.add(self);

        if (block == Blocks.STONE) drops.add(Items.COBBLESTONE);
        else if (block == Blocks.DEEPSLATE) drops.add(Items.COBBLED_DEEPSLATE);
        else if (block == Blocks.GRASS_BLOCK || block == Blocks.MYCELIUM || block == Blocks.PODZOL
            || block == Blocks.DIRT_PATH || block == Blocks.FARMLAND || block == Blocks.ROOTED_DIRT) {
            drops.add(Items.DIRT);
        } else if (block == Blocks.GRAVEL) {
            drops.add(Items.FLINT);
        } else if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            drops.add(Items.COAL);
        } else if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            drops.add(Items.RAW_IRON);
        } else if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            drops.add(Items.RAW_COPPER);
        } else if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
            drops.add(Items.RAW_GOLD);
        } else if (block == Blocks.NETHER_GOLD_ORE) {
            drops.add(Items.GOLD_NUGGET);
        } else if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            drops.add(Items.REDSTONE);
        } else if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            drops.add(Items.EMERALD);
        } else if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            drops.add(Items.LAPIS_LAZULI);
        } else if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            drops.add(Items.DIAMOND);
        } else if (block == Blocks.NETHER_QUARTZ_ORE) {
            drops.add(Items.QUARTZ);
        } else if (block == Blocks.CLAY) {
            drops.add(Items.CLAY_BALL);
        } else if (block == Blocks.BOOKSHELF) {
            drops.add(Items.BOOK);
        }
        return drops;
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
        gravityGuard = false;
        transientTask = false;
        emptyTicks = 0;
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
        gravityGuard = false;
        transientTask = false;
        emptyTicks = 0;
        debugToolPolicy = false;
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
            maybeAutoCancel(mc);
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
            ToolNeed desired = toolNeed(mc.level.getBlockState(nearest));

            boolean wantHand = desired.hand();
            MineTools.Kind wantKind = desired.kind();
            boolean switchNeeded = wantHand ? held != null : (wantKind != null && wantKind != held);

            if (switchNeeded) {
                int slot = wantHand
                    ? DEBUG_HAND_HOTBAR
                    : MineTools.hotbarSlot(player.getInventory(), wantKind);
                if (slot < 0) {
                    target = nearest;
                    targetTool = wantKind;
                    lastTickCount = 0;
                    setState(State.NO_SUITABLE_TOOL);
                    return;
                }
                if (!HotbarManager.get().request(Owner.MINE, slot)) {
                    target = nearest;
                    targetTool = wantKind;
                    lastTickCount = 0;
                    setState(State.HOTBAR_DENIED);
                    return;
                }
                hotbarHeld = true;
                held = wantHand ? null : wantKind;
                target = nearest;
                targetTool = wantKind;
                ChatUtils.debug("挖掘：手上这件工具挖不动附近的方块了，换成"
                    + (wantHand ? "空手（快捷栏 9）" : wantKind.label()));

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
            maybeAutoCancel(mc);
        }
    }

    /** 手上这件工具（{@code null} = 空手）挖不挖得动这个方块。 */
    private boolean suitable(BlockState state, @Nullable MineTools.Kind held) {
        ToolNeed need = toolNeed(state);
        if (need.hand()) return held == null;
        return need.kind() == null || need.kind() == held;
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
        // 「没东西可挖」只要断一次就重新数：中间挖到过东西 / 走开过，都不算连续空转
        if (next != State.NO_BLOCKS) emptyTicks = 0;
        if (state == next) return;
        ChatUtils.debug("挖掘：" + state.text() + " → " + next.text());
        state = next;
    }

    // ------------------------------------------------------------------
    // 挖空了就自己收工
    // ------------------------------------------------------------------

    /**
     * 连续 {@value #CLEAR_SETTLE_TICKS} tick 没东西可挖之后，看一眼整个区域是不是已经清空；
     * 是的话就当玩家执行了 {@code /pc mine cancel}。
     *
     * <p>只在 {@code /pc mine simple}（{@link #start}）上做：复杂挖掘是 {@link #startTransient}
     * 起的，它靠 {@code NO_BLOCKS} 这个状态推进流程，自己 cancel 会把调用方卡死。
     *
     * <p>「清空」= 区域里已经没有这个任务会挖的方块（空气、水 / 熔岩、挖不动的、过滤器不要的
     * 都不算），而且盖住区域的所有区块都加载了 —— 没加载就不知道那边还有没有东西，不动。
     */
    private void maybeAutoCancel(Minecraft mc) {
        if (transientTask) return;
        if (region == null) return;

        if (++emptyTicks < CLEAR_SETTLE_TICKS) return;
        emptyTicks = 0;

        if (!regionCleared(mc)) return;

        ChatUtils.info("挖掘区已经清空（" + region.describe() + "），自动取消挖掘任务");
        cancel();
    }

    /**
     * 区域里还有没有「这个任务会挖的方块」。
     *
     * <p>整块区域都加载了才回答 yes / no；有一个区块没加载就返回 false（不知道）。
     * 先用 {@link LevelChunkSection#hasOnlyAir()} 快速跳过空的子区块，只在
     * 有东西的（以及跨在区域边界上的）子区块里逐格看。
     */
    private boolean regionCleared(Minecraft mc) {
        MineRegion target = region;
        ClientLevel level = mc.level;
        if (target == null || level == null) return false;
        if (!target.dimension().equals(DimensionUtils.current())) return false;

        BlockPos min = target.min();
        BlockPos max = target.max();
        int minSectionY = level.getMinSectionY();
        int maxSectionY = minSectionY + level.getSectionsCount() - 1;

        int fromSectionY = Math.max(min.getY() >> 4, minSectionY);
        int toSectionY = Math.min(max.getY() >> 4, maxSectionY);

        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return false;

                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) return false;
                LevelChunkSection[] sections = chunk.getSections();

                int x0 = Math.max(min.getX(), cx << 4);
                int x1 = Math.min(max.getX(), (cx << 4) + 15);
                int z0 = Math.max(min.getZ(), cz << 4);
                int z1 = Math.min(max.getZ(), (cz << 4) + 15);

                for (int sectionY = fromSectionY; sectionY <= toSectionY; sectionY++) {
                    int index = level.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= sections.length) continue;

                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) continue;

                    int baseY = sectionY * 16;
                    int lo = Math.max(min.getY(), baseY);
                    int hi = Math.min(max.getY(), baseY + 15);

                    for (int y = lo; y <= hi; y++) {
                        for (int x = x0; x <= x1; x++) {
                            for (int z = z0; z <= z1; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (breakable(level, new BlockPos(x, y, z), state)
                                    && target.filter().allows(state.getBlock())) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
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
                    if (blockedByGravity(mc.level, mutable, state)) continue;
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
     * 重力方块保护：这一格是不是「上面还压着重力方块」的重力方块。
     *
     * <p>沙砾 / 沙子 / 混凝土粉末 / 铁砧这些会往下掉：先挖下面那个，上面那一摞会整摞落下来
     * （落进已经挖空的洞里，还得再挖一遍，还可能把掉落物埋了）。所以只挖**最上面**那个。
     */
    private boolean blockedByGravity(ClientLevel level, BlockPos pos, BlockState state) {
        if (!gravityGuard) return false;
        if (!isGravity(state)) return false;

        BlockPos above = pos.above();
        // 上面那块不在这次的挖掘范围里：我们永远挖不到它，再拦着就等于永远不挖这一块。
        // 让它掉进来反而是好事 —— 掉进区域之后它自己就变成「范围内的方块」，下一轮就能挖掉。
        if (region == null || !region.contains(above)) return false;

        return isGravity(level.getBlockState(above));
    }

    /** 会不会往下掉（沙砾、沙子、混凝土粉末、铁砧……）。 */
    private static boolean isGravity(BlockState state) {
        return state.getBlock() instanceof FallingBlock;
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
