package org.six_coin.playerController.client.station;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作站数据的读写：{@code config/player-controller/world_<n>/station.json}。
 *
 * <p>格式见 {@link StationData}；检查报告写到同目录的 {@code station/check.json}。
 *
 * <p>坐标不带维度（按需求给的文件格式），所以 show / check 这些都是「当前维度的这些坐标」。
 */
public final class StationManager {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String FILE_NAME = "station.json";

    private static final StationManager INSTANCE = new StationManager();

    private StationData data = new StationData();
    private int loadedWorld = -1;
    private boolean loadedOk = false;
    private boolean show = false;

    private StationManager() {
    }

    public static StationManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    public static Path stationFile(int world) {
        return WaypointManager.worldDir(world).resolve(FILE_NAME);
    }

    /** 检查报告：{@code world_<n>/station/check.json}。 */
    public static Path checkFile(int world) {
        return WaypointManager.worldDir(world).resolve("station").resolve("check.json");
    }

    public Path currentFile() {
        return stationFile(loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld());
    }

    public Path currentCheckFile() {
        return checkFile(loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld());
    }

    public int loadedWorld() {
        return loadedWorld;
    }

    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int world) {
        world = Math.max(1, world);
        loadedWorld = world;
        loadedOk = false;
        data = new StationData();

        Path path = stationFile(world);
        if (!Files.exists(path)) {
            loadedOk = true;
            ChatUtils.debug("world_" + world + " 还没有工作站文件，用空的: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StationData loaded = GSON.fromJson(reader, StationData.class);
            if (loaded != null) data = loaded;
            data.sanitize();
            loadedOk = true;
            ChatUtils.debug("已加载 world_" + world + " 的工作站：" + totalCount() + " 个坐标");
        } catch (Exception e) {
            loadedOk = false;
            data = new StationData();
            ChatUtils.error("读取工作站文件失败：" + e.getMessage()
                + " —— 这一轮不会覆盖原文件，请把文件发给我看看");
        }
    }

    /** 切换世界配置：先把旧的存起来，再读新的。 */
    public void switchWorld(int world) {
        save();
        load(world);
        ChatUtils.info("工作站已切到 world_" + world + "（" + totalCount() + " 个坐标）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        Path path = stationFile(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存工作站失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 单点部分的位置（带维度）；没设过返回 null。 */
    @Nullable
    public StationPos single(StationPart part) {
        return switch (part) {
            case STAND_POINT -> data.standPoint();
            case CRAFTING_TABLE -> data.craftingTable();
            case STONECUTTER -> data.stonecutter();
            case ITEM_TEMP -> data.itemTemp();
            case SHULKER_BOX_PROVIDER -> data.shulkerBoxProvider();
            default -> null;
        };
    }

    /** 列表部分的位置（拷贝，改它没用）。 */
    public List<StationPos> list(StationPart part) {
        return switch (part) {
            case SHULKER_BOX_PLACEMENT -> List.copyOf(data.shulkerBoxPlacement());
            case ITEM_STORAGE -> List.copyOf(data.itemStorage());
            case ITEM_FINAL -> List.copyOf(data.itemFinal());
            default -> List.of();
        };
    }

    /** 这一部分所有已经设过的坐标：单点就是 0 或 1 个，列表就是全部。 */
    public List<StationPos> positions(StationPart part) {
        if (part.isList()) return list(part);
        StationPos single = single(part);
        return single == null ? List.of() : List.of(single);
    }

    /** 所有已经设过的坐标（去重），用来做触及范围检查。 */
    public List<StationPos> allPositions() {
        List<StationPos> all = new ArrayList<>();
        Set<StationPos> seen = new HashSet<>();
        for (StationPart part : StationPart.values()) {
            for (StationPos pos : positions(part)) {
                if (seen.add(pos)) all.add(pos);
            }
        }
        return all;
    }

    public int totalCount() {
        return allPositions().size();
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    public void setSingle(StationPart part, String dimension, BlockPos pos) {
        StationPos value = StationPos.of(dimension, pos);
        switch (part) {
            case STAND_POINT -> data.standPoint(value);
            case CRAFTING_TABLE -> data.craftingTable(value);
            case STONECUTTER -> data.stonecutter(value);
            case ITEM_TEMP -> data.itemTemp(value);
            case SHULKER_BOX_PROVIDER -> data.shulkerBoxProvider(value);
            default -> throw new IllegalArgumentException(part + " 不是单点");
        }
        save();
        ChatUtils.debug("工作站 " + part.display() + " = " + value.describe());
    }

    /** 往列表里加一个位置（已经有了就返回 false）。 */
    public boolean add(StationPart part, String dimension, BlockPos pos) {
        List<StationPos> target = mutableList(part);
        StationPos value = StationPos.of(dimension, pos);
        if (target.contains(value)) return false;

        target.add(value);
        save();
        ChatUtils.debug("工作站 " + part.display() + " 加入 " + value.describe()
            + "，现在 " + target.size() + " 个");
        return true;
    }

    /** 从列表里删一个位置（本来就没有返回 false）。 */
    public boolean remove(StationPart part, String dimension, BlockPos pos) {
        List<StationPos> target = mutableList(part);
        boolean removed = target.removeIf(existing -> existing.dimension().equals(dimension)
            && existing.pos().equals(pos));
        if (removed) {
            save();
            ChatUtils.debug("工作站 " + part.display() + " 删掉 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString()
                + "，现在 " + target.size() + " 个");
        }
        return removed;
    }

    private List<StationPos> mutableList(StationPart part) {
        return switch (part) {
            case SHULKER_BOX_PLACEMENT -> data.shulkerBoxPlacement();
            case ITEM_STORAGE -> data.itemStorage();
            case ITEM_FINAL -> data.itemFinal();
            default -> throw new IllegalArgumentException(part + " 不是列表");
        };
    }

    // ------------------------------------------------------------------
    // 高亮
    // ------------------------------------------------------------------

    public boolean isShowing() {
        return show;
    }

    public boolean toggleShow() {
        show = !show;
        return show;
    }

    public void setShow(boolean value) {
        show = value;
    }
}
