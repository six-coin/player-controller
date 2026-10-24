package org.six_coin.playerController.client.eat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 自动进食的开关，记在 {@code config/player-controller/world_<n>/eat.json}：
 *
 * <pre>
 * {"enabled": true}
 * </pre>
 *
 * <p>和攻击的开关一样按世界配置分开存：不同存档 / 服务器的习惯不一样，
 * {@code /pc config world <n>} 换世界时会自动换成那一份（见 {@link #switchWorld(int)}）。
 */
public final class EatConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String FILE_NAME = "eat.json";

    private static final EatConfig INSTANCE = new EatConfig();

    /** 盘上那一份。 */
    private static final class Data {
        @SerializedName("enabled")
        private boolean enabled = false;
    }

    private Data data = new Data();
    private int loadedWorld = -1;
    private boolean loadedOk = false;

    private EatConfig() {
    }

    public static EatConfig get() {
        return INSTANCE;
    }

    public static Path file(int level) {
        return WaypointManager.worldDir(level).resolve(FILE_NAME);
    }

    // ------------------------------------------------------------------
    // 读 / 写
    // ------------------------------------------------------------------

    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int level) {
        level = Math.max(1, level);
        loadedWorld = level;
        loadedOk = false;
        data = new Data();

        Path path = file(level);
        if (!Files.exists(path)) {
            loadedOk = true;
            save();
            ChatUtils.debug("已创建自动进食配置：" + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            if (loaded != null) data = loaded;
            loadedOk = true;
            ChatUtils.debug("已读取自动进食配置：" + path + "（enabled=" + data.enabled + "）");
        } catch (Exception e) {
            loadedOk = false;
            ChatUtils.error("读 " + path + " 失败：" + e.getMessage() + " —— 这一轮不会覆盖原文件");
        }
    }

    public void switchWorld(int level) {
        save();
        load(level);
        ChatUtils.info("自动进食配置已切到 world_" + level + "（" + (enabled() ? "开" : "关") + "）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        Path path = file(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存 " + path + " 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查 / 改
    // ------------------------------------------------------------------

    public int loadedWorld() {
        return loadedWorld;
    }

    public Path currentFile() {
        return file(loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld());
    }

    /** 自动进食开着没。 */
    public boolean enabled() {
        return data.enabled;
    }

    /** 开 / 关自动进食（立刻存盘）。 */
    public void setEnabled(boolean value) {
        data.enabled = value;
        save();
    }

    /** 反过来，返回改完之后的值。 */
    public boolean toggle() {
        setEnabled(!data.enabled);
        return data.enabled;
    }
}
