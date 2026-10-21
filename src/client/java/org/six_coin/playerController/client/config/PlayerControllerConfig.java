package org.six_coin.playerController.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置文件。
 *
 * <p>源代码放在 config 包下，运行时生成的配置文件位于
 * {@code <游戏运行目录>/config/player-controller/player-controller.json}。
 *
 * <pre>
 * {
 *   "debug": false,
 *   "world": 1,
 *   "actions": {
 *     "move_speed": 5.0,
 *     "max_descent_speed": 3.0,
 *     "interaction_range": 4.5,
 *     "attack_range": 4.5,
 *     "attack_wall_range": 4.5,
 *     "anti_kick_delay": 40,
 *     "anti_kick_off_time": 1,
 *     "network_delay_ticks": 2,
 *     "wait_ticks_after_dimension_teleport": 40,
 *     "mending_max_wait_exp_seconds": 30,
 *     "mending_detect_interval_ticks": 20,
 *     "mine_simple_range": 4.0,
 *     "mine_simple_wall_range": 4.0,
 *     "mine_simple_max_blocks_per_tick": 40,
 *     "mine_tool_switch_interval_ticks": 4,
 *     "mine_max_vertical_speed_blocks_per_tick": 0.08,
 *     "mine_max_horizontal_speed_blocks_per_tick": 0.0
 *   }
 * }
 * </pre>
 *
 * <p>{@code world} 是全局的世界配置编号，和游戏内的存档 / 服务器无关，
 * 路径点数据放在 {@code config/player-controller/world_<num>/waypoints.json}。
 *
 * <p>老版本（没有后三个键）的配置文件读进来时那三个键会取默认值，然后立刻回写一份补全的。
 */
public final class PlayerControllerConfig {

    /** 配置文件夹名（在 config 下单独隔一层，方便以后放别的文件）。 */
    public static final String FOLDER_NAME = "player-controller";
    private static final String FILE_NAME = "player-controller.json";

    /** 移动速度的合法范围（单位：方块 / tick）。 */
    public static final double MIN_MOVE_SPEED = 0.01;
    public static final double MAX_MOVE_SPEED = 64.0;

    /** 下降速度的默认值（单位：方块 / tick）。 */
    public static final double DEFAULT_MAX_DESCENT_SPEED = 3.0;

    /** 下降速度的合法范围（单位：方块 / tick）。 */
    public static final double MIN_MAX_DESCENT_SPEED = 0.01;
    public static final double MAX_MAX_DESCENT_SPEED = 64.0;

    /** 触及距离的默认值（和原版方块交互距离一样是 4.5 格）。 */
    public static final double DEFAULT_INTERACTION_RANGE = 4.5;

    /** 触及距离的合法范围（单位：方块）。 */
    public static final double MIN_INTERACTION_RANGE = 0.5;
    public static final double MAX_INTERACTION_RANGE = 64.0;

    /** 攻击范围的默认值（单位：方块），和 meteor 的 killaura 一样是 4.5。 */
    public static final double DEFAULT_ATTACK_RANGE = 4.5;

    /** 攻击范围的合法范围（单位：方块）。 */
    public static final double MIN_ATTACK_RANGE = 0.5;
    public static final double MAX_ATTACK_RANGE = 64.0;

    /**
     * 看不见目标时（隔着方块）还能打到的距离，默认 {@value #DEFAULT_ATTACK_WALL_RANGE}。
     *
     * <p>注意：和 meteor 的默认值（3.5）不一样，这里是 4.5。
     */
    public static final double DEFAULT_ATTACK_WALL_RANGE = 4.5;

    /** 穿墙攻击距离的合法范围（单位：方块）。 */
    public static final double MIN_ATTACK_WALL_RANGE = 0.5;
    public static final double MAX_ATTACK_WALL_RANGE = 64.0;

    /** 防踢：两次「往下补一点」之间的间隔（tick）。 */
    public static final int DEFAULT_ANTI_KICK_DELAY = 40;
    public static final int MIN_ANTI_KICK_DELAY = 1;
    public static final int MAX_ANTI_KICK_DELAY = 200;

