package org.six_coin.playerController.client.mending;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
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
 * mending 的两个位置，记在 {@code config/player-controller/world_<n>/mend.json}：
 *
 * <pre>
 * {
 *   "stand_point": {"dimension": "minecraft:overworld", "x": 1, "y": 64, "z": 1},
 *   "ender_chest": {"dimension": "minecraft:overworld", "x": 2, "y": 64, "z": 1}
 * }
 * </pre>
 *
 * <p>{@code ender_chest} 可以不设（那就不打开末影箱，只看物品栏）。
 * 放 level 目录下是因为不同存档 / 服务器的工作站位置不一样，换世界（{@code /pc config level <n>}）会跟着换。
 */
public final class MendingConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String FILE_NAME = "mend.json";

    private static final MendingConfig INSTANCE = new MendingConfig();

    /** 盘上那一份（字段名就是文件里的键名）。 */
    private static final class Data {
        @SerializedName("stand_point")
        private MendingPos standPoint;

        @SerializedName("ender_chest")
        private MendingPos enderChest;
    }

    private Data data = new Data();
    private int loadedWorld = -1;
    private boolean loadedOk = false;

    private MendingConfig() {
    }

    public static MendingConfig get() {
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
            ChatUtils.debug("已创建 mending 配置：" + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            if (loaded != null) data = loaded;

            // 手改出来的坐标可能没有维度：那种坐标没法用，当作没设
            if (data.standPoint != null && isBlank(data.standPoint.dimension())) {
                ChatUtils.error(path + " 里的 stand_point 没有维度，当作没设");
                data.standPoint = null;
            }
            if (data.enderChest != null && isBlank(data.enderChest.dimension())) {
                ChatUtils.error(path + " 里的 ender_chest 没有维度，当作没设");
                data.enderChest = null;
            }

            loadedOk = true;
            ChatUtils.debug("已读取 mending 配置：" + path + "（站立点 "
                + (data.standPoint == null ? "未设置" : data.standPoint.describe())
                + "，末影箱 " + (data.enderChest == null ? "未设置" : data.enderChest.describe()) + "）");
        } catch (Exception e) {
            loadedOk = false;
            ChatUtils.error("读 " + path + " 失败：" + e.getMessage() + " —— 这一轮不会覆盖原文件");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public void switchWorld(int level) {
        save();
        load(level);
        ChatUtils.info("mending 配置已切到 world_" + level + "（站立点 "
            + (standPoint() == null ? "未设置" : standPoint().describe()) + "）");
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
        return file(loadedWorld < 0 ? PlayerControllerConfig.getWorld() : loadedWorld);
    }

    /** 站立点（没设是 null）。 */
    @Nullable
    public MendingPos standPoint() {
        return data.standPoint;
    }

    /** 末影箱（没设是 null，这时不打开末影箱）。 */
    @Nullable
    public MendingPos enderChest() {
        return data.enderChest;
    }

    public void setStandPoint(String dimension, BlockPos pos) {
        data.standPoint = MendingPos.of(dimension, pos);
        save();
    }

    public void setEnderChest(String dimension, BlockPos pos) {
        data.enderChest = MendingPos.of(dimension, pos);
        save();
    }

    /** 删掉末影箱（之后就不打开末影箱了）。 */
    public void clearEnderChest() {
        data.enderChest = null;
        save();
    }
}
