package org.six_coin.playerController.client.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.container.CachedContainer;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerSession;
import org.six_coin.playerController.client.container.ContainerTypes;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把（一批）容器加进容器缓存：逐个打开、数里面的东西、写进缓存。
 *
 * <p>流程：看向方块 → 右键打开 → 等槽位同步 → 用
 * {@link ContainerCacheManager#snapshot(net.minecraft.screen.ScreenHandler)} 数一遍容器格子 →
 * 写入缓存 → 关界面 → 下一个。
 *
 * <p>单个添加（{@code add_position} / {@code add_target}）时 {@code verbose = true}，每个容器都报一行；
 * 批量（{@code add_batch}）时在最后汇总一行：成功多少、超出触及范围多少、不是支持的容器多少。
 */
public class ContainerCacheAddAction extends Action {

    /** 整个任务最久（20 tick = 1 秒）。 */
    private static final int MAX_TOTAL_TICKS = 20 * 300;

    private final List<BlockPos> positions;
    /** 批量时在外面就已经筛掉的：超出触及范围、不是支持的容器。 */
    private final int skippedOutOfReach;
    private final int skippedUnsupported;
    private final boolean verbose;

    private int index;
    /** 当前正在处理的容器的会话（每换一个容器就换一个）。 */
    @Nullable
    private ContainerSession session;
    private int totalTicks;

    private int added;
    private int updated;
    private int unchanged;
    private int failed;

    public ContainerCacheAddAction(List<BlockPos> positions, int skippedOutOfReach,
                                   int skippedUnsupported, boolean verbose) {
        this.positions = positions;
        this.skippedOutOfReach = skippedOutOfReach;
        this.skippedUnsupported = skippedUnsupported;
        this.verbose = verbose;
    }

    @Override
    public String name() {
        if (positions.size() == 1) {
            return "缓存容器 " + positions.get(0).toShortString();
        }
        return "批量缓存容器（" + positions.size() + " 个）";
    }

    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        if (positions.isEmpty()) {
            ChatUtils.debug("没有要缓存的容器（全被筛掉了）");
            finish();
            return;
        }
        openNext();
    }

    @Override
    protected void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || session == null) {
            fail("玩家不存在");
            return;
        }

        totalTicks++;
        if (totalTicks > MAX_TOTAL_TICKS) {
            fail("超时（" + totalTicks + " tick）");
            return;
        }

        BlockPos pos = session.pos();

        // 阶段一：等容器界面打开
        if (!session.isOpen()) {
            switch (session.awaitOpen(mc)) {
                case READY -> ChatUtils.debug("容器界面已打开（" + pos.toShortString() + "）");
                case TIMEOUT -> {
                    ChatUtils.error("打开 " + pos.toShortString() + " 的容器超时，跳过");
                    failed++;
                    session.close(mc);
                    advance();
                }
                case WAITING -> {
                }
            }
            return;
        }

        // 界面被别人关掉了
        if (!session.stillOpen(mc)) {
            ChatUtils.error("缓存 " + pos.toShortString() + " 时容器界面被关掉了，跳过");
            failed++;
            session.close(mc);
            advance();
            return;
        }

        // 阶段二：等槽位同步完
        if (!session.settled()) return;

        // 阶段三：数一遍写进缓存
        record(mc, pos);
        session.close(mc);
        advance();
    }

    @Override
    protected void onEnd() {
        if (session != null) session.close(MinecraftClient.getInstance());

        if (verbose) {
            output().info("容器缓存：新加 " + added + " 个，更新 " + updated + " 个"
                + (unchanged > 0 ? "，内容没变 " + unchanged + " 个" : "")
                + (failed > 0 ? "，失败 " + failed + " 个" : ""));
            return;
        }

        int total = positions.size() + skippedOutOfReach + skippedUnsupported;
        output().info("容器缓存（批量）：范围内 " + total + " 个位置 —— 成功 " + (added + updated)
            + " 个（新加 " + added + "、更新 " + updated + "，其中内容没变 " + unchanged + "）"
            + "，超出触及范围 " + skippedOutOfReach + " 个"
            + "，不是支持的容器 " + skippedUnsupported + " 个"
            + (failed > 0 ? "，打开失败 " + failed + " 个" : ""));
    }

    // ------------------------------------------------------------------

    private void openNext() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            fail("玩家或世界不存在");
            return;
        }

        BlockPos pos = positions.get(index);
        session = new ContainerSession(pos);

        ChatUtils.debug("第 " + (index + 1) + "/" + positions.size() + " 个："
            + pos.toShortString() + "（" + ContainerTypes.idOf(mc.world.getBlockState(pos)) + "）");
        session.open(mc, player);
    }

    private void advance() {
        index++;
        if (index >= positions.size()) {
            finish();
            return;
        }
        openNext();
    }

    /** 数一遍当前界面里容器格子的东西，写进缓存。 */
    private void record(MinecraftClient mc, BlockPos pos) {
        ContainerCacheManager cache = ContainerCacheManager.get();
        String dimension = DimensionUtils.current();
        String type = ContainerTypes.idOf(mc.world.getBlockState(pos));
        Map<String, Integer> items = ContainerCacheManager.snapshot(session.handler());

        CachedContainer existing = cache.at(dimension, pos);
        boolean isNew = existing == null;
        boolean changed = isNew || !existing.items().equals(new TreeMap<>(items));

        CachedContainer container = cache.put(type, dimension, pos, items);
        cache.save();

        int total = 0;
        for (int count : items.values()) total += count;
        String detail = items.size() + " 种 / " + total + " 个";

        if (isNew) {
            added++;
            if (verbose) {
                output().info("已加入容器缓存 #" + container.id() + " " + type + " "
                    + DimensionUtils.display(dimension) + " " + pos.toShortString() + "：" + detail);
            }
            ChatUtils.debug("新缓存 #" + container.id() + "：" + items);
            return;
        }

        if (changed) {
            updated++;
            if (verbose) {
                output().info("已更新容器缓存 #" + container.id() + " " + type + " "
                    + DimensionUtils.display(dimension) + " " + pos.toShortString() + "：" + detail);
            }
            ChatUtils.debug("更新 #" + container.id() + "：" + items);
            return;
        }

        unchanged++;
        ChatUtils.debug("#" + container.id() + " " + pos.toShortString() + " 内容没变，不重复写");
    }
}