    /** 防踢：「往下补」持续多少 tick。 */
    public static final int DEFAULT_ANTI_KICK_OFF_TIME = 1;
    public static final int MIN_ANTI_KICK_OFF_TIME = 1;
    public static final int MAX_ANTI_KICK_OFF_TIME = 20;

    /**
     * 等「服务端把数据同步回来」的默认 tick 数。
     *
     * <p>容器界面打开之后格子的内容才同步过来，一 tick 连点之后服务端也会把真实状态推回来，
     * 所以读服务端状态之前都要先等这么多 tick。
     */
    public static final int DEFAULT_NETWORK_DELAY_TICKS = 2;
    public static final int MIN_NETWORK_DELAY_TICKS = 1;
    public static final int MAX_NETWORK_DELAY_TICKS = 40;

    /**
     * 换维度（走传送门）之后先等多少 tick 再继续移动。
     *
     * <p>刚被传送过去时服务端那边的落点 / 区块还没同步好，这时候马上走很容易被拉回去（卡顿、回弹）。
     */
    public static final int DEFAULT_WAIT_TICKS_AFTER_DIMENSION_TELEPORT = 40;
    public static final int MIN_WAIT_TICKS_AFTER_DIMENSION_TELEPORT = 0;
    public static final int MAX_WAIT_TICKS_AFTER_DIMENSION_TELEPORT = 400;

    /**
     * mending：多久没有任何东西被修好就放弃，单位：秒（默认 {@value #DEFAULT_MENDING_MAX_WAIT_EXP_SECONDS}）。
     *
     * <p>经验修补是「捡到经验球才修」，所以这里等的其实是经验：连续这么多秒一件装备的耐久都没涨过，
     * 就不再干等（附近可能根本没有经验来源），退出检测走回原位。
     */
    public static final int DEFAULT_MENDING_MAX_WAIT_EXP_SECONDS = 30;
    public static final int MIN_MENDING_MAX_WAIT_EXP_SECONDS = 1;
    public static final int MAX_MENDING_MAX_WAIT_EXP_SECONDS = 600;

    /** mending：多久检测一次，单位：tick（默认 {@value #DEFAULT_MENDING_DETECT_INTERVAL_TICKS} = 1 秒）。 */
    public static final int DEFAULT_MENDING_DETECT_INTERVAL_TICKS = 20;
    public static final int MIN_MENDING_DETECT_INTERVAL_TICKS = 1;
    public static final int MAX_MENDING_DETECT_INTERVAL_TICKS = 1200;

    /** 挖掘：看得见的方块能挖多远（单位：方块，默认 {@value #DEFAULT_MINE_SIMPLE_RANGE}）。 */
    public static final double DEFAULT_MINE_SIMPLE_RANGE = 4.0;
    public static final double MIN_MINE_SIMPLE_RANGE = 0.5;
    public static final double MAX_MINE_SIMPLE_RANGE = 64.0;

    /** 挖掘：被别的方块挡住的时候还能挖多远（单位：方块，默认 {@value #DEFAULT_MINE_SIMPLE_WALL_RANGE}）。 */
    public static final double DEFAULT_MINE_SIMPLE_WALL_RANGE = 4.0;
    public static final double MIN_MINE_SIMPLE_WALL_RANGE = 0.5;
    public static final double MAX_MINE_SIMPLE_WALL_RANGE = 64.0;

    /** 挖掘：一个 tick 最多挖几个方块（默认 {@value #DEFAULT_MINE_SIMPLE_MAX_BLOCKS_PER_TICK}）。 */
    public static final int DEFAULT_MINE_SIMPLE_MAX_BLOCKS_PER_TICK = 40;
    public static final int MIN_MINE_SIMPLE_MAX_BLOCKS_PER_TICK = 1;
    public static final int MAX_MINE_SIMPLE_MAX_BLOCKS_PER_TICK = 200;

