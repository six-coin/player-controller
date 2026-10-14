package org.six_coin.playerController.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/**
 * 让 {@code /pc account} 能换掉客户端当前登录的身份。
 *
 * <p>{@code MinecraftClient} 里这些东西全是 final，而且都不是「改一个 session 就完事」：
 * 登录握手用的是 {@code session}（见 {@code ConnectScreen} 里发 LoginHelloC2SPacket 那步），
 * 而 {@code gameProfileFuture} / {@code userApiService} / {@code profileKeys} /
 * {@code socialInteractionsManager} 都是启动时按旧账户建好的，不改就会继续用旧身份的档案、
 * 旧账户的聊天密钥和旧账户的屏蔽名单 —— 和 Meteor 换账户时改的是同一批字段。
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

    @Mutable
    @Accessor("session")
    void playerController$setSession(Session session);

    @Mutable
    @Accessor("gameProfileFuture")
    void playerController$setGameProfileFuture(CompletableFuture<ProfileResult> future);

    @Mutable
    @Accessor("userApiService")
    void playerController$setUserApiService(UserApiService service);

    @Mutable
    @Accessor("profileKeys")
    void playerController$setProfileKeys(ProfileKeys keys);

    @Mutable
    @Accessor("socialInteractionsManager")
    void playerController$setSocialInteractionsManager(SocialInteractionsManager manager);
}
