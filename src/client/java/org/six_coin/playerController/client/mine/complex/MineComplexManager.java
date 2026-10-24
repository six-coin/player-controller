package org.six_coin.playerController.client.mine.complex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 复杂挖掘任务的清单 + simple 的缓存：{@code config/player-controller/world_<n>/mine.json}。
 *
 * <pre>
 * world_&lt;n&gt;/
 *   mine.json                ← 本类管（任务清单 + simple 缓存），和 waypoints.json 并排
 *   mine/
 *     station.json           ← 挖掘站（MineStationManager）
 *     check.json             ← 挖掘站检查报告
 *     &lt;task&gt;/
 *       region.json          ← 任务范围 + 信标（MineTaskRegion）
 * </pre>
 *
 * <p>任务名只允许 {@code [A-Za-z0-9_-]}（1~64 个字符）：它会直接变成目录名，
 * 不做限制的话 {@code ../} 这种东西能把删除操作带到别的地方去。
 */
public final class MineComplexManager {

    private static final String FILE_NAME = "mine.json";
    private static final String REGION_FILE = "region.json";

    /** 任务名：字母 / 数字 / 下划线 / 减号，1~64 个字符。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final MineComplexManager INSTANCE = new MineComplexManager();

    private MineComplexData data = new MineComplexData();
    private int loadedWorld = -1;
    private boolean loadedOk = false;

    private MineComplexManager() {
    }

    public static MineComplexManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    /** 挖掘数据目录：{@code world_<n>/mine/}（任务目录和挖掘站文件都在这里）。 */
    public static Path mineDir(int level) {
        return WaypointManager.worldDir(level).resolve("mine");
    }

    /** 任务清单 + simple 缓存：{@code world_<n>/mine.json}（和 waypoints.json 并排）。 */
    public static Path file(int level) {
        return WaypointManager.worldDir(level).resolve(FILE_NAME);
    }

    /** 一个任务的目录：{@code world_<n>/mine/<task>/}。 */
    public static Path taskDir(int level, String name) {
        return mineDir(level).resolve(name);
    }

    /** 一个任务的区域文件：{@code world_<n>/mine/<task>/region.json}。 */
    public static Path regionFile(int level, String name) {
        return taskDir(level, name).resolve(REGION_FILE);
    }

    private int level() {
        return loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld();
    }

    public Path currentFile() {
        return file(level());
    }

    public Path currentTaskDir(String name) {
        return taskDir(level(), name);
    }

    public Path currentRegionFile(String name) {
        return regionFile(level(), name);
    }

    public int loadedWorld() {
        return loadedWorld;
    }

    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int level) {
        level = Math.max(1, level);
        loadedWorld = level;
        loadedOk = false;
        data = new MineComplexData();

        Path path = file(level);
        if (!Files.exists(path)) {
            loadedOk = true;
            ChatUtils.debug("world_" + level + " 还没有 mine.json，用空的: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            MineComplexData loaded = GSON.fromJson(reader, MineComplexData.class);
            if (loaded != null) data = loaded;
            loadedOk = true;
            ChatUtils.debug("已加载 world_" + level + " 的 mine.json：任务 " + data.complex().size()
                + " 个，simple " + (data.simple() == null ? "无" : "有"));
        } catch (Exception e) {
            loadedOk = false;
            data = new MineComplexData();
            ChatUtils.error("读取 mine.json 失败：" + e.getMessage()
                + " —— 这一轮不会覆盖原文件，请把文件发给我看看");
        }
    }

    /** 切换世界配置：先把旧的存起来，再读新的。 */
    public void switchWorld(int level) {
        save();
        load(level);
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
            ChatUtils.error("保存 mine.json 失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    /** 现在的任务名（拷贝，改它没用）。 */
    public List<String> tasks() {
        return List.copyOf(data.complex());
    }

    public int taskCount() {
        return data.complex().size();
    }

    public boolean has(String name) {
        return name != null && data.complex().contains(name);
    }

    /** 任务名合不合法（会变成目录名，所以限制得很死）。 */
    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 建一个任务：mine.json 里加一条，同时建 {@code mine/<task>/region.json}（内容是空的 {@code {}}）。
     *
     * @return null 表示成功，否则是给用户看的错误信息
     */
    @Nullable
    public String addTask(String name) {
        if (!isValidName(name)) {
            return "任务名只能用字母、数字、下划线和减号（1~64 个字符）：" + name;
        }
        if (has(name)) {
            return "已经有叫 " + name + " 的任务了";
        }

        Path dir = currentTaskDir(name);
        Path region = currentRegionFile(name);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(region)) {
                Files.writeString(region, "{}", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "建任务目录失败：" + e.getMessage();
        }

        data.complex().add(name);
        save();
        ChatUtils.debug("新建挖掘任务 " + name + "（" + dir + "）");
        return null;
    }

    /**
     * 删一个任务：mine.json 里去掉，整个 {@code mine/<task>/} 目录一起删掉。
     *
     * @return null 表示成功，否则是给用户看的错误信息
     */
    @Nullable
    public String delTask(String name) {
        if (!isValidName(name)) {
            return "任务名只能用字母、数字、下划线和减号（1~64 个字符）：" + name;
        }
        if (!has(name)) {
            return "没有叫 " + name + " 的任务";
        }

        Path dir = currentTaskDir(name);
        try {
            deleteTree(dir);
        } catch (IOException e) {
            return "删任务目录失败（" + dir + "）：" + e.getMessage();
        }

        data.complex().remove(name);
        save();
        ChatUtils.debug("删除挖掘任务 " + name + "（" + dir + "）");
        return null;
    }

    /** 读一个任务的 region.json；没有 / 还是空的 {@code {}} 都返回 null。 */
    @Nullable
    public MineTaskRegion region(String name) {
        return MineTaskRegion.load(currentRegionFile(name));
    }

    // ------------------------------------------------------------------
    // simple 缓存
    // ------------------------------------------------------------------

    /** mine.json 里缓存的 simple 任务；没有就是 null。 */
    @Nullable
    public MineComplexData.SimpleRegion simple() {
        return data.simple();
    }

    public void setSimple(MineComplexData.SimpleRegion value) {
        data.simple(value);
        save();
    }

    /** 清掉 simple 缓存。有没有真的清掉。 */
    public boolean clearSimple() {
        if (data.simple() == null) return false;
        data.simple(null);
        save();
        return true;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 递归删一个目录（先删里面的东西，再删目录本身）。 */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