    /**
     * 挖掘：换完工具之后要空多少 tick 才继续动手（默认
     * {@value #DEFAULT_MINE_TOOL_SWITCH_INTERVAL_TICKS}）。
     *
     * <p>换工具那一 tick 连着后面这些 tick 都不发包，让服务端那边的选中格先稳定下来。
     */
    public static final int DEFAULT_MINE_TOOL_SWITCH_INTERVAL_TICKS = 4;
    public static final int MIN_MINE_TOOL_SWITCH_INTERVAL_TICKS = 0;
    public static final int MAX_MINE_TOOL_SWITCH_INTERVAL_TICKS = 200;

    /**
     * 挖掘：纵向速度上限（格 / tick，默认 {@value #DEFAULT_MINE_MAX_VERTICAL_SPEED_PER_TICK}）。
     *
     * <p>纵向速度的绝对值**不超过**这个值的时候才动手：掉得太快 / 升得太快先暂停。
     * 0 表示要求纵向速度正好是 0。
     */
    public static final double DEFAULT_MINE_MAX_VERTICAL_SPEED_PER_TICK = 0.08;
    public static final double MIN_MINE_MAX_VERTICAL_SPEED_PER_TICK = 0.0;
    public static final double MAX_MINE_MAX_VERTICAL_SPEED_PER_TICK = 64.0;

    /**
     * 挖掘：水平速度上限（格 / tick，默认 {@value #DEFAULT_MINE_MAX_HORIZONTAL_SPEED_PER_TICK}）。
     *
     * <p>x、z 两个方向的水平速度要**分别**不超过这个值才动手（不是看合速度）：
     * 默认 0 就是要求两个方向的速度都正好是 0（完全站定）。
     */
    public static final double DEFAULT_MINE_MAX_HORIZONTAL_SPEED_PER_TICK = 0.0;
    public static final double MIN_MINE_MAX_HORIZONTAL_SPEED_PER_TICK = 0.0;
    public static final double MAX_MINE_MAX_HORIZONTAL_SPEED_PER_TICK = 64.0;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static PlayerControllerConfig instance = new PlayerControllerConfig();

    /** 调试开关。 */
    private boolean debug = false;

    /** 当前使用的世界配置编号，对应 world_{@code <num>} 目录。 */
    private int world = 1;

    private Actions actions = new Actions();

    private PlayerControllerConfig() {
    }

    /** 动作相关的参数。 */
    public static final class Actions {

        /** 移动速度，单位：方块 / tick（20 tick = 1 秒，所以 5 就是约 100 格/秒）。 */
        @SerializedName("move_speed")
        private double moveSpeed = 5.0;

        /**
         * 下降速度上限，单位：方块 / tick。
         *
         * <p>往下走的时候实际速度取 {@code min(move_speed, max_descent_speed)}。
         * 原版对「下降速度超过理论最大值」是会算伤害的（跟有没有 NoFall 无关），
         * 所以下降不能跟平移一样快。
         */
        @SerializedName("max_descent_speed")
        private double maxDescentSpeed = DEFAULT_MAX_DESCENT_SPEED;

        /**
         * 触及距离，单位：方块。
         *
         * <p>用来判断方块在不在「够得着」的范围内（例如 {@code /pc container} 打开容器、
         * {@code /pc waypoint del_waypoint target} 取准星看到的方块）。默认值和原版一样是 4.5。
         *
         * <p>注意：这只是本模组自己的判断标准；服务端还有它自己的一套距离检查，
         * 调大之后对服务端不认的交互依然会被服务端拒绝。
         */
        @SerializedName("interaction_range")
        private double interactionRange = DEFAULT_INTERACTION_RANGE;

        /**
         * 攻击范围，单位：方块（{@code /pc attack} 用）。
         *
         * <p>默认 4.5，和 meteor 的 killaura 一样。
         */
        @SerializedName("attack_range")
        private double attackRange = DEFAULT_ATTACK_RANGE;

