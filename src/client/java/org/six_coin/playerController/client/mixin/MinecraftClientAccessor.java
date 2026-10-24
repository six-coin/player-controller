package org.six_coin.playerController.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 让 {@code /pc account} 能换掉客户端当前登录的身份。
 *
 * <p>{@code Minecraft} 里这些东西全是 final，而且都不是「改一个 user 就完事」：
 * 登录握手用的是 {@code user}（见 {@code ConnectScreen} 里发 LoginHelloC2SPacket 那步），
 * 而 {@code userApiService} / {@code profileKeyPairManager} / {@code playerSocialManager}
 * 都是启动时按旧账户建好的，不改就会继续用旧身份的档案、旧账户的聊天密钥和旧账户的屏蔽名单。
 *
 * <p>注意：1.21.11 里的 {@code gameProfileFuture} 在 26.3 已经没有了（档案改为从 {@code user} 现拼），
 * 所以这里不再有对应的 accessor。
 */
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {

    @Mutable
    @Accessor("user")
    void playerController$setUser(User user);

    @Mutable
    @Accessor("userApiService")
    void playerController$setUserApiService(UserApiService service);

    @Mutable
    @Accessor("profileKeyPairManager")
    void playerController$setProfileKeyPairManager(ProfileKeyPairManager keys);

    @Mutable
    @Accessor("playerSocialManager")
    void playerController$setPlayerSocialManager(PlayerSocialManager manager);
}
