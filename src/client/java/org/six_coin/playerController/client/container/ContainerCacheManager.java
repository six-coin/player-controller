package org.six_coin.playerController.client.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.util.InventoryUtils;
import org.six_coin.playerController.client.util.ItemRules;
import org.six_coin.playerController.client.util.ShulkerUtils;
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
import java.util.Map;
import java.util.TreeMap;

/**
 * 容器缓存：记下箱子 / 木桶里的东西，存在
 * {@code config/player-controller/world_<n>/container.json}。
 *
 * <p>文件格式（键是容器的编号）：
 * <pre>
 * {
 *   "1": {
 *     "id": 1,
 *     "type": "minecraft:chest",
 *     "dimension": "minecraft:overworld",
 *     "x": 1, "y": 1, "z": 1,
 *     "items": { "minecraft:cobblestone": 64 }
 *   }
 * }
 * </pre>
 *
 * <p>items 里存的是「箱子里的东西 + 潜影盒里的东西」：潜影盒自己只在非空时算一个，
 * 堆叠上限 1 的、改过名字的物品都不算（规则见 {@link ItemRules}）。
 *
 * <p>大箱子不特判：玩家加的是哪一半就存哪一半的坐标，但 items 是整口箱子（两半）的东西
 * —— 因为我们是照着打开界面里的 54 个格子统计的。
 */
public final class ContainerCacheManager {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final Type FILE_TYPE = new TypeToken<TreeMap<Integer, CachedContainer>>() {
    }.getType();

    private static final String FILE_NAME = "container.json";

    private static final ContainerCacheManager INSTANCE = new ContainerCacheManager();

    /** id -> 容器（按 id 排序）。 */
    private final TreeMap<Integer, CachedContainer> containers = new TreeMap<>();

    private int loadedWorld = -1;
    private boolean loadedOk = false;

    /** 是不是要把缓存里的容器高亮出来（紫色）。 */
    private boolean show = false;

    private ContainerCacheManager() {
    }

    public static ContainerCacheManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    public static Path cacheFile(int world) {
        return WaypointManager.worldDir(world).resolve(FILE_NAME);
    }

    /** get_all_items 生成的文件：{@code world_<n>/container/all_items.json}。 */
    public static Path itemsFile(int world) {
        return WaypointManager.worldDir(world).resolve("container").resolve("all_items.json");
    }

    public Path currentItemsFile() {
        return itemsFile(loadedWorld >= 0 ? loadedWorld : PlayerControllerConfig.getWorld());
    }

