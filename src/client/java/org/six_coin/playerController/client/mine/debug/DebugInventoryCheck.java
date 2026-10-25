package org.six_coin.playerController.client.mine.debug;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCheck;
import org.six_coin.playerController.client.mine.complex.single.SingleStageCtx;
import org.six_coin.playerController.client.mine.complex.single.SingleStageData;
import org.six_coin.playerController.client.mine.complex.single.SingleStageDetail;
import org.six_coin.playerController.client.util.DimensionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拿 {@code debug_chunk_miner} 那一套检查（{@link SingleStageCheck#checkInventory} + reach）
 * 看当前物品栏，返回「哪里不对」。
 *
 * <p>和 {@code MineTaskMiningAction#verifyInventory} 用的是同一套判断，所以这里通过就说明
 * 直接跑 {@code debug_chunk_miner} 能过。
 */
public final class DebugInventoryCheck {

    private DebugInventoryCheck() {
    }

    /**
     * 那串指令到底生效了没有（两个「一定会有东西」的格子：快捷栏 1、背包 1）。
     *
     * <p>没管理员权限的时候指令会被服务端忽略（客户端连解析都过不去），一点反应都没有 ——
     * 所以这里至少看一眼，别让人以为「生成好了」。
     */
    public static boolean applied(LocalPlayer player) {
        return !player.getInventory().getItem(0).isEmpty()
            && !player.getInventory().getItem(9).isEmpty();
    }

    /** 当前物品栏不符合 debug_chunk_miner 的地方（空列表 = 通过）。 */
    public static List<String> problems(LocalPlayer player) {        SingleStageData data = new SingleStageData();
        data.refreshInventory(player.getInventory());

        BlockPos initialMin = new BlockPos(player.getBlockX() & ~15, player.getBlockY() & ~15,
            player.getBlockZ() & ~15);
        SingleStageCtx ctx = new SingleStageCtx(DimensionUtils.current(), initialMin,
            MineFilterConfig.get().filter(), SingleStageDetail.of(Set.of(), Map.of(), 8), data);

        List<String> problems = new ArrayList<>(SingleStageCheck.checkInventory(ctx));
        problems.addAll(SingleStageCheck.checkReach());
        return problems;
    }
}
