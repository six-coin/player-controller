package org.six_coin.playerController.client.mending;

import net.minecraft.block.EnderChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.action.Action;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.action.PathMoveAction;
import org.six_coin.playerController.client.attack.AttackConfig;
import org.six_coin.playerController.client.attack.AttackManager;
import org.six_coin.playerController.client.attack.AttackProfile;
import org.six_coin.playerController.client.attack.AttackProfiles;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerClicks;
import org.six_coin.playerController.client.container.ContainerOpener;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.container.InventoryUtils;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.PlayerUtils;
import org.six_coin.playerController.client.waypoint.PathPlanner;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * mending：站在末影箱旁边，把要修的东西一件一件换到副手，靠 {@code /pc attack} 刷经验喂经验修补。
 *
 * <p>{@code /pc mending start} 的顺序（就是代码里的顺序）：
 *
 * <ol>
 *   <li>检查 attack 里有 {@link #PROFILE} 这份 profile（没有就报错，让用户自己建）；</li>
 *   <li>检查快捷栏第一位是一把带经验修补、剩余耐久 &gt; {@link AttackManager#MIN_DURABILITY} 的剑；</li>
 *   <li>检查玩家脚下是路径点或者某条边（否则既走不过去，也走不回来）；</li>
 *   <li>手上切成第一位那把剑，记下出发的位置和 attack 的开关 / profile（收尾时还原）；</li>
 *   <li>走 {@link PathPlanner} 算出来的路到站立点；</li>
 *   <li>attack 换成 {@link #PROFILE} 并打开；</li>
 *   <li>设了末影箱就打开它（界面一直开着：末影箱里的东西只有界面开着才看得到）；</li>
 *   <li>每隔 {@code actions.mending_detect_interval_ticks} 检测一次。</li>
 * </ol>
 *
 * <p>每次检测做三件事：
 *
 * <ol>
 *   <li>站立点半径 {@value #ORB_RADIUS} 格（球）里经验球超过 {@value #ORB_LIMIT} 个就把攻击关掉，
 *       否则打开；</li>
 *   <li>物品栏（快捷栏、背包、盔甲、副手）和末影箱里还有没有「耐久不满 + 有经验修补」的东西，
 *       一件都没有就退出检测；潜影盒里的东西不处理；</li>
 *   <li>副手空着或者已经修满了，就把副手和临时物品格换回来（有的话），再挑下一件要修的
 *       换到副手（第一位那把剑、盔甲都不挪：它们在装备栏里本来就会被修）。</li>
 * </ol>
 *
 * <p>退出检测以后：换回副手 → 关末影箱 → 还原 attack → 走回出发的位置。
 *
 * <p>连续 {@code actions.mending_max_wait_exp_seconds} 秒没有任何东西被修好，就当作附近没有经验来源，
 * 主动退出检测（免得一直干等）。
 *
 * <p>换东西用的是原版「和副手交换」（{@code SWAP} + 按钮 40，就是 F 键那一下）：
 * 物品栏、末影箱里的格子都能直接这样跟副手换，不需要先把东西搬到快捷栏。
 */
public final class MendingManager {

    /** 经验球超过这个数就先把攻击关掉。 */
    public static final int ORB_LIMIT = 100;

    /** 只有站立点周围这么多格（球）里的经验球才数。 */
    public static final double ORB_RADIUS = 3.0;

    /** mending 用的 attack profile 名字。 */
    public static final String PROFILE = "mending";

    /**
     * 快捷栏第一位。
     *
     * <p>这一格是常驻的剑：start 的时候要求它是一把带经验修补、剩余耐久
     * {@code >} {@link AttackManager#MIN_DURABILITY} 的剑（和攻击挑武器的门槛一样），
     * 开始以后手上就切成它。它和盔甲一样**不挪地方**，也不会被换到副手 ——
     * 但它自己的磨损一样算在「还有没有要修的」里面。
     */
    public static final int FIRST_SLOT = 0;

    /** 等末影箱界面出来最多等这么多 tick。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;

    /** 原版「和副手交换」那一下：{@code SWAP} + 这个按钮号（F 键）。 */
    private static final int SWAP_OFFHAND_BUTTON = 40;

    private static final MendingManager INSTANCE = new MendingManager();

    /** 现在跑到哪一步了（{@code /pc mending} 会显示）。 */
    public enum Stage {

        IDLE("没在跑"),
        MOVE_OUT("走去站立点"),
        OPEN_CHEST("打开末影箱"),
        LOOP("检测中"),
        EXIT("收尾"),
        MOVE_BACK("回出发的位置");

        private final String text;

        Stage(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** 交换的两边：物品栏里的第几格 / 容器里的第几格（界面里的槽位号会变，这个不会）。 */
    private enum Side {
        PLAYER,
        CONTAINER
    }

    private record SlotKey(Side side, int index) {
    }

    private record Swap(SlotKey slot, boolean restore) {
    }

    /** 一次检测的结果。 */
    private record Scan(boolean needsRepair, int damageSum, int repairable, @Nullable SlotKey candidate) {
    }

    private Stage stage = Stage.IDLE;

    /** 上一次是怎么结束的（{@code /pc mending} 会显示）。 */
    private String lastResult = "还没跑过";

    /** 出发的位置（收尾时走回来）。 */
    @Nullable
    private BlockPos returnPos;
    @Nullable
    private String returnDimension;

    /** 出发前的 attack 状态（收尾时还原）。 */
    private String attackProfileBefore = "";
    private boolean attackEnableBefore;
    /** 我们动过 attack 的配置没有（没动过就不用还原）。 */
    private boolean attackTouched;

    /** 临时物品格：副手里那件是从哪儿换过来的（null = 没换过）。 */
    @Nullable
    private SlotKey tempSlot;

    /** 排队等着点的交换（一 tick 一下）。 */
    private final Deque<Swap> swaps = new ArrayDeque<>();

    /** 正在跑的移动任务。 */
    @Nullable
    private Action moveAction;

    /** 末影箱界面（开着的时候才有）。 */
    @Nullable
    private ScreenHandler chestHandler;
    private boolean suppressorHeld;
    private int openWaitTicks;
    private int settleLeft;

    /** 距离下一次检测还有多少 tick。 */
    private int waitTicks;
    /** 点完之后等服务端把状态推回来的 tick 数。 */
    private int settleTicks;
    /** 内部计时（tick）。 */
    private long tickCount;
    /** 上一次「有进展」是哪个 tick。 */
    private long lastProgressTick;
    /** 上一次检测时所有要修的东西的耐久缺口（-1 = 还没有参照）。 */
    private int lastDamageSum = -1;

    private MendingManager() {
    }

    public static MendingManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public Stage stage() {
        return stage;
    }

    public String lastResult() {
        return lastResult;
    }

    /** 临时物品格现在指哪儿（没换过就是 null）。 */
    @Nullable
    public String tempSlotText() {
        return tempSlot == null ? null : slotName(tempSlot);
    }

    /** 站立点周围现在有几个经验球（没设站立点 / 没世界 / 不在那个维度就是 -1）。 */
    public int orbCount() {
        MinecraftClient mc = MinecraftClient.getInstance();
        MendingPos stand = MendingConfig.get().standPoint();
        if (stand == null || mc.world == null) return -1;
        if (!DimensionUtils.current().equals(stand.dimension())) return -1;
        return countOrbs(mc, stand);
    }

    /**
     * 快捷栏第一位那把剑合不合用（{@code /pc mending} 和 {@code start} 都用它）。
     *
     * @return null = 没问题；否则是一句为什么不行
     */
    @Nullable
    public static String firstSlotProblem(ClientPlayerEntity player) {
        ItemStack sword = player.getInventory().getStack(FIRST_SLOT);
        if (sword.isEmpty()) {
            return "快捷栏第一位是空的，要放一把带经验修补的剑";
        }
        if (!sword.isIn(ItemTags.SWORDS)) {
            return "快捷栏第一位不是剑（现在是 " + InventoryUtils.describe(sword) + "）";
        }
        if (!MendingItems.hasMending(sword)) {
            return "快捷栏第一位那把剑没有经验修补（" + InventoryUtils.describe(sword) + "）";
        }

        int remaining = MendingItems.remainingDurability(sword);
        if (remaining <= AttackManager.MIN_DURABILITY) {
            return "快捷栏第一位那把剑剩余耐久 " + remaining + "，要大于 " + AttackManager.MIN_DURABILITY
                + "（和攻击挑武器的门槛一样）";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // /pc mending start
    // ------------------------------------------------------------------

    /**
     * 开始。
     *
     * @return null = 开始了；否则是为什么没开始（命令层直接打出来）
     */
    @Nullable
    public String start() {
        if (stage != Stage.IDLE) {
            return "mending 已经在跑了（现在：" + stage.text() + "）";
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return "没有玩家";

        // 1) attack 里必须有 mending 这份 profile
        if (!AttackProfiles.exists(PROFILE)) {
            return "attack 里没有叫 " + PROFILE + " 的 profile，先用 /pc attack profile add " + PROFILE
                + " 建一份（里面写清楚边上刷什么怪）";
        }
        AttackProfile profile = AttackProfile.load(PROFILE);
        if (profile == null || profile.isEmpty()) {
            ChatUtils.info("§emending：profile「" + PROFILE + "」里一个生物都没有，"
                + "开起来也不会打任何东西（/pc attack show_all_mobs 里点 [添加]）");
        }

        // 2) 快捷栏第一位必须是合用的剑（它一直在手上打，所以不挪；门槛和攻击那边一致）
        String swordProblem = firstSlotProblem(player);
        if (swordProblem != null) return swordProblem;

        // 3) 玩家脚下必须是路径点或某条边上：既走不过去也走不回来就没意义了
        if (WaypointManager.get().playerStartWaypoint() == null) {
            BlockPos here = PlayerUtils.currentBlockPos();
            return "你现在所在的 " + (here == null ? "?" : here.toShortString())
                + " 既不是路径点，也不在任何边上，先用 /pc waypoints add_waypoint here 加一个";
        }

        // 4) 站立点
        MendingPos stand = MendingConfig.get().standPoint();
        if (stand == null) {
            return "还没设站立点：/pc mending station stand_point set_here";
        }

        // 5) 别的任务在跑就不掺和（移动和容器都是共用的）
        if (ActionManager.get().isBusy()) {
            Action busy = ActionManager.get().currentAction();
            return "现在还有别的任务在跑" + (busy == null ? "" : "（" + busy.name() + "）")
                + "，等它跑完再开始";
        }

        clearRuntime();

        // 手上先换成第一位那把剑（只切这一次；之后攻击自己还会按需要切）
        if (HotbarManager.get().request(Owner.MENDING, FIRST_SLOT)) {
            HotbarManager.get().release(Owner.MENDING);
        }

        // 记下出发的位置和攻击状态，收尾时还原
        returnPos = PlayerUtils.currentBlockPos();
        returnDimension = DimensionUtils.current();
        AttackConfig attack = AttackConfig.get();
        attackProfileBefore = attack.profileName();
        attackEnableBefore = attack.enable();
        attackTouched = false;

        String error = beginMove(stand.pos(), stand.dimension(), Stage.MOVE_OUT);
        if (error != null) {
            clearRuntime();
            return error;
        }

        if (stage != Stage.MOVE_OUT) {
            // 已经站在站立点上了，不用走
            error = arriveAtStand();
            if (error != null) {
                finishSilently();
                return error;
            }
            ChatUtils.info("mending：已经在站立点上，直接开始");
            return null;
        }

        ChatUtils.info("mending：先去站立点 " + stand.describe());
        return null;
    }

    /** 进世界 / 断线：静默清掉（不走回去，也不发交换包）。 */
    public void reset() {
        if (stage == Stage.IDLE) {
            clearRuntime();
            return;
        }
        ChatUtils.debug("进世界：mending 已重置（原来在：" + stage.text() + "）");
        closeChest(MinecraftClient.getInstance());
        RotateManager.get().release(Owner.MENDING);
        restoreAttack();
        lastResult = "被重置（进世界 / 断线）";
        clearRuntime();
    }

    /** 出错中止：能换回来就换回来，然后收尾（不走回原位）。 */
    public void fail(String reason) {
        if (stage == Stage.IDLE) return;

        ChatUtils.error("mending 中止：" + reason);
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        // 还活着、界面还在的话，把副手先换回去
        if (tempSlot != null && player != null && player.isAlive() && player.currentScreenHandler != null) {
            ScreenHandler handler = player.currentScreenHandler;
            Slot slot = resolve(handler, player, tempSlot);
            if (slot != null) {
                ContainerClicks.click(handler, slot, SWAP_OFFHAND_BUTTON, SlotActionType.SWAP,
                    "中止前把副手换回来");
            }
        }

        closeChest(mc);
        RotateManager.get().release(Owner.MENDING);
        restoreAttack();
        lastResult = "中止：" + reason;
        clearRuntime();
    }

    // ------------------------------------------------------------------
    // 每 tick
    // ------------------------------------------------------------------

    public void tick() {
        tickCount++;
        if (stage == Stage.IDLE) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;
        if (!player.isAlive()) {
            fail("你死了");
            return;
        }

        switch (stage) {
            case MOVE_OUT -> tickMoveOut();
            case OPEN_CHEST -> tickOpenChest(mc, player);
            case LOOP -> tickLoop(mc, player);
            case EXIT -> tickExit(mc, player);
            case MOVE_BACK -> tickMoveBack();
            case IDLE -> {
            }
        }
    }

    private void tickMoveOut() {
        if (moveAction == null) {
            fail("没有移动任务");
            return;
        }
        if (!moveAction.isFinished()) return;

        String failure = moveAction.failureReason();
        moveAction = null;
        if (failure != null) {
            fail("走去站立点失败：" + failure);
            return;
        }

        String error = arriveAtStand();
        if (error != null) {
            fail(error);
            return;
        }
        ChatUtils.info("mending：到站立点了");
    }

    private void tickOpenChest(MinecraftClient mc, ClientPlayerEntity player) {
        MendingPos chest = MendingConfig.get().enderChest();
        if (chest == null) {
            stage = Stage.LOOP;
            return;
        }

        if (chestHandler == null) {
            ScreenHandler current = player.currentScreenHandler;
            if (current != null && current != player.playerScreenHandler) {
                chestHandler = current;
                settleLeft = PlayerControllerConfig.getNetworkDelayTicks();
                ChatUtils.debug("mending：末影箱界面已打开（%d 格），等格子内容同步过来", current.slots.size());
                return;
            }
            if (++openWaitTicks > MAX_OPEN_WAIT_TICKS) {
                fail("等末影箱界面超时（" + chest.describe() + "）：确认那里是末影箱、而且够得着");
            }
            return;
        }

        if (chestHandler.getRevision() != 0) settleLeft = 0;
        if (settleLeft > 0) {
            settleLeft--;
            return;
        }

        ChatUtils.debug("mending：末影箱界面内容已同步，开始检测");
        stage = Stage.LOOP;
        waitTicks = 0;
    }

    private void tickLoop(MinecraftClient mc, ClientPlayerEntity player) {
        MendingPos stand = MendingConfig.get().standPoint();
        if (stand == null) {
            exitLoop("站立点被删掉了");
            return;
        }
        if (!DimensionUtils.current().equals(stand.dimension())) {
            fail("维度变了（站立点在 " + DimensionUtils.display(stand.dimension()) + "）");
            return;
        }

        // 末影箱界面掉了就重新开：里面的东西只有界面开着才看得到，临时格也可能就在里面
        MendingPos chest = MendingConfig.get().enderChest();
        if (chest != null && (chestHandler == null || player.currentScreenHandler != chestHandler)) {
            ChatUtils.debug("mending：末影箱界面没了，重新打开");
            stage = Stage.OPEN_CHEST;
            openChest(mc, player, chest.pos());
            return;
        }

        if (settleTicks > 0) {
            settleTicks--;
            return;
        }

        if (!swaps.isEmpty()) {
            performSwap(player);
            settleTicks = PlayerControllerConfig.getNetworkDelayTicks();
            return;
        }

        if (waitTicks++ < PlayerControllerConfig.getMendingDetectIntervalTicks()) return;
        waitTicks = 0;
        detect(mc, player, stand);
    }

    // ------------------------------------------------------------------
    // 一次检测
    // ------------------------------------------------------------------

    /**
     * 一次检测。
     *
     * <p>第 3 步里「副手已经修满了」是按 {@link MendingItems#needsRepair} 判断的：
     * 副手里要是坏了一把**没有经验修补**的剑，它永远修不满，但也修不了 ——
     * 那种也算「让出来了」（换出去），不然整轮会卡在它身上。
     */
    private void detect(MinecraftClient mc, ClientPlayerEntity player, MendingPos stand) {
        // 1) 经验球太多就先别打了，等捡起来一些再继续
        int orbs = countOrbs(mc, stand);
        setAttack(orbs <= ORB_LIMIT, "站立点周围 " + orbs + " 个经验球");

        // 2) 还有没有要修的东西
        Scan scan = scan(player);
        ChatUtils.debug("mending：还要修 %d 件，合计缺口 %d，经验球 %d 个，副手 %s",
            scan.repairable(), scan.damageSum(), orbs,
            InventoryUtils.describe(player.getOffHandStack()));
        if (!scan.needsRepair()) {
            exitLoop("没有需要修的物品了");
            return;
        }

        // 「有进展」= 所有要修的东西加起来，耐久缺口变小了
        if (lastDamageSum >= 0 && scan.damageSum() < lastDamageSum) {
            lastProgressTick = tickCount;
        }
        lastDamageSum = scan.damageSum();

        int maxWait = PlayerControllerConfig.getMendingMaxWaitExpSeconds();
        if (tickCount - lastProgressTick > maxWait * 20L) {
            exitLoop(maxWait + " 秒没有任何东西被修好（附近大概没有经验来源）");
            return;
        }

        // 3) 副手：还在修就等；空着 / 已经修满了就换下一件
        if (MendingItems.needsRepair(player.getOffHandStack())) return;

        if (tempSlot != null) {
            swaps.add(new Swap(tempSlot, true));
            tempSlot = null;
        }

        SlotKey candidate = scan.candidate();
        if (candidate == null) return;

        // 临时物品格在这一下真的点出去了之后才算数（见 performSwap）
        swaps.add(new Swap(candidate, false));
    }

    /** 数站立点周围（半径 {@value #ORB_RADIUS} 的球）里的经验球。 */
    private static int countOrbs(MinecraftClient mc, MendingPos stand) {
        if (mc.world == null) return 0;

        Vec3d center = PlayerUtils.blockCenter(stand.pos());
        double r = ORB_RADIUS;
        Box box = new Box(center.x - r, center.y - r, center.z - r,
            center.x + r, center.y + r, center.z + r);

        int count = 0;
        for (ExperienceOrbEntity orb : mc.world.getEntitiesByClass(ExperienceOrbEntity.class, box, orb -> true)) {
            if (orb.isRemoved()) continue;
            if (orb.squaredDistanceTo(center) <= r * r) count++;
        }
        return count;
    }

    /**
     * 扫一遍：物品栏（快捷栏、背包、盔甲、副手）+ 末影箱（界面开着的时候）。
     *
     * <p>物品栏直接读 {@link PlayerInventory}：开没开容器界面、开的是哪个界面都不影响
     * （末影箱界面里是没有盔甲格和副手格的，按界面上的格子扫会漏掉它们）。
     *
     * <p>「能换到副手的」只有快捷栏第 2 格起的 8 格和背包 27 格
     * （第一位那把剑、盔甲、副手都不挪）；但是判断「还没修完」要连它们一起算 ——
     * 第一位和盔甲在装备栏里一样会被修，修完之前不该退出去。
     */
    private Scan scan(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();

        boolean needsRepair = false;
        int damageSum = 0;
        int repairable = 0;
        SlotKey candidate = null;

        for (int index = 0; index <= PlayerInventory.OFF_HAND_SLOT; index++) {
            ItemStack stack = inventory.getStack(index);
            if (!MendingItems.needsRepair(stack)) continue;

            needsRepair = true;
            repairable++;
            damageSum += MendingItems.damage(stack);

            // 只搬快捷栏第 2 格起、以及背包 27 格：
            // 第一位（常驻的剑）和盔甲（36~39）、副手（40）都不挪
            if (index == FIRST_SLOT || index >= InventoryUtils.MAIN_END) continue;
            if (candidate == null) candidate = new SlotKey(Side.PLAYER, index);
        }

        if (chestHandler != null && player.currentScreenHandler == chestHandler) {
            List<Slot> slots = InventoryUtils.containerSlots(chestHandler);
            for (int index = 0; index < slots.size(); index++) {
                ItemStack stack = slots.get(index).getStack();
                if (!MendingItems.needsRepair(stack)) continue;

                needsRepair = true;
                repairable++;
                damageSum += MendingItems.damage(stack);
                if (candidate == null) candidate = new SlotKey(Side.CONTAINER, index);
            }
        }

        return new Scan(needsRepair, damageSum, repairable, candidate);
    }

    /** 点一下「和副手交换」。 */
    private void performSwap(ClientPlayerEntity player) {
        Swap swap = swaps.poll();
        if (swap == null) return;

        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null) {
            fail("界面没了，没法交换");
            return;
        }

        Slot slot = resolve(handler, player, swap.slot());
        if (slot == null) {
            fail("找不到" + slotName(swap.slot()) + "（界面换了？）");
            return;
        }

        ItemStack before = player.getOffHandStack().copy();
        String action = swap.restore()
            ? "把 " + slotName(swap.slot()) + " 换回副手"
            : "把 " + slotName(swap.slot()) + " 换到副手";
        ContainerClicks.click(handler, slot, SWAP_OFFHAND_BUTTON, SlotActionType.SWAP, action);

        // 换进来的那一格从现在起就是临时物品格（下次修满了换回去）
        if (!swap.restore()) tempSlot = swap.slot();

        ItemStack after = player.getOffHandStack();
        if (ItemStack.areEqual(before, after)) {
            ChatUtils.debug("交换好像没生效（副手还是 %s）", InventoryUtils.describe(after));
        }
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private void exitLoop(String reason) {
        ChatUtils.info("mending：停止检测（" + reason + "）");
        lastResult = "收尾：" + reason;
        if (tempSlot != null) {
            swaps.add(new Swap(tempSlot, true));
            tempSlot = null;
        }
        stage = Stage.EXIT;
        settleTicks = 0;
    }

    private void tickExit(MinecraftClient mc, ClientPlayerEntity player) {
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (!swaps.isEmpty()) {
            performSwap(player);
            settleTicks = PlayerControllerConfig.getNetworkDelayTicks();
            return;
        }

        closeChest(mc);
        RotateManager.get().release(Owner.MENDING);
        restoreAttack();

        BlockPos target = returnPos;
        String dimension = returnDimension;
        if (target == null || dimension == null) {
            finish("完成（没记下出发的位置，就停在这里）");
            return;
        }

        String error = beginMove(target, dimension, Stage.MOVE_BACK);
        if (error != null) {
            finish("完成，但没能走回原位：" + error);
            return;
        }
        if (stage != Stage.MOVE_BACK) {
            finish("完成（你就在出发的位置）");
            return;
        }

        ChatUtils.info("mending：收尾了，走回出发的位置 " + target.toShortString());
    }

    private void tickMoveBack() {
        if (moveAction == null) {
            finish("完成（没有移动任务）");
            return;
        }
        if (!moveAction.isFinished()) return;

        String failure = moveAction.failureReason();
        moveAction = null;
        if (failure != null) {
            finish("完成，但走回原位失败：" + failure);
        } else {
            finish("完成，已回到出发的位置");
        }
    }

    private void finish(String message) {
        ChatUtils.info("mending：" + message);
        lastResult = message;
        clearRuntime();
    }

    /** 开了但还没真正开始（出发前就出错了）：静默收尾。 */
    private void finishSilently() {
        closeChest(MinecraftClient.getInstance());
        restoreAttack();
        clearRuntime();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 提交一个「走到这个位置」的任务。
     *
     * @return null = 交出去了（{@link #stage} 变成 {@code movingStage}）或者已经在那儿了（stage 不动）；
     * 否则是为什么走不了
     */
    @Nullable
    private String beginMove(BlockPos target, String dimension, Stage movingStage) {
        PathPlanner.Plan plan = PathPlanner.toPosition(target, dimension);
        if (!plan.ok()) return plan.error();
        if (plan.info() != null) ChatUtils.debug("mending：%s", plan.info());

        if (plan.alreadyThere()) {
            moveAction = null;
            return null;
        }

        PathMoveAction action = new PathMoveAction(plan.path());
        ActionManager.get().submit(action);
        moveAction = action;
        stage = movingStage;
        return null;
    }

    /** 到站立点了：开攻击、（设了的话）打开末影箱，开始检测。 */
    @Nullable
    private String arriveAtStand() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return "没有玩家";

        MendingPos stand = MendingConfig.get().standPoint();
        if (stand == null) return "站立点被删掉了";

        MendingPos chest = MendingConfig.get().enderChest();
        if (chest != null) {
            if (!chest.dimension().equals(stand.dimension())) {
                return "末影箱（" + chest.describe() + "）和站立点（" + stand.describe() + "）不在一个维度";
            }
            if (!PlayerUtils.isWithinReachFrom(stand.pos(), chest.pos())) {
                return "站在站立点上够不着末影箱（距离 "
                    + String.format("%.2f", PlayerUtils.eyeDistanceFrom(stand.pos(), chest.pos()))
                    + " > " + String.format("%.2f", PlayerUtils.reach()) + "）";
            }
            if (DimensionUtils.current().equals(chest.dimension())) {
                if (!(mc.world.getBlockState(chest.pos()).getBlock() instanceof EnderChestBlock)) {
                    return chest.describe() + " 不是末影箱（现在是 "
                        + ContainerTypes.idOf(mc.world.getBlockState(chest.pos())) + "）";
                }
            }
        }

        if (!AttackConfig.get().switchProfile(PROFILE)) {
            return "attack 里没有叫 " + PROFILE + " 的 profile（刚被删掉了？）";
        }
        attackTouched = true;
        setAttack(true, "到站立点了");

        waitTicks = 0;
        settleTicks = 0;
        lastDamageSum = -1;
        lastProgressTick = tickCount;

        if (chest == null) {
            stage = Stage.LOOP;
            ChatUtils.debug("mending：没设末影箱，直接开始检测");
            return null;
        }

        stage = Stage.OPEN_CHEST;
        openChest(mc, player, chest.pos());
        return null;
    }

    private void openChest(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        if (!suppressorHeld) {
            ScreenSuppressor.hold();
            suppressorHeld = true;
        }
        chestHandler = null;
        openWaitTicks = 0;
        ContainerOpener.open(mc, player, pos, Owner.MENDING);
        // 右键完了就把朝向还回去：不然 mending 一直占着「谁在转头」，攻击那边每 tick 都会被拒绝
        RotateManager.get().release(Owner.MENDING);
        ChatUtils.debug("mending：右键末影箱 %s，等界面", pos.toShortString());
    }

    private void closeChest(MinecraftClient mc) {
        if (chestHandler != null && mc.player != null && mc.player.currentScreenHandler == chestHandler) {
            mc.player.closeHandledScreen();
        }
        chestHandler = null;
        if (suppressorHeld) {
            ScreenSuppressor.unhold();
            suppressorHeld = false;
        }
    }

    /** 攻击开关（只在真的要变的时候才写盘）。 */
    private static void setAttack(boolean enable, String why) {
        AttackConfig config = AttackConfig.get();
        if (config.enable() == enable) return;
        config.setEnable(enable);
        ChatUtils.debug("mending：%s，攻击%s", why, enable ? "打开" : "关掉");
    }

    /** 还原出发前的 profile 和开关。 */
    private void restoreAttack() {
        if (!attackTouched) return;
        attackTouched = false;

        AttackConfig config = AttackConfig.get();
        if (!config.profileName().equals(attackProfileBefore)) {
            if (AttackProfiles.exists(attackProfileBefore)) {
                config.switchProfile(attackProfileBefore);
            } else {
                ChatUtils.debug("mending：原来的 profile（%s）没了，留着现在的", attackProfileBefore);
            }
        }
        if (config.enable() != attackEnableBefore) config.setEnable(attackEnableBefore);
    }

    private void clearRuntime() {
        stage = Stage.IDLE;
        tempSlot = null;
        swaps.clear();
        moveAction = null;
        chestHandler = null;
        openWaitTicks = 0;
        settleLeft = 0;
        settleTicks = 0;
        waitTicks = 0;
        lastDamageSum = -1;
        returnPos = null;
        returnDimension = null;
        if (suppressorHeld) {
            ScreenSuppressor.unhold();
            suppressorHeld = false;
        }
    }

    /** 把「哪边第几格」解析成现在这个界面里的槽位。 */
    @Nullable
    private static Slot resolve(ScreenHandler handler, ClientPlayerEntity player, SlotKey key) {
        if (key.side() == Side.PLAYER) {
            return InventoryUtils.inventorySlot(handler, player.getInventory(), key.index());
        }
        List<Slot> slots = InventoryUtils.containerSlots(handler);
        return key.index() >= 0 && key.index() < slots.size() ? slots.get(key.index()) : null;
    }

    private static String slotName(SlotKey key) {
        if (key.side() == Side.CONTAINER) return "末影箱第 " + (key.index() + 1) + " 格";
        int index = key.index();
        if (index < PlayerInventory.getHotbarSize()) return "快捷栏第 " + (index + 1) + " 格";
        if (index < InventoryUtils.MAIN_END) return "背包第 " + (index - InventoryUtils.MAIN_START + 1) + " 格";
        if (index < PlayerInventory.OFF_HAND_SLOT) return "盔甲第 " + (index - InventoryUtils.MAIN_END + 1) + " 格";
        return "副手";
    }
}