        /**
         * 看不见目标时还能打到的距离，单位：方块。
         *
         * <p>挡着的时候要求近一点；默认 4.5（和 meteor 的 3.5 不一样）。
         */
        @SerializedName("attack_wall_range")
        private double attackWallRange = DEFAULT_ATTACK_WALL_RANGE;

        /**
         * 防踢：两次「往下补一点」之间隔多少 tick。
         *
         * <p>见 {@code feature/AntiKick}：飞行（悬停）期间每隔这么多 tick 往服务端补一个
         * 「比上一包低 0.0313 格」的位置包，免得服务端按「浮空超过 80 tick」把人踢了。
         */
        @SerializedName("anti_kick_delay")
        private int antiKickDelay = DEFAULT_ANTI_KICK_DELAY;

        /** 防踢：「往下补」持续多少 tick（1 就是补一 tick）。 */
        @SerializedName("anti_kick_off_time")
        private int antiKickOffTime = DEFAULT_ANTI_KICK_OFF_TIME;

        /**
         * 等「服务端把数据同步回来」的 tick 数（默认 {@value #DEFAULT_NETWORK_DELAY_TICKS}）。
         *
         * <p>凡是「点完/开完界面之后要读服务端的状态」的地方都先等这么多 tick：
         * <ul>
         *   <li>容器界面刚打开时，格子的内容是**之后**才同步过来的 —— 不等就可能读到一箱子空气；</li>
         *   <li>一 tick 连点很多下之后，服务端会把它**实际**接受到的状态推回来覆盖客户端的本地预测，
         *       不等就会拿着过期的数字继续算（搬了多少、格子里有几个）。</li>
         * </ul>
         *
         * <p>网络不好（延迟高、丢包）就把这个调大；单机 / 局域网可以调小。
         * 合法范围 {@value #MIN_NETWORK_DELAY_TICKS}~{@value #MAX_NETWORK_DELAY_TICKS}。
         */
        @SerializedName("network_delay_ticks")
        private int networkDelayTicks = DEFAULT_NETWORK_DELAY_TICKS;

        /**
         * 换维度（走传送门）之后先等多少 tick 再继续移动。
         *
         * <p>见 {@code feature/DimensionWait}：刚传送过去时服务端还没把落点 / 区块同步好，
         * 这时候马上走容易被拉回去。
         */
        @SerializedName("wait_ticks_after_dimension_teleport")
        private int waitTicksAfterDimensionTeleport = DEFAULT_WAIT_TICKS_AFTER_DIMENSION_TELEPORT;

        /**
         * mending：多久没有任何东西被修好就放弃，单位：秒。
         *
         * <p>见 {@code mending/MendingManager}：检测循环里连续这么多秒所有装备的耐久都没涨过，
         * 就判定「附近没有经验来源」，退出检测走回原位。
         */
        @SerializedName("mending_max_wait_exp_seconds")
        private int mendingMaxWaitExpSeconds = DEFAULT_MENDING_MAX_WAIT_EXP_SECONDS;

        /** mending：多久检测一次，单位：tick。 */
        @SerializedName("mending_detect_interval_ticks")
        private int mendingDetectIntervalTicks = DEFAULT_MENDING_DETECT_INTERVAL_TICKS;

        /**
         * 挖掘：看得见的方块能挖多远，单位：方块。
         *
         * <p>见 {@code mine/MineManager}：{@code /pc mine simple} 扫方块时，
         * 眼睛到方块中心这条线没被挡住就按这个距离算。
         */
        @SerializedName("mine_simple_range")
        private double mineSimpleRange = DEFAULT_MINE_SIMPLE_RANGE;

        /**
         * 挖掘：被别的方块挡住的时候还能挖多远，单位：方块。
         *
         * <p>和 meteor 的 nuker 一样分「range」和「walls range」两条：
         * 挡着的方块要求近一点（服务端那边只认距离，看不见也能挖到）。
         */
        @SerializedName("mine_simple_wall_range")
        private double mineSimpleWallRange = DEFAULT_MINE_SIMPLE_WALL_RANGE;

