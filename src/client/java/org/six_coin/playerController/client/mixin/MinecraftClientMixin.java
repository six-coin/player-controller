package org.six_coin.playerController.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.six_coin.playerController.client.feature.ScreenSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 /pc container 打开箱子时不在客户端弹出界面。
 *
 * <p>打开容器界面时会先设置 {@code player.containerMenu} 再调用 {@code setScreen}，
 * 所以我们只拦掉 {@code setScreen}：槽位同步照常完成，界面上什么都不显示。
 *
 * <p>26.3 里「当前界面」从 {@code Minecraft} 搬到了 {@code Gui}
 * （{@code Minecraft#setScreen} → {@code Gui#setScreen}），所以这里改注入 {@link Gui}。
 */
@Mixin(Gui.class)
public class MinecraftClientMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void playerController$setScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen && ScreenSuppressor.isSuppressed()) {
            ci.cancel();
        }
    }
}
