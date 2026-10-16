package org.six_coin.playerController.client.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.resource.DefaultResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本地那份**完整**的工作台配方表（没解锁的也在里面）。
 *
 * <p>为什么需要它：1.21.2 起服务端不再把合成配方表同步给客户端了 ——
 * {@code SynchronizeRecipesS2CPacket} 里只剩原料属性表（{@code RecipePropertySet}）和切石机配方，
 * 工作台配方只会以「已经解锁的配方书条目」（{@code RecipeBookAddS2CPacket} → {@code ClientRecipeBook}）
 * 的形式过来。所以**没解锁的配方，客户端本来根本看不到**，配方书里自然找不到。
 *
 * <p>但合成本身跟解锁没关系：服务端是拿格子里的东西去它自己的完整配方表里匹配的
 * （{@link CraftAction} 也是直接把材料摆进格子），
 * 解锁只影响配方书界面。既然我们的模组只在原版工作，服务端跑的和我们本地带的是同一份原版数据包
 * （同样的版本 → 同样的配方），那就直接读本地的：
 * <ul>
 *   <li><b>单人</b>：直接问集成的服务端要（{@link ServerRecipeManager#values()}）——
 *       连存档自己的数据包都算进去了，最准；</li>
 *   <li><b>联机</b>：读客户端自带原版数据包里的 {@code data/minecraft/recipe/*.json}，
 *       用原版的 {@link Recipe#CODEC} 反序列化（和原版服务端读配方是同一套代码）。</li>
 * </ul>
 *
 * <p>结果按**这一次连接**缓存：换了世界 / 换了服务器就重读。进世界时会在后台先读一遍
 * （见 {@link #warmUp}），第一次要用配方的时候就不用等。
 */
public final class LocalRecipes {

    private static final Logger LOGGER = LoggerFactory.getLogger("player-controller/recipe");

    /** 数据包里配方所在的目录。 */
    private static final String RECIPE_DIR = "recipe";

    private static final String JSON_SUFFIX = ".json";

    /** 读不出来时最多再试几次（同一个连接里）。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 缓存与加载都串行化：后台线程可能正在读，这时候主线程要等它，而不是再读一遍。 */
    private static final Object LOCK = new Object();

    /** 这份缓存是哪一次连接的（单人 = 集成的服务端，联机 = 连接本身）。 */
    @Nullable
    private static Object loadedKey;

    private static List<RecipeDisplay> displays = List.of();

    /** 读失败的重试计数：连接标识 + 已经试了几次。 */
    @Nullable
    private static Object attemptKey;
    private static int attempts;

    private LocalRecipes() {
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /**
     * 本地完整配方表里所有**工作台**配方（有形状 / 无形状的那些）。
     *
     * <p>读不出来（单人还没起服务端、联机没连上、解析全失败）就是空表，调用方照旧只用配方书。
     */
    public static List<RecipeDisplay> craftingDisplays(MinecraftClient mc) {
        Object key = connectionKey(mc);
        if (key == null) return List.of();

        synchronized (LOCK) {
            if (key.equals(loadedKey)) return displays;
        }
        return load(mc, key, registries(mc));
    }

    /**
     * 进世界的时候在后台先读一遍，省得第一次用到配方时卡一下（读一次 100~500 ms 的量级）。
     *
     * <p>读的时候如果主线程正好也要配方，{@link #craftingDisplays} 会等这次读完（不会读两遍）。
     */
    public static void warmUp(MinecraftClient mc) {
        Object key = connectionKey(mc);
        RegistryWrapper.WrapperLookup lookup = registries(mc);
        if (key == null || lookup == null) return;

        synchronized (LOCK) {
            if (key.equals(loadedKey)) return;
        }

        Thread thread = new Thread(() -> load(mc, key, lookup), "pc-local-recipes");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 真正读一遍并记下来；同一个连接只会读一次（后到的线程看到已经读好了就直接拿缓存）。 */
    private static List<RecipeDisplay> load(MinecraftClient mc, Object key,
                                            @Nullable RegistryWrapper.WrapperLookup lookup) {
        synchronized (LOCK) {
            if (key.equals(loadedKey)) return displays;

            long start = System.currentTimeMillis();
            List<Named> found = new ArrayList<>();
            String source = "（没有可读的来源）";
            Read read = new Read(0, 0);
            @Nullable String problem = null;

            try {
                if (mc.getServer() != null) {
                    source = "集成的服务端";
                    read = readFromServer(mc, found);
                } else if (lookup != null) {
                    source = "客户端自带原版数据包";
                    read = readFromDataPack(lookup, found);
                } else {
                    problem = "没有可读的来源";
                }
            } catch (Throwable t) {
                problem = "读的时候出错（" + t + "）";
                LOGGER.warn("读本地配方表时出错", t);
            }

            if (problem == null && read.recipes() == 0) problem = "一条配方都没读到";

            if (problem != null) {
                // 别把失败当成「读好了」：下次要用配方的时候再试，但也不能无限重试（每次都要几十上百 ms）
                if (!key.equals(attemptKey)) {
                    attemptKey = key;
                    attempts = 0;
                }
                attempts++;
                if (attempts < MAX_ATTEMPTS) {
                    LOGGER.warn("本地配方表暂时读不出来（{}，第 {} 次），下次要用配方时再试一遍", problem, attempts);
                    return List.of();
                }
                LOGGER.warn("本地配方表读不出来（{}，试了 {} 次），这一轮只用配方书里已经解锁的配方",
                    problem, attempts);
            }

            // 按配方 id 排序：顺序每次都一样，出问题好复现
            found.sort(Comparator.comparing(named -> named.id().toString()));

            loadedKey = key;
            displays = List.copyOf(found.stream().map(Named::display).toList());

            if (problem != null) return displays;

            LOGGER.info("本地配方表：读了 {} 条配方，其中工作台 {} 条（来源 {}, 耗时 {} ms{}）",
                read.recipes(), displays.size(), source, System.currentTimeMillis() - start,
                read.failed() == 0 ? "" : ", " + read.failed() + " 条读不出来");

            // 聊天栏只在主线程上发（后台线程读的时候不能碰聊天栏）
            if (mc.isOnThread()) {
                ChatUtils.debug("本地配方表：" + displays.size() + " 条工作台配方（" + source
                    + "，没解锁的也能用），耗时 " + (System.currentTimeMillis() - start) + " ms");
            }
            return displays;
        }
    }

    /** 单人：集成的服务端的配方表（一定是最准的）。 */
    private static Read readFromServer(MinecraftClient mc, List<Named> out) {
        ServerRecipeManager manager = mc.getServer().getRecipeManager();
        for (RecipeEntry<?> entry : manager.values()) {
            addDisplays(entry.id().getValue(), entry.value(), out);
        }
        return new Read(manager.values().size(), 0);
    }

    /**
     * 联机：原版数据包里的 {@code data/<命名空间>/recipe/*.json}。
     */
    private static Read readFromDataPack(RegistryWrapper.WrapperLookup lookup, List<Named> out) {
        DefaultResourcePack pack = VanillaDataPackProvider.createDefaultPack();
        RegistryOps<JsonElement> ops = lookup.getOps(JsonOps.INSTANCE);

        int[] counters = {0, 0};   // [读到的配方数, 读不出来的]
        for (String namespace : pack.getNamespaces(ResourceType.SERVER_DATA)) {
            pack.findResources(ResourceType.SERVER_DATA, namespace, RECIPE_DIR, (id, supplier) -> {
                JsonElement json;
                try (Reader reader = new InputStreamReader(supplier.get(), StandardCharsets.UTF_8)) {
                    json = JsonParser.parseReader(reader);
                } catch (Exception e) {
                    counters[1]++;
                    return;
                }

                Recipe<?> recipe;
                try {
                    recipe = Recipe.CODEC.parse(ops, json).getOrThrow();
                } catch (RuntimeException e) {
                    // 大多是引用了客户端没有的东西（自定义标签之类）；少一条不影响别的
                    counters[1]++;
                    return;
                }
                counters[0]++;
                addDisplays(recipeId(id), recipe, out);
            });
        }
        return new Read(counters[0], counters[1]);
    }

    /** 只要是工作台配方（有形状 / 无形状）就把它的显示数据留下来。 */
    private static void addDisplays(Identifier id, Recipe<?> recipe, List<Named> out) {
        for (RecipeDisplay display : recipe.getDisplays()) {
            if (display instanceof ShapedCraftingRecipeDisplay
                || display instanceof ShapelessCraftingRecipeDisplay) {
                out.add(new Named(id, display));
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 把 {@code minecraft:recipe/oak_planks.json} 这种资源 id 还原成配方 id {@code minecraft:oak_planks}。 */
    private static Identifier recipeId(Identifier resourceId) {
        String path = resourceId.getPath();
        if (path.startsWith(RECIPE_DIR + "/")) path = path.substring(RECIPE_DIR.length() + 1);
        if (path.endsWith(JSON_SUFFIX)) path = path.substring(0, path.length() - JSON_SUFFIX.length());
        return Identifier.of(resourceId.getNamespace(), path);
    }

    /** 这一次连接的标识：单人用集成的服务端，联机用连接本身（换了就重读）。 */
    @Nullable
    private static Object connectionKey(MinecraftClient mc) {
        if (mc == null || mc.world == null) return null;
        return mc.getServer() != null ? mc.getServer() : mc.getNetworkHandler();
    }

    @Nullable
    private static RegistryWrapper.WrapperLookup registries(MinecraftClient mc) {
        return mc == null || mc.world == null ? null : mc.world.getRegistryManager();
    }

    /** 配方 id + 它的显示数据（读的时候要按 id 排序，所以先配对放着）。 */
    private record Named(Identifier id, RecipeDisplay display) {
    }

    /** 一次读的结果：读到几条配方、几条读不出来。 */
    private record Read(int recipes, int failed) {
    }
}