        /** 挖掘：一个 tick 最多挖几个方块（发包挖，数字越大越快）。 */
        @SerializedName("mine_simple_max_blocks_per_tick")
        private int mineSimpleMaxBlocksPerTick = DEFAULT_MINE_SIMPLE_MAX_BLOCKS_PER_TICK;

        /**
         * 挖掘：换完工具之后空多少 tick 再动手。
         *
         * <p>换工具那一 tick 和后面这几个 tick 都只切快捷栏、不发破坏包，
         * 免得服务端还没把选中格换过来就收到挖掘请求。
         */
        @SerializedName("mine_tool_switch_interval_ticks")
        private int mineToolSwitchIntervalTicks = DEFAULT_MINE_TOOL_SWITCH_INTERVAL_TICKS;

        /**
         * 挖掘：纵向速度上限（格 / tick）。
         *
         * <p>纵向速度的绝对值不超过它才动手；0 表示要求纵向速度正好是 0。
         */
        @SerializedName("mine_max_vertical_speed_blocks_per_tick")
        private double mineMaxVerticalSpeedPerTick = DEFAULT_MINE_MAX_VERTICAL_SPEED_PER_TICK;

        /**
         * 挖掘：水平速度上限（格 / tick）。
         *
         * <p>x、z 两个方向的水平速度要分别不超过它才动手（不看合速度）。
         */
        @SerializedName("mine_max_horizontal_speed_blocks_per_tick")
        private double mineMaxHorizontalSpeedPerTick = DEFAULT_MINE_MAX_HORIZONTAL_SPEED_PER_TICK;

        public double moveSpeed() {
            return moveSpeed;
        }

        public void moveSpeed(double value) {
            moveSpeed = Math.min(MAX_MOVE_SPEED, Math.max(MIN_MOVE_SPEED, value));
        }

        public double maxDescentSpeed() {
            return maxDescentSpeed;
        }

        public void maxDescentSpeed(double value) {
            maxDescentSpeed = Math.min(MAX_MAX_DESCENT_SPEED,
                Math.max(MIN_MAX_DESCENT_SPEED, value));
        }

        public double interactionRange() {
            return interactionRange;
        }

        public void interactionRange(double value) {
            interactionRange = Math.min(MAX_INTERACTION_RANGE,
                Math.max(MIN_INTERACTION_RANGE, value));
        }

        public double attackRange() {
            return attackRange;
        }

        public void attackRange(double value) {
            attackRange = Math.min(MAX_ATTACK_RANGE, Math.max(MIN_ATTACK_RANGE, value));
        }

        public double attackWallRange() {
            return attackWallRange;
        }

        public void attackWallRange(double value) {
            attackWallRange = Math.min(MAX_ATTACK_WALL_RANGE, Math.max(MIN_ATTACK_WALL_RANGE, value));
        }

        public int antiKickDelay() {
            return antiKickDelay;
        }

        public void antiKickDelay(int value) {
            antiKickDelay = Math.min(MAX_ANTI_KICK_DELAY, Math.max(MIN_ANTI_KICK_DELAY, value));
        }

        public int antiKickOffTime() {
            return antiKickOffTime;
        }

        public void antiKickOffTime(int value) {
            antiKickOffTime = Math.min(MAX_ANTI_KICK_OFF_TIME,
                Math.max(MIN_ANTI_KICK_OFF_TIME, value));
        }

        public int networkDelayTicks() {
            return networkDelayTicks;
        }

        public void networkDelayTicks(int value) {
            networkDelayTicks = Math.min(MAX_NETWORK_DELAY_TICKS,
                Math.max(MIN_NETWORK_DELAY_TICKS, value));
        }

        public int waitTicksAfterDimensionTeleport() {
            return waitTicksAfterDimensionTeleport;
        }

        public void waitTicksAfterDimensionTeleport(int value) {
            waitTicksAfterDimensionTeleport = Math.min(MAX_WAIT_TICKS_AFTER_DIMENSION_TELEPORT,
                Math.max(MIN_WAIT_TICKS_AFTER_DIMENSION_TELEPORT, value));
        }

