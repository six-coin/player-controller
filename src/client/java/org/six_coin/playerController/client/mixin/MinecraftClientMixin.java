package org.six_coin.playerController.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 /pc container 打开箱子时不在客户端弹出界面。
 *
 * <p>{@code HandledScreens.open} 会先设置 {@code player.currentScreenHandler}
 * 再调用 {@code setScreen}，所以我们只拦掉 {@code setScreen}：
 * 槽位同步照常完成，界面上什么都不显示。
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void playerController$setScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof HandledScreen && ScreenSuppressor.isSuppressed()) {
            ci.cancel();
        }
    }
}
