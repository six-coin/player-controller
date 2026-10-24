package org.six_coin.playerController.client.mine.station;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
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
 * 挖掘站数据的读写：{@code config/player-controller/world_<n>/mine/station.json}。
 *
 * <p>格式见 {@link MineStationData}；检查报告写到同目录的 {@code check.json}
 * （{@code world_<n>/mine/check.json}）。
 *
 * <p>坐标都带维度（和备货的 station.json 不一样：挖掘站不要求所有容器都在一个地方）。
 */
public final class MineStationManager {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String FILE_NAME = "station.json";

    private static final MineStationManager INSTANCE = new MineStationManager();

    private MineStationData data = new MineStationData();
    private int loadedWorld = -1;
    private boolean loadedOk = false;
    private boolean show = false;

    private MineStationManager() {
    }

    public static MineStationManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    /** 挖掘站数据的目录：{@code world_<n>/mine/}。 */
    public static Path mineDir(int level) {
        return WaypointManager.worldDir(level).resolve("mine");
    }

    public static Path stationFile(int level) {
        return mineDir(level).resolve(FILE_NAME);
    }

    /** 检查报告：{@code world_<n>/mine/check.json}。 */
    public static Path checkFile(int level) {
        return mineDir(level).resolve("check.json");
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

    public void load(int level) {
        level = Math.max(1, level);
        loadedWorld = level;
        loadedOk = false;
        data = new MineStationData();

        Path path = stationFile(level);
        if (!Files.exists(path)) {
            loadedOk = true;
            ChatUtils.debug("world_" + level + " 还没有挖掘站文件，用空的: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            MineStationData loaded = GSON.fromJson(reader, MineStationData.class);
            if (loaded != null) data = loaded;
            data.resequence();
            loadedOk = true;
            ChatUtils.debug("已加载 world_" + level + " 的挖掘站：" + totalCount() + " 个坐标");
        } catch (Exception e) {
            loadedOk = false;
            data = new MineStationData();
            ChatUtils.error("读取挖掘站文件失败：" + e.getMessage()
                + " —— 这一轮不会覆盖原文件，请把文件发给我看看");
        }
    }

    /** 切换世界配置：先把旧的存起来，再读新的。 */
    public void switchWorld(int level) {
        save();
        load(level);
        ChatUtils.info("挖掘站已切到 world_" + level + "（" + totalCount() + " 个坐标）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        data.resequence();
        Path path = stationFile(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存挖掘站失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 单点部分的位置（带维度）；没设过返回 null。 */
    @Nullable
    public MinePlace single(MineStationPart part) {
        return switch (part) {
            case SAFE_POINT -> data.safePoint();
            case FOOD_PROVIDER -> data.foodProvider();
            case TOTEM_PROVIDER -> data.totemProvider();
            case EMPTY_SHULKER_BOX_RECYCLER -> data.emptyShulkerBoxRecycler();
            case INVENTORY_TEMP -> data.inventoryTemp();
            default -> null;
        };
    }

    /** 列表部分的位置（拷贝，改它没用）。 */
    public List<MinePlace> list(MineStationPart part) {
        return switch (part) {
            case ITEM_COLLECTOR -> List.copyOf(data.itemCollector());
            case TOOL_CACHE -> List.copyOf(data.toolCache());
            case SLIME_CACHE -> List.copyOf(data.slimeCache());
            case COBBLESTONE_CACHE -> List.copyOf(data.cobblestoneCache());
            case ITEM_TEMP -> List.copyOf(data.itemTemp());
            case UNLOADING_PLACEMENT -> List.copyOf(data.unloadingShulkerBoxPlacement());
            case SUPPLEMENT_PLACEMENT -> List.copyOf(data.supplementShulkerBoxPlacement());
            case TEMP_PLACEMENT -> List.copyOf(data.tempShulkerBoxPlacement());
            default -> List.of();
        };
    }

    /** 这一部分所有已经设过的位置：单点就是 0 或 1 个，列表就是全部。 */
    public List<MinePlace> positions(MineStationPart part) {
        if (part.isList()) return list(part);
        MinePlace single = single(part);
        return single == null ? List.of() : List.of(single);
    }

    /** 所有已经设过的坐标（去重）。 */
    public List<MinePlace> allPositions() {
        List<MinePlace> all = new ArrayList<>();
        Set<MinePlace> seen = new HashSet<>();
        for (MineStationPart part : MineStationPart.values()) {
            for (MinePlace place : positions(part)) {
                if (seen.add(place)) all.add(place);
            }
        }
        return all;
    }

    public int totalCount() {
        return allPositions().size();
    }

    /** 某一部分的某一条（按 id）；没有返回 null。 */
    @Nullable
    public MinePlace byId(MineStationPart part, int id) {
        for (MinePlace place : positions(part)) {
            if (place.id() == id) return place;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    public void setSingle(MineStationPart part, String dimension, BlockPos pos) {
        MinePlace value = MinePlace.of(dimension, pos, null);
        switch (part) {
            case SAFE_POINT -> data.safePoint(value);
            case FOOD_PROVIDER -> data.foodProvider(value);
            case TOTEM_PROVIDER -> data.totemProvider(value);
            case EMPTY_SHULKER_BOX_RECYCLER -> data.emptyShulkerBoxRecycler(value);
            case INVENTORY_TEMP -> data.inventoryTemp(value);
            default -> throw new IllegalArgumentException(part + " 不是单点");
        }
        save();
        ChatUtils.debug("挖掘站 " + part.display() + " = " + value.describe());
    }

    /**
     * 往列表里加一个位置（同维度同坐标已经有了就返回 false）。
     *
     * @param itemId 只有产物收集要传（产物 id），别的部分传 null
     */
    public boolean add(MineStationPart part, String dimension, BlockPos pos, @Nullable String itemId) {
        List<MinePlace> target = mutableList(part);
        MinePlace value = MinePlace.of(dimension, pos, itemId);
        if (target.contains(value)) return false;

        target.add(value);
        save();
        ChatUtils.debug("挖掘站 " + part.display() + " 加入 " + value.describe()
            + (itemId == null ? "" : "（产物 " + itemId + "）") + "，现在 " + target.size() + " 个");
        return true;
    }

    /** 从列表里删一个位置（本来就没有返回 false）。 */
    public boolean remove(MineStationPart part, String dimension, BlockPos pos) {
        List<MinePlace> target = mutableList(part);
        boolean removed = target.removeIf(existing -> existing.dimension().equals(dimension)
            && existing.pos().equals(pos));
        if (removed) {
            save();
            ChatUtils.debug("挖掘站 " + part.display() + " 删掉 "
                + DimensionUtils.display(dimension) + " " + pos.toShortString()
                + "，现在 " + target.size() + " 个");
        }
        return removed;
    }

    /** 按产物 id 删（产物收集专用），返回删掉几个。 */
    public int removeByItemId(MineStationPart part, String itemId) {
        List<MinePlace> target = mutableList(part);
        int before = target.size();
        target.removeIf(existing -> itemId.equals(existing.itemId()));
        int removed = before - target.size();
        if (removed > 0) {
            save();
            ChatUtils.debug("挖掘站 " + part.display() + " 删掉产物 " + itemId + " 的 " + removed + " 个");
        }
        return removed;
    }

    private List<MinePlace> mutableList(MineStationPart part) {
        return switch (part) {
            case ITEM_COLLECTOR -> data.itemCollector();
            case TOOL_CACHE -> data.toolCache();
            case SLIME_CACHE -> data.slimeCache();
            case COBBLESTONE_CACHE -> data.cobblestoneCache();
            case ITEM_TEMP -> data.itemTemp();
            case UNLOADING_PLACEMENT -> data.unloadingShulkerBoxPlacement();
            case SUPPLEMENT_PLACEMENT -> data.supplementShulkerBoxPlacement();
            case TEMP_PLACEMENT -> data.tempShulkerBoxPlacement();
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