        public int mendingMaxWaitExpSeconds() {
            return mendingMaxWaitExpSeconds;
        }

        public void mendingMaxWaitExpSeconds(int value) {
            mendingMaxWaitExpSeconds = Math.min(MAX_MENDING_MAX_WAIT_EXP_SECONDS,
                Math.max(MIN_MENDING_MAX_WAIT_EXP_SECONDS, value));
        }

        public int mendingDetectIntervalTicks() {
            return mendingDetectIntervalTicks;
        }

        public void mendingDetectIntervalTicks(int value) {
            mendingDetectIntervalTicks = Math.min(MAX_MENDING_DETECT_INTERVAL_TICKS,
                Math.max(MIN_MENDING_DETECT_INTERVAL_TICKS, value));
        }

        public double mineSimpleRange() {
            return mineSimpleRange;
        }

        public void mineSimpleRange(double value) {
            mineSimpleRange = Math.min(MAX_MINE_SIMPLE_RANGE, Math.max(MIN_MINE_SIMPLE_RANGE, value));
        }

        public double mineSimpleWallRange() {
            return mineSimpleWallRange;
        }

        public void mineSimpleWallRange(double value) {
            mineSimpleWallRange = Math.min(MAX_MINE_SIMPLE_WALL_RANGE,
                Math.max(MIN_MINE_SIMPLE_WALL_RANGE, value));
        }

        public int mineSimpleMaxBlocksPerTick() {
            return mineSimpleMaxBlocksPerTick;
        }

        public void mineSimpleMaxBlocksPerTick(int value) {
            mineSimpleMaxBlocksPerTick = Math.min(MAX_MINE_SIMPLE_MAX_BLOCKS_PER_TICK,
                Math.max(MIN_MINE_SIMPLE_MAX_BLOCKS_PER_TICK, value));
        }

        public int mineToolSwitchIntervalTicks() {
            return mineToolSwitchIntervalTicks;
        }

        public void mineToolSwitchIntervalTicks(int value) {
            mineToolSwitchIntervalTicks = Math.min(MAX_MINE_TOOL_SWITCH_INTERVAL_TICKS,
                Math.max(MIN_MINE_TOOL_SWITCH_INTERVAL_TICKS, value));
        }

        public double mineMaxVerticalSpeedPerTick() {
            return mineMaxVerticalSpeedPerTick;
        }

        public void mineMaxVerticalSpeedPerTick(double value) {
            mineMaxVerticalSpeedPerTick = Math.min(MAX_MINE_MAX_VERTICAL_SPEED_PER_TICK,
                Math.max(MIN_MINE_MAX_VERTICAL_SPEED_PER_TICK, value));
        }

        public double mineMaxHorizontalSpeedPerTick() {
            return mineMaxHorizontalSpeedPerTick;
        }

        public void mineMaxHorizontalSpeedPerTick(double value) {
            mineMaxHorizontalSpeedPerTick = Math.min(MAX_MINE_MAX_HORIZONTAL_SPEED_PER_TICK,
                Math.max(MIN_MINE_MAX_HORIZONTAL_SPEED_PER_TICK, value));
        }
    }

    // ------------------------------------------------------------------
    // 访问
    // ------------------------------------------------------------------

    public static PlayerControllerConfig get() {
        return instance;
    }

    public static boolean isDebug() {
        return instance.debug;
    }

    public static void setDebug(boolean value) {
        instance.debug = value;
        instance.save();
    }

    public static int getWorld() {
        return instance.world;
    }

    public static void setWorld(int value) {
        instance.world = Math.max(1, value);
        instance.save();
    }

    public static double getMoveSpeed() {
        return instance.actions.moveSpeed();
    }

    public static void setMoveSpeed(double value) {
        instance.actions.moveSpeed(value);
        instance.save();
    }

    /** 下降速度上限（方块 / tick）。 */
    public static double getMaxDescentSpeed() {
        return instance.actions.maxDescentSpeed();
    }

