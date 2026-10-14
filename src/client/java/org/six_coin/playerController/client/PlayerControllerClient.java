package org.six_coin.playerController.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.commands.PcCommand;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheRenderer;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.feature.AccountSwitcher;
import org.six_coin.playerController.client.feature.AntiKick;
import org.six_coin.playerController.client.feature.DimensionWait;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.station.StationManager;
import org.six_coin.playerController.client.station.StationRenderer;
import org.six_coin.playerController.client.stock.StockManager;
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
        StockManager.get().load();
        PcCommand.register();
        WaypointRenderer.register();
        ContainerCacheRenderer.register();
        StationRenderer.register();

        // 在原版 tick 世界之前驱动动作队列，移动类动作写入的速度才能在当 tick 生效
        ClientTickEvents.START_CLIENT_TICK.register(ActionManager.get()::tick);

        // 防踢：飞行（悬停）期间每 20 tick 往服务端补一个「稍微往下」的位置包，
        // 免得原版服务端按「浮空超过 80 tick」把人踢了。见 AntiKick。
        // 放在 tick 末尾（和 Meteor 的 post-tick 一致）：这时候 player.getY() 正好是
        // 客户端这一 tick 报给服务端的高度，往下补的量最准。
        ClientTickEvents.END_CLIENT_TICK.register(client -> AntiKick.tick());

        // 换维度（传送门）之后先等一会儿再动：服务端还没把落点同步好，马上走容易被拉回去
        ClientTickEvents.END_CLIENT_TICK.register(client -> DimensionWait.tick());

        // 记录下界传送门边：只有 /pc move <轴> / /pc move face 的终点是传送门时才会武装，
        // 所以这里要一直跑着（传送是服务端做的，得等维度变化才能确认落点）
        ClientTickEvents.END_CLIENT_TICK.register(PortalTracker::tick);

        // 容器缓存：监听玩家每次开关容器，打开的容器在缓存里就把内容刷新一遍
        ClientTickEvents.END_CLIENT_TICK.register(ContainerCacheTracker::tick);

        // 每次进世界都把「不显示容器界面」的计数清零：上次要是非正常退出（崩了 / 被强杀），
        // 计数会一直挂着，那之后所有容器界面都被挡住，箱子永远打不开。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ScreenSuppressor.reset()) {
                ChatUtils.debug("进世界：已解除容器界面抑制（上次没正常收尾留下的计数）");
            }
        });

        // /pc account：换账户是分 tick 做的（先换身份下线，再自动重连），这里驱动它
        ClientTickEvents.END_CLIENT_TICK.register(AccountSwitcher::tick);

        // 换完账户重新进世界时补一条提示：切世界的过程中聊天栏会被清空
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> AccountSwitcher.onJoin(client));

        ChatUtils.debug("Player Controller 已加载，使用 /pc 查看命令；当前 debug="
            + PlayerControllerConfig.isDebug());
    }
}
