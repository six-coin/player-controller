package org.six_coin.playerController.client.stock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 备货任务表：{@code config/player-controller/world_<n>/stock.json}（就是一个任务名数组），
 * 每个任务一个文件夹 {@code stock/<名字>/}，里面放材料清单和中间产物。
 *
 * <pre>
 * stock.json           ["test_task", "test_task_2"]
 * stock/&lt;名字&gt;/material.json              用户手写放进去的
 * stock/&lt;名字&gt;/1_1_material_clean.json    第一部分阶段1 第 1 步产物
 * stock/&lt;名字&gt;/1_2_material_optimized.json 第一部分阶段1 第 2 步产物
 * stock/&lt;名字&gt;/final_final.json            第一部分阶段1 第 3 步产物（就是 1_2 的副本）
 * stock/&lt;名字&gt;/2_1_process_raw.json        第二部分阶段1 第 1 步产物
 * stock/&lt;名字&gt;/2_2_material_raw.json       第二部分阶段1 第 2 步产物
 * stock/&lt;名字&gt;/2_3_material_clean.json     第二部分阶段1 第 3 步产物
 * stock/&lt;名字&gt;/2_4_process_clean.json      第二部分阶段1 第 4 步产物
 * stock/&lt;名字&gt;/2_5_material_cleaner.json   第二部分阶段1 第 5 步产物
 * stock/&lt;名字&gt;/final_material.json         第二部分阶段2 照着它取货（2_5 的副本）
 * stock/&lt;名字&gt;/final_process.json          第二部分阶段3 合成用（2_4 的副本）
 * </pre>
 */
public final class StockManager {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final Type FILE_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private static final String FILE_NAME = "stock.json";

    /** 任务名同时也是文件夹名，限制一下别让它跑出 stock 目录。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    private static final StockManager INSTANCE = new StockManager();

    private List<String> tasks = new ArrayList<>();
    private int loadedWorld = -1;
    private boolean loadedOk = false;

    private StockManager() {
    }

    public static StockManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    public static Path stockFile(int world) {
        return WaypointManager.worldDir(world).resolve(FILE_NAME);
    }

    public static Path stockDir(int world) {
        return WaypointManager.worldDir(world).resolve("stock");
    }

    public static Path taskDir(int world, String task) {
        return stockDir(world).resolve(task);
    }

    public static Path materialFile(int world, String task) {
        return taskDir(world, task).resolve("material.json");
    }

    public static Path cleanFile(int world, String task) {
        return taskDir(world, task).resolve("1_1_material_clean.json");
    }

    public static Path optimizedFile(int world, String task) {
        return taskDir(world, task).resolve("1_2_material_optimized.json");
    }

    public static Path finalFinalFile(int world, String task) {
        return taskDir(world, task).resolve("final_final.json");
    }

    // ---- 第二部分（收集需要合成的材料）的文件 ----

    /** {@code 2_1_process_raw.json}：material.json 去掉 final_final 已经收过的那些。 */
    public static Path processRawFile(int world, String task) {
        return taskDir(world, task).resolve("2_1_process_raw.json");
    }

    /** {@code 2_2_material_raw.json}：2_1 里所有「原材料」摊平成 item_list。 */
    public static Path materialRawFile(int world, String task) {
        return taskDir(world, task).resolve("2_2_material_raw.json");
    }

    /** {@code 2_3_material_clean.json}：2_2 里「仓库 + 容器凑得齐」的那些。 */
    public static Path materialCleanFile(int world, String task) {
        return taskDir(world, task).resolve("2_3_material_clean.json");
    }

    /** {@code 2_4_process_clean.json}：2_1 里「材料都齐」的最终产物。 */
    public static Path processCleanFile(int world, String task) {
        return taskDir(world, task).resolve("2_4_process_clean.json");
    }

    /** {@code 2_5_material_cleaner.json}：2_4 里所有「原材料」摊平成 item_list。 */
    public static Path materialCleanerFile(int world, String task) {
        return taskDir(world, task).resolve("2_5_material_cleaner.json");
    }

    /** {@code final_material.json}：2_5 的副本（第二部分阶段2 就照它去取货）。 */
    public static Path finalMaterialFile(int world, String task) {
        return taskDir(world, task).resolve("final_material.json");
    }