    public static void setMaxDescentSpeed(double value) {
        instance.actions.maxDescentSpeed(value);
        instance.save();
    }

    public static double getInteractionRange() {
        return instance.actions.interactionRange();
    }

    public static void setInteractionRange(double value) {
        instance.actions.interactionRange(value);
        instance.save();
    }

    /** 攻击范围（方块），{@code /pc attack} 用。 */
    public static double getAttackRange() {
        return instance.actions.attackRange();
    }

    public static void setAttackRange(double value) {
        instance.actions.attackRange(value);
        instance.save();
    }

    /** 看不见目标时还能打到的距离（方块）。 */
    public static double getAttackWallRange() {
        return instance.actions.attackWallRange();
    }

    public static void setAttackWallRange(double value) {
        instance.actions.attackWallRange(value);
        instance.save();
    }

    /** 防踢：两次「往下补一点」之间隔多少 tick。 */
    public static int getAntiKickDelay() {
        return instance.actions.antiKickDelay();
    }

    public static void setAntiKickDelay(int value) {
        instance.actions.antiKickDelay(value);
        instance.save();
    }

    /** 防踢：「往下补」持续多少 tick。 */
    public static int getAntiKickOffTime() {
        return instance.actions.antiKickOffTime();
    }

    public static void setAntiKickOffTime(int value) {
        instance.actions.antiKickOffTime(value);
        instance.save();
    }

    /** 等「服务端把数据同步回来」的 tick 数。 */
    public static int getNetworkDelayTicks() {
        return instance.actions.networkDelayTicks();
    }

    public static void setNetworkDelayTicks(int value) {
        instance.actions.networkDelayTicks(value);
        instance.save();
    }

    /** 换维度之后先等多少 tick 再继续移动。 */
    public static int getWaitTicksAfterDimensionTeleport() {
        return instance.actions.waitTicksAfterDimensionTeleport();
    }

    public static void setWaitTicksAfterDimensionTeleport(int value) {
        instance.actions.waitTicksAfterDimensionTeleport(value);
        instance.save();
    }

    /** mending：多久没有任何东西被修好就退出（秒）。 */
    public static int getMendingMaxWaitExpSeconds() {
        return instance.actions.mendingMaxWaitExpSeconds();
    }

    public static void setMendingMaxWaitExpSeconds(int value) {
        instance.actions.mendingMaxWaitExpSeconds(value);
        instance.save();
    }

    /** mending：检测间隔（tick）。 */
    public static int getMendingDetectIntervalTicks() {
        return instance.actions.mendingDetectIntervalTicks();
    }

    public static void setMendingDetectIntervalTicks(int value) {
        instance.actions.mendingDetectIntervalTicks(value);
        instance.save();
    }

    /** 挖掘：看得见的方块能挖多远（格）。 */
    public static double getMineSimpleRange() {
        return instance.actions.mineSimpleRange();
    }

    public static void setMineSimpleRange(double value) {
        instance.actions.mineSimpleRange(value);
        instance.save();
    }

    /** 挖掘：被挡住的时候还能挖多远（格）。 */
    public static double getMineSimpleWallRange() {
        return instance.actions.mineSimpleWallRange();
    }

    public static void setMineSimpleWallRange(double value) {
        instance.actions.mineSimpleWallRange(value);
        instance.save();
    }

    /** 挖掘：一个 tick 最多挖几个方块。 */
    public static int getMineSimpleMaxBlocksPerTick() {
        return instance.actions.mineSimpleMaxBlocksPerTick();
    }

    public static void setMineSimpleMaxBlocksPerTick(int value) {
        instance.actions.mineSimpleMaxBlocksPerTick(value);
        instance.save();
    }

    /** 挖掘：换完工具之后空多少 tick 再动手。 */
    public static int getMineToolSwitchIntervalTicks() {
        return instance.actions.mineToolSwitchIntervalTicks();
    }

    public static void setMineToolSwitchIntervalTicks(int value) {
        instance.actions.mineToolSwitchIntervalTicks(value);
        instance.save();
    }

