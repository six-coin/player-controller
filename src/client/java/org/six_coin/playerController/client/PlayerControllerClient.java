package org.six_coin.playerController.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.attack.AttackConfig;
import org.six_coin.playerController.client.attack.AttackManager;
import org.six_coin.playerController.client.commands.PcCommand;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.container.ContainerCacheManager;
import org.six_coin.playerController.client.container.ContainerCacheRenderer;
import org.six_coin.playerController.client.container.ContainerCacheTracker;
import org.six_coin.playerController.client.eat.EatConfig;
import org.six_coin.playerController.client.eat.EatManager;
import org.six_coin.playerController.client.feature.AccountSwitcher;
import org.six_coin.playerController.client.feature.AntiKick;
import org.six_coin.playerController.client.feature.DimensionWait;
import org.six_coin.playerController.client.feature.HoverJitter;
import org.six_coin.playerController.client.feature.MovePackets;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.six_coin.playerController.client.feature.ServerTickRate;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.mending.MendingConfig;
import org.six_coin.playerController.client.mending.MendingManager;
import org.six_coin.playerController.client.mine.MineFilterConfig;
import org.six_coin.playerController.client.mine.MineManager;
import org.six_coin.playerController.client.mine.complex.MineComplexManager;
import org.six_coin.playerController.client.mine.station.MineStationManager;
import org.six_coin.playerController.client.mine.station.MineStationRenderer;
import org.six_coin.playerController.client.recipe.LocalRecipes;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.stock.station.StationManager;
import org.six_coin.playerController.client.stock.station.StationRenderer;
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
        AttackConfig.get().load();
        EatConfig.get().load();
        MendingConfig.get().load();
        MineFilterConfig.get().load();
        MineStationManager.get().load();
        MineComplexManager.get().load();
        PcCommand.register();
        WaypointRenderer.register();
        ContainerCacheRenderer.register();
        StationRenderer.register();
        MineStationRenderer.register();

        // 26.3 服务端一个 tick 只接受一个带位置的移动包，第二个直接断开；
        // 而「一个 tick」的边界是 ClientTickEnd 包（见 MovePackets.endWindow / ClientPacketSendMixin）。
        // 这里只做兜底：ClientTickEnd 一直没发出去（暂停 / 卡住）时强行换窗口。
        ClientTickEvents.START_CLIENT_TICK.register(client -> MovePackets.beginTick());

        // 在原版 tick 世界之前驱动动作队列，移动类动作写入的速度才能在当 tick 生效
        ClientTickEvents.START_CLIENT_TICK.register(ActionManager.get()::tick);

        // 防踢：飞行（悬停）期间每 20 tick 往服务端补一个「稍微往下」的位置包，
        // 免得原版服务端按「浮空超过 80 tick」把人踢了。见 AntiKick。
        // 放在 tick 末尾（和 Meteor 的 post-tick 一致）：这时候 player.getY() 正好是
        // 客户端这一 tick 报给服务端的高度，往下补的量最准。
        ClientTickEvents.END_CLIENT_TICK.register(client -> AntiKick.tick());

        // 悬停时把 fallDistance 压回 0：不然 Meteor 的 Flight（velocity 模式）反检测会每 tick
        // 把朝向 ±1 度地改一下再改回来，yRotO 记到的是改过的值，视角就会一直左右摆。见 HoverJitter。
        ClientTickEvents.END_CLIENT_TICK.register(client -> HoverJitter.tick());

        // 换维度（传送门）之后先等一会儿再动：服务端还没把落点同步好，马上走容易被拉回去
        ClientTickEvents.END_CLIENT_TICK.register(client -> DimensionWait.tick());

        // 攻击：常驻扫描，不受动作队列影响（见 AttackManager）。放在 tick 末尾：
        // 这时候这一 tick 的位置 / 朝向都已经定下来了，攻击包发出去不会和移动包打架。
        ClientTickEvents.END_CLIENT_TICK.register(client -> AttackManager.get().tick());

        // 自动进食（/pc eat）：饱食度降到 12 就切快捷栏 9 吃一口。放在攻击后面、挖掘前面：
        // 优先级比攻击低（攻击先占快捷栏）、比挖掘高（挖掘不能把食物格抢走）。
        ClientTickEvents.END_CLIENT_TICK.register(client -> EatManager.get().tick());

        // mending：自己一套「走过去 → 开着末影箱 → 定时检测」的流程（见 MendingManager）。
        // 放在攻击后面：它每一轮会按经验球数量开关攻击，下一 tick 的攻击就能按新的开关来。
        ClientTickEvents.END_CLIENT_TICK.register(client -> MendingManager.get().tick());

        // 挖掘：按 /pc mine simple 选中的区域一直挖（见 MineManager）。放在攻击后面：
        // 这一 tick 攻击已经占过快捷栏 / 朝向，挖掘按优先级让开，不会把攻击的剑切走。
        ClientTickEvents.END_CLIENT_TICK.register(client -> MineManager.get().tick());

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

            // 换世界 / 重连之后，快捷栏和朝向的占用、服务端 tick 速率的样本都不算数了
            HotbarManager.get().reset();
            RotateManager.get().reset();
            AttackManager.get().reset();
            EatManager.get().reset();
            MendingManager.get().reset();
            MineManager.get().reset();
            HoverJitter.reset();
            ServerTickRate.onJoin();

            // 挖掘的过滤列表是可以手改的 json，进世界时重新读一遍
            MineFilterConfig.get().load();

            // mine.json（任务清单 + simple 缓存）也重读一遍，然后把上次的 simple 任务接回来：
            // 重新进世界之后挖掘区不会丢，站着不动就会接着挖（要停就 /pc mine cancel）
            MineComplexManager.get().load();
            MineManager.get().resumeSaved();
        });

        // /pc account：换账户是分 tick 做的（先换身份下线，再自动重连），这里驱动它
        ClientTickEvents.END_CLIENT_TICK.register(AccountSwitcher::tick);

        // 换完账户重新进世界时补一条提示：切世界的过程中聊天栏会被清空
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> AccountSwitcher.onJoin(client));

        // 配方：进世界时在后台把本地那份**完整**配方表读出来（服务端从 1.21.2 起不再同步合成配方表，
        // 没解锁的配方只能从本地读；见 LocalRecipes）。后台读，第一次要用配方时就不用等。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> LocalRecipes.warmUp(client));

        ChatUtils.debug("Player Controller 已加载，使用 /pc 查看命令；当前 debug="
            + PlayerControllerConfig.isDebug());
    }
}
