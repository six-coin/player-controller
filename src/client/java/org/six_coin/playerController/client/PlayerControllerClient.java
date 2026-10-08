package org.six_coin.playerController.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.commands.PcCommand;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheRenderer;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationRenderer;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.PortalTracker;
import org.six_coin.playerController.client.waypoint.WaypointManager;
import org.six_coin.playerController.client.waypoint.WaypointRenderer;

public class PlayerControllerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PlayerControllerConfig.load();
        WaypointManager.get().load();
        ContainerCacheManager.get().load();
        StationManager.get().load();
        PcCommand.register();
        WaypointRenderer.register();
        ContainerCacheRenderer.register();
        StationRenderer.register();

        // 在原版 tick 世界之前驱动动作队列，移动类动作写入的速度才能在当 tick 生效
        ClientTickEvents.START_CLIENT_TICK.register(ActionManager.get()::tick);

        // 记录下界传送门边：只有 /pc move <轴> / /pc move face 的终点是传送门时才会武装，
        // 所以这里要一直跑着（传送是服务端做的，得等维度变化才能确认落点）
        ClientTickEvents.END_CLIENT_TICK.register(PortalTracker::tick);

        // 容器缓存：监听玩家每次开关容器，打开的容器在缓存里就把内容刷新一遍
        ClientTickEvents.END_CLIENT_TICK.register(ContainerCacheTracker::tick);

        ChatUtils.debug("Player Controller 已加载，使用 /pc 查看命令；当前 debug="
            + PlayerControllerConfig.isDebug());
    }
}
