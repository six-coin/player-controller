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
 *     "anti_kick_delay": 40,
 *     "anti_kick_off_time": 1,
 *     "items_per_tick": 64,
 *     "wait_ticks_after_dimension_teleport": 40
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

    /** 防踢：两次「往下补一点」之间的间隔（tick）。 */
    public static final int DEFAULT_ANTI_KICK_DELAY = 40;
    public static final int MIN_ANTI_KICK_DELAY = 1;
    public static final int MAX_ANTI_KICK_DELAY = 200;

    /** 防踢：「往下补」持续多少 tick。 */
    public static final int DEFAULT_ANTI_KICK_OFF_TIME = 1;
    public static final int MIN_ANTI_KICK_OFF_TIME = 1;
    public static final int MAX_ANTI_KICK_OFF_TIME = 20;

    /** 一个 tick 里最多往一个格子里放几个（「一个一个放」的批量上限）。 */
    public static final int DEFAULT_ITEMS_PER_TICK = 64;
    public static final int MIN_ITEMS_PER_TICK = 1;
    public static final int MAX_ITEMS_PER_TICK = 64;

    /**
     * 换维度（走传送门）之后先等多少 tick 再继续移动。
     *
     * <p>刚被传送过去时服务端那边的落点 / 区块还没同步好，这时候马上走很容易被拉回去（卡顿、回弹）。
     */
    public static final int DEFAULT_WAIT_TICKS_AFTER_DIMENSION_TELEPORT = 40;
    public static final int MIN_WAIT_TICKS_AFTER_DIMENSION_TELEPORT = 0;
    public static final int MAX_WAIT_TICKS_AFTER_DIMENSION_TELEPORT = 400;

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
         * {@code /pc waypoints del_waypoint target} 取准星看到的方块）。默认值和原版一样是 4.5。
         *
         * <p>注意：这只是本模组自己的判断标准；服务端还有它自己的一套距离检查，
         * 调大之后对服务端不认的交互依然会被服务端拒绝。
         */
        @SerializedName("interaction_range")
        private double interactionRange = DEFAULT_INTERACTION_RANGE;

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
         * 一个 tick 里最多往一个格子里放几个。
         *
         * <p>「一个一个放」的那些步骤（往合成格子里摆材料、右键往背包格放物品）一 tick 可以连点好几下，
         * 因为客户端点击是**本地预测、同步生效**的，没必要点一下等一个 tick。
         * 服务端要是少接受了几下，下一 tick 会按「光标少了多少」自己纠正。
         *
         * <p>合法范围 {@value #MIN_ITEMS_PER_TICK}~{@value #MAX_ITEMS_PER_TICK}，默认
         * {@value #DEFAULT_ITEMS_PER_TICK}。
         */
        @SerializedName("items_per_tick")
        private int itemsPerTick = DEFAULT_ITEMS_PER_TICK;

        /**
         * 换维度（走传送门）之后先等多少 tick 再继续移动。
         *
         * <p>见 {@code feature/DimensionWait}：刚传送过去时服务端还没把落点 / 区块同步好，
         * 这时候马上走容易被拉回去。
         */
        @SerializedName("wait_ticks_after_dimension_teleport")
        private int waitTicksAfterDimensionTeleport = DEFAULT_WAIT_TICKS_AFTER_DIMENSION_TELEPORT;

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

        public int itemsPerTick() {
            return itemsPerTick;
        }

        public void itemsPerTick(int value) {
            itemsPerTick = Math.min(MAX_ITEMS_PER_TICK, Math.max(MIN_ITEMS_PER_TICK, value));
        }

        public int waitTicksAfterDimensionTeleport() {
            return waitTicksAfterDimensionTeleport;
        }

        public void waitTicksAfterDimensionTeleport(int value) {
            waitTicksAfterDimensionTeleport = Math.min(MAX_WAIT_TICKS_AFTER_DIMENSION_TELEPORT,
                Math.max(MIN_WAIT_TICKS_AFTER_DIMENSION_TELEPORT, value));
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

    /** 一个 tick 里最多往一个格子里放几个。 */
    public static int getItemsPerTick() {
        return instance.actions.itemsPerTick();
    }

    public static void setItemsPerTick(int value) {
        instance.actions.itemsPerTick(value);
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
                + ", anti_kick_delay=" + instance.actions.antiKickDelay()
                + ", anti_kick_off_time=" + instance.actions.antiKickOffTime()
                + ", items_per_tick=" + instance.actions.itemsPerTick()
                + ", wait_ticks_after_dimension_teleport="
                + instance.actions.waitTicksAfterDimensionTeleport() + ")");
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
