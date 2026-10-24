package org.six_coin.playerController.client.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import org.six_coin.playerController.client.container.CachedContainer;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.mending.MendingConfig;
import org.six_coin.playerController.client.mending.MendingPos;
import org.six_coin.playerController.client.mine.station.MinePlace;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationPos;
import org.six_coin.playerController.client.util.DimensionUtils;
import org.six_coin.playerController.client.waypoint.Waypoint;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/** {@code [dim]} 参数的补全：原版三个维度 + 路径点图 / 工作站 / 挖掘站 / 容器缓存里出现过的维度。 */
public final class DimensionSuggestions {

    private DimensionSuggestions() {
    }

    public static CompletableFuture<Suggestions> suggest(CommandContext<FabricClientCommandSource> context,
                                                        SuggestionsBuilder builder) {
        Set<String> dimensions = new TreeSet<>();
        dimensions.add(DimensionUtils.OVERWORLD);
        dimensions.add(DimensionUtils.NETHER);
        dimensions.add(DimensionUtils.END);

        for (Waypoint waypoint : WaypointManager.get().graph().allWaypoints()) {
            dimensions.add(waypoint.dimension());
        }
        for (StationPos pos : StationManager.get().allPositions()) {
            dimensions.add(pos.dimension());
        }
        MendingPos standPoint = MendingConfig.get().standPoint();
        if (standPoint != null) dimensions.add(standPoint.dimension());
        MendingPos enderChest = MendingConfig.get().enderChest();
        if (enderChest != null) dimensions.add(enderChest.dimension());
        for (CachedContainer container : ContainerCacheManager.get().all()) {
            dimensions.add(container.dimension());
        }
        for (MinePlace place : MineStationManager.get().allPositions()) {
            dimensions.add(place.dimension());
        }

        return SharedSuggestionProvider.suggest(dimensions, builder);
    }
}