    /** {@code final_process.json}：2_4 的副本（第二部分阶段3 合成用）。 */
    public static Path finalProcessFile(int world, String task) {
        return taskDir(world, task).resolve("final_process.json");
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public int loadedWorld() {
        return loadedWorld;
    }

    public Path currentTaskDir(String task) {
        return taskDir(world(), task);
    }

    public Path currentMaterialFile(String task) {
        return materialFile(world(), task);
    }

    public Path currentCleanFile(String task) {
        return cleanFile(world(), task);
    }

    public Path currentOptimizedFile(String task) {
        return optimizedFile(world(), task);
    }

    public Path currentFinalFinalFile(String task) {
        return finalFinalFile(world(), task);
    }

    public Path currentProcessRawFile(String task) {
        return processRawFile(world(), task);
    }

    public Path currentMaterialRawFile(String task) {
        return materialRawFile(world(), task);
    }

    public Path currentMaterialCleanFile(String task) {
        return materialCleanFile(world(), task);
    }

    public Path currentProcessCleanFile(String task) {
        return processCleanFile(world(), task);
    }

    public Path currentMaterialCleanerFile(String task) {
        return materialCleanerFile(world(), task);
    }

    public Path currentFinalMaterialFile(String task) {
        return finalMaterialFile(world(), task);
    }

    public Path currentFinalProcessFile(String task) {
        return finalProcessFile(world(), task);
    }

    private int world() {
        return loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld();
    }

    // ------------------------------------------------------------------
    // 任务表
    // ------------------------------------------------------------------

    public List<String> tasks() {
        return List.copyOf(tasks);
    }

    public boolean has(String task) {
        return tasks.contains(task);
    }

    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int world) {
        world = Math.max(1, world);
        loadedWorld = world;
        loadedOk = false;
        tasks = new ArrayList<>();

        Path path = stockFile(world);
        if (!Files.exists(path)) {
            loadedOk = true;
            ChatUtils.debug("world_" + world + " 还没有 stock.json，用空的: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> loaded = GSON.fromJson(reader, FILE_TYPE);
            if (loaded != null) tasks = new ArrayList<>(loaded);
            loadedOk = true;
            ChatUtils.debug("已加载 world_" + world + " 的备货任务：" + tasks.size() + " 个 " + tasks);
        } catch (Exception e) {
            loadedOk = false;
            tasks = new ArrayList<>();
            ChatUtils.error("读取备货任务表失败：" + e.getMessage() + " —— 这一轮不会覆盖原文件");
        }
    }

    public void switchWorld(int world) {
        save();
        load(world);
        ChatUtils.info("备货任务表已切到 world_" + world + "（" + tasks.size() + " 个任务）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        Path path = stockFile(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(tasks, FILE_TYPE, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存备货任务表失败: " + e.getMessage());
        }
    }

    /** 新建任务：建文件夹 + 记一笔 + 存盘。已经有了返回 false。 */
    public boolean addTask(String name) throws IOException {
        if (!isValidName(name)) {
            throw new IOException("任务名只能用字母、数字、下划线、减号（1~64 个字符），因为它同时是文件夹名");
        }
        if (tasks.contains(name)) return false;

        Files.createDirectories(taskDir(world(), name));
        tasks.add(name);
        save();
        ChatUtils.info("已新建备货任务 " + name + "：文件夹 " + currentTaskDir(name));
        return true;
    }

    /** 删任务：删文件夹 + 删记录 + 存盘。没有这个任务返回 false。 */
    public boolean delTask(String name) throws IOException {
        if (!tasks.contains(name)) return false;

        Path dir = taskDir(world(), name);
        if (Files.exists(dir)) {
            // 只允许删 stock 目录下面的东西
            Path stock = stockDir(world()).toAbsolutePath().normalize();
            Path target = dir.toAbsolutePath().normalize();
            if (!target.startsWith(stock) || target.equals(stock)) {
                throw new IOException("拒绝删除 " + target + "：它不在 " + stock + " 里面");
            }
            deleteRecursively(target);
        }

        tasks.remove(name);
        save();
        ChatUtils.info("已删除备货任务 " + name + "（连同文件夹 " + dir + "）");
        return true;
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        }
    }
}