    public Path currentFile() {
        return cacheFile(PlayerControllerConfig.getWorld());
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
        containers.clear();

        Path path = cacheFile(world);
        if (!Files.exists(path)) {
            loadedOk = true;
            ChatUtils.debug("world_" + world + " 还没有容器缓存文件，用空的: " + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            TreeMap<Integer, CachedContainer> loaded = GSON.fromJson(reader, FILE_TYPE);
            if (loaded != null) containers.putAll(loaded);
            loadedOk = true;
            ChatUtils.debug("已加载 world_" + world + " 的容器缓存: " + containers.size() + " 个容器");
        } catch (Exception e) {
            containers.clear();
            loadedOk = false;
            ChatUtils.error("读取容器缓存失败：" + e.getMessage()
                + " —— 这一轮不会覆盖原文件，请把文件发给我看看");
        }
    }

    /** 切换世界配置：先把旧的存起来，再读新的。 */
    public void switchWorld(int world) {
        save();
        load(world);
        ChatUtils.info("容器缓存已切到 world_" + world + "（" + containers.size() + " 个容器）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        Path path = cacheFile(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(new TreeMap<>(containers), FILE_TYPE, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存容器缓存失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<CachedContainer> all() {
        return new ArrayList<>(containers.values());
    }

    public int size() {
        return containers.size();
    }

    @Nullable
    public CachedContainer byId(int id) {
        return containers.get(id);
    }

    /** 找这个维度、这个位置的缓存。 */
    @Nullable
    public CachedContainer at(String dimension, BlockPos pos) {
        for (CachedContainer container : containers.values()) {
            if (!container.dimension().equals(dimension)) continue;
            if (container.x() != pos.getX() || container.y() != pos.getY() || container.z() != pos.getZ()) {
                continue;
            }
            return container;
        }
        return null;
    }

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

    // ------------------------------------------------------------------
    // 增删改
    // ------------------------------------------------------------------

    /** 加一个容器；同一维度同一位置已经有了就更新它的内容（id 不变）。 */
    public CachedContainer put(String type, String dimension, BlockPos pos, Map<String, Integer> items) {
        CachedContainer existing = at(dimension, pos);
        if (existing != null) {
            existing.items(items);
            return existing;
        }

        CachedContainer created = new CachedContainer(nextId(), type, dimension, pos, items);
        containers.put(created.id(), created);
        return created;
    }

    private int nextId() {
        int id = 1;
        while (containers.containsKey(id)) id++;
        return id;
    }

    /**
     * 刷新一个容器的内容（打开界面关闭时用）。
     *
     * @return 内容有没有变化（没变化就不写文件）
     */
    public boolean updateItems(CachedContainer container, Map<String, Integer> items) {
        if (container.items().equals(new TreeMap<>(items))) return false;
        container.items(items);
        save();
        return true;
    }

    public boolean remove(int id) {
        if (containers.remove(id) == null) return false;
        save();
        return true;
    }

    /** 删掉这个维度这个位置的缓存，返回删了几个。 */
    public int removeAt(String dimension, BlockPos pos) {
        CachedContainer container = at(dimension, pos);
        if (container == null) return 0;
        containers.remove(container.id());
        save();
        return 1;
    }

    /** 删掉这个维度、这个长方体范围内的缓存，返回删了几个。 */
    public int removeInBox(String dimension, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());

        List<Integer> removing = new ArrayList<>();
        for (CachedContainer container : containers.values()) {
            if (!container.dimension().equals(dimension)) continue;
            if (container.x() < minX || container.x() > maxX) continue;
            if (container.y() < minY || container.y() > maxY) continue;
            if (container.z() < minZ || container.z() > maxZ) continue;
            removing.add(container.id());
        }

        for (int id : removing) containers.remove(id);
        if (!removing.isEmpty()) save();
        return removing.size();
    }

    /** 按「维度（主世界、下界、末地、其它）、x、y、z 递增」重新编号，id 从 1 开始。 */
    public int optimize() {
        List<CachedContainer> sorted = new ArrayList<>(containers.values());
        sorted.sort(Comparator
            .comparingInt((CachedContainer c) -> DimensionUtils.orderOf(c.dimension()))
            .thenComparing(CachedContainer::dimension)
            .thenComparingInt(CachedContainer::x)
            .thenComparingInt(CachedContainer::y)
            .thenComparingInt(CachedContainer::z));

        containers.clear();
        int id = 1;
        for (CachedContainer container : sorted) {
            container.id(id);
            containers.put(id, container);
            id++;
        }
        save();
        return sorted.size();
    }

    // ------------------------------------------------------------------
    // 统计容器里的东西
    // ------------------------------------------------------------------

    /**
     * 把界面里容器格子里的东西统计出来（大箱子的话 54 格都在里面）。
     *
     * <p>规则：箱子里的物品和潜影盒里的物品都算；<b>潜影盒本身永远不记</b>
     * （不管空的还是装着东西的）；堆叠上限 1 的、改过名字的物品不算。
     */
    public static Map<String, Integer> snapshot(ScreenHandler handler) {
        Map<String, Integer> items = new TreeMap<>();
        for (Slot slot : InventoryUtils.containerSlots(handler)) {
            count(items, slot.getStack());
        }
        return items;
    }

    private static void count(Map<String, Integer> items, ItemStack stack) {
        if (stack.isEmpty()) return;

        if (ShulkerUtils.isShulkerBox(stack)) {
            // 盒子本身不记，只记里面要看的东西（空盒子就什么都不记）
            for (ItemStack inner : ShulkerUtils.consideredContents(stack)) {
                add(items, Registries.ITEM.getId(inner.getItem()).toString(), inner.getCount());
            }
            return;
        }

        if (ItemRules.isIgnored(stack)) return;
        add(items, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount());
    }

    private static void add(Map<String, Integer> items, String itemId, int count) {
        items.merge(itemId, count, Integer::sum);
    }
}
