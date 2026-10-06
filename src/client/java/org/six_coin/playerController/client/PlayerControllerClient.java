package org.six_coin.playerController.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.commands.PcCommand;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;

public class PlayerControllerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PlayerControllerConfig.load();
        PcCommand.register();

        // 在原版 tick 世界之前驱动动作队列，移动类动作写入的速度才能在当 tick 生效
        ClientTickEvents.START_CLIENT_TICK.register(ActionManager.get()::tick);

        ChatUtils.debug("Player Controller 已加载，使用 /pc 查看命令；当前 debug="
            + PlayerControllerConfig.isDebug());
    }
}