    /** 挖掘：纵向速度上限（格 / tick）。 */
    public static double getMineMaxVerticalSpeedPerTick() {
        return instance.actions.mineMaxVerticalSpeedPerTick();
    }

    public static void setMineMaxVerticalSpeedPerTick(double value) {
        instance.actions.mineMaxVerticalSpeedPerTick(value);
        instance.save();
    }

    /** 挖掘：水平速度上限（格 / tick），x、z 两个方向分别判。 */
    public static double getMineMaxHorizontalSpeedPerTick() {
        return instance.actions.mineMaxHorizontalSpeedPerTick();
    }

    public static void setMineMaxHorizontalSpeedPerTick(double value) {
        instance.actions.mineMaxHorizontalSpeedPerTick(value);
        instance.save();
    }

    /** 配置目录：config/player-controller/ */
    public static Path configDir() {
        return FabricLoader.getInstance()
            .getConfigDir()
            .resolve(FOLDER_NAME);
    }

    public static Path configFile() {
        return configDir().resolve(FILE_NAME);
    }

    // ------------------------------------------------------------------
    // 读 / 写
    // ------------------------------------------------------------------

    /** 启动时调用：文件不存在就创建一份默认配置，存在就读取（读完回写一份，补上新增的键）。 */
    public static void load() {
        Path path = configFile();
        if (!Files.exists(path)) {
            instance = new PlayerControllerConfig();
            instance.save();
            ChatUtils.debug("已创建默认配置文件: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PlayerControllerConfig loaded = GSON.fromJson(reader, PlayerControllerConfig.class);
            if (loaded == null) {
                loaded = new PlayerControllerConfig();
                ChatUtils.error("配置文件内容为空，已使用默认值: " + path);
            } else if (loaded.actions == null) {
                loaded.actions = new Actions();
            }
            instance = loaded;
            ChatUtils.debug("已读取配置文件: " + path + " (debug=" + instance.debug
                + ", world=" + instance.world
                + ", move_speed=" + instance.actions.moveSpeed()
                + ", max_descent_speed=" + instance.actions.maxDescentSpeed()
                + ", interaction_range=" + instance.actions.interactionRange()
                + ", attack_range=" + instance.actions.attackRange()
                + ", attack_wall_range=" + instance.actions.attackWallRange()
                + ", anti_kick_delay=" + instance.actions.antiKickDelay()
                + ", anti_kick_off_time=" + instance.actions.antiKickOffTime()
                + ", network_delay_ticks=" + instance.actions.networkDelayTicks()
                + ", wait_ticks_after_dimension_teleport="
                + instance.actions.waitTicksAfterDimensionTeleport()
                + ", mending_max_wait_exp_seconds=" + instance.actions.mendingMaxWaitExpSeconds()
                + ", mending_detect_interval_ticks=" + instance.actions.mendingDetectIntervalTicks()
                + ", mine_simple_range=" + instance.actions.mineSimpleRange()
                + ", mine_simple_wall_range=" + instance.actions.mineSimpleWallRange()
                + ", mine_simple_max_blocks_per_tick=" + instance.actions.mineSimpleMaxBlocksPerTick()
                + ", mine_tool_switch_interval_ticks=" + instance.actions.mineToolSwitchIntervalTicks()
                + ", mine_max_vertical_speed_blocks_per_tick="
                + instance.actions.mineMaxVerticalSpeedPerTick()
                + ", mine_max_horizontal_speed_blocks_per_tick="
                + instance.actions.mineMaxHorizontalSpeedPerTick() + ")");
        } catch (Exception e) {
            instance = new PlayerControllerConfig();
            ChatUtils.error("读取配置文件失败，已使用默认值: " + e.getMessage());
        }

        // 回写一份：老配置文件里没有的键（比如后加的防踢参数）就能在文件里看到默认值了
        instance.save();
    }

    /** 写回配置文件（目录不存在会自动创建）。 */
    public void save() {
        Path path = configFile();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存配置文件失败: " + e.getMessage());
        }
    }
}
