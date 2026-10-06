package org.six_coin.playerController.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 *   "debug": true
 * }
 * </pre>
 */
public final class PlayerControllerConfig {

    /** 配置文件夹名（在 config 下单独隔一层，方便以后放别的文件）。 */
    private static final String FOLDER_NAME = "player-controller";
    private static final String FILE_NAME = "player-controller.json";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static PlayerControllerConfig instance = new PlayerControllerConfig();

    /**
     * 调试开关。早期开发阶段默认打开，所有步骤都会输出到聊天栏。
     */
    private boolean debug = true;

    private PlayerControllerConfig() {
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

    public static Path configFile() {
        return FabricLoader.getInstance()
            .getConfigDir()
            .resolve(FOLDER_NAME)
            .resolve(FILE_NAME);
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
                // 空文件 / 内容为 null，回退到默认值
                loaded = new PlayerControllerConfig();
                ChatUtils.error("配置文件内容为空，已使用默认值: " + path);
            }
            instance = loaded;
            ChatUtils.debug("已读取配置文件: " + path + " (debug=" + instance.debug + ")");
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
