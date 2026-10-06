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
 *   "debug": true,
 *   "world": 1,
 *   "actions": {
 *     "move_speed": 3.0
 *   }
 * }
 * </pre>
 *
 * <p>{@code world} 是全局的世界配置编号，和游戏内的存档 / 服务器无关，
 * 路径点数据放在 {@code config/player-controller/world_<num>/waypoints.json}。
 */
public final class PlayerControllerConfig {

    /** 配置文件夹名（在 config 下单独隔一层，方便以后放别的文件）。 */
    public static final String FOLDER_NAME = "player-controller";
    private static final String FILE_NAME = "player-controller.json";

    /** 移动速度的合法范围（单位：方块 / tick）。 */
    public static final double MIN_MOVE_SPEED = 0.01;
    public static final double MAX_MOVE_SPEED = 64.0;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static PlayerControllerConfig instance = new PlayerControllerConfig();

    /** 调试开关。早期开发阶段默认打开，所有步骤都会输出到聊天栏。 */
    private boolean debug = true;

    /** 当前使用的世界配置编号，对应 world_{@code <num>} 目录。 */
    private int world = 1;

    private Actions actions = new Actions();

    private PlayerControllerConfig() {
    }

    /** 动作相关的参数。 */
    public static final class Actions {

        /** 移动速度，单位：方块 / tick（20 tick = 1 秒，所以 3 就是约 60 格/秒）。 */
        @SerializedName("move_speed")
        private double moveSpeed = 3.0;

        public double moveSpeed() {
            return moveSpeed;
        }

        public void moveSpeed(double value) {
            moveSpeed = Math.min(MAX_MOVE_SPEED, Math.max(MIN_MOVE_SPEED, value));
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

    /** 启动时调用：文件不存在就创建一份默认配置，存在就读取。 */
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
                + ", move_speed=" + instance.actions.moveSpeed() + ")");
        } catch (Exception e) {
            instance = new PlayerControllerConfig();
            ChatUtils.error("读取配置文件失败，已使用默认值: " + e.getMessage());
        }
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
