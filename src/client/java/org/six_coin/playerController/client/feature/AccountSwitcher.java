package org.six_coin.playerController.client.feature;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.User;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.LevelResource;
import org.six_coin.playerController.client.action.ActionManager;
import org.six_coin.playerController.client.mixin.MinecraftClientAccessor;
import org.six_coin.playerController.client.util.ChatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * {@code /pc account switch_to <player_id>} / {@code /pc account re_login}：
 * 下线之后重新加入刚才的世界，前者顺便把身份换成离线账户，后者什么都不换。
 *
 * <p>流程分三步，都是分 tick 做的：
 * <ol>
 *   <li>{@code /pc account} 只做校验和登记（{@link #request} / {@link #requestRelogin}），
 *       真正下线放到下一个 tick —— 命令是在聊天栏回调里执行的，
 *       直接在里面走「保存世界 → 断开 → 重连」容易踩重入；</li>
 *   <li>下一个 tick 换掉 session 等身份字段（只有换账户时才换），然后调用原版的下线流程
 *       （和「保存并退出」按钮同一个方法：它会等存档写完、服务端线程真正退出再返回，
 *       所以返回之后存档锁已经放开了，可以马上重新进同一个世界）；</li>
 *   <li>等 {@link #WAIT_TICKS} tick 之后，按下线前记下来的目标重连：
 *       服务器就重新连那个地址，单人游戏就重新打开那个存档目录。</li>
 * </ol>
 *
 * <p>注意：单人游戏里玩家存档是按 UUID 存的，换成离线账户就是换了一个 UUID，
 * 所以重新进世界时通常是一个「新玩家」（出生点开局、背包是空的），除非以前就用这个离线名字
 * 玩过这个存档。这是换账户本身决定的。{@code re_login} 不换身份，所以不会遇到这个。
 */
public final class AccountSwitcher {

    /** 也往日志里写一份：切世界时聊天栏会被清空，出问题只能看日志。 */
    private static final Logger LOG = LoggerFactory.getLogger("player-controller/account");

    /** 原版服务端对离线名字的校验规则：3~16 个字母 / 数字 / 下划线。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    /** 断开之后等这么多 tick 再连回去（1 秒，让服务端那边把断开处理完）。 */
    private static final int WAIT_TICKS = 20;

    private enum Stage {
        /** 没有在切换。 */
        IDLE,
        /** 命令已经受理，等下一个 tick 执行。 */
        START,
        /** 已经下线，等重连。 */
        WAIT
    }

    /** 下线之前记下来的「要回到哪里」。 */
    private record Target(String levelName, ServerData server) {

        boolean isServer() {
            return server != null;
        }
    }

    private static Stage stage = Stage.IDLE;
    /** 这次是换账户（true）还是只重新登录（false）。 */
    private static boolean switchingAccount;
    private static String pendingName;
    private static Target target;
    private static int waitTicks;
    /** 已经换成的离线账户名；进世界之后补一条提示（切世界时聊天栏会被清空）。 */
    private static String announceName;
    /** 存档加载失败的取消回调只报一次。 */
    private static boolean cancelReported;

    private AccountSwitcher() {
    }

    // ------------------------------------------------------------------
    // 命令层调用的入口
    // ------------------------------------------------------------------

    /**
     * 受理一次切换。返回错误信息，{@code null} 表示已经排队，会在下一个 tick 执行。
     */
    public static String request(String playerName) {
        if (stage != Stage.IDLE) return "上一次账户切换还没结束，请稍等";
        String error = validateName(playerName);
        if (error != null) return error;

        Minecraft client = Minecraft.getInstance();
        if (client == null) return "客户端还没启动";

        User session = client.getUser();
        if (session != null
            && playerName.equals(session.getName())
            && session.getAccessToken().isEmpty()) {
            return "当前已经是以离线账户 " + playerName + " 的身份登录了";
        }

        pendingName = playerName;
        switchingAccount = true;
        stage = Stage.START;
        LOG.info("请求切换到离线账户 {}（当前 {}）", playerName, describeCurrent(client));
        return null;
    }

    /**
     * 受理一次「原地重新登录」：不换账户，只退出到主界面再重新登录、回到刚才的世界。
     *
     * <p>返回错误信息，{@code null} 表示已经排队，会在下一个 tick 执行。
     */
    public static String requestRelogin() {
        if (stage != Stage.IDLE) return "上一次账户操作还没结束，请稍等";

        Minecraft client = Minecraft.getInstance();
        if (client == null) return "客户端还没启动";

        pendingName = null;
        switchingAccount = false;
        stage = Stage.START;
        LOG.info("请求重新登录（账户不变：{}）", describeCurrent(client));
        return null;
    }

    /** 校验离线名字；返回错误信息，{@code null} 表示合法。 */
    public static String validateName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "要写玩家名字，例如：/pc account switch_to Steve";
        }
        if (!VALID_NAME.matcher(playerName).matches()) {
            return "离线名字只能是 3~16 个字母、数字、下划线（原版服务端就是这么校验的），这个名字不行："
                + playerName;
        }
        return null;
    }

    /** 当前身份的一行描述，给 {@code /pc account} 不带参数时看。 */
    public static String describeCurrent(Minecraft client) {
        User session = client.getUser();
        if (session == null) return "拿不到当前登录信息";

        String type = session.getAccessToken().isEmpty() ? "离线（没有登录令牌）" : "有登录令牌";
        return session.getName() + "（UUID " + session.getProfileId() + "，" + type + "）";
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    public static void tick(Minecraft client) {
        if (client == null || stage == Stage.IDLE) return;

        if (stage == Stage.START) {
            start(client);
        } else if (stage == Stage.WAIT) {
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            reconnect(client);
        }
    }

    /** 换身份（只换账户时）+ 下线。 */
    private static void start(Minecraft client) {
        boolean switchAccount = switchingAccount;
        String name = pendingName;
        target = captureTarget(client);
        if (switchAccount) applyOffline(client, name);
        ActionManager.get().cancelAll();

        if (target == null) {
            boolean wasInWorld = client.level != null || client.getSingleplayerServer() != null;
            if (!wasInWorld) {
                ChatUtils.info(switchAccount
                    ? "已切换到离线账户 " + name + "（当前不在世界里，没有重新加入）"
                    : "当前不在世界里，不用重新登录");
            } else {
                ChatUtils.info(switchAccount
                    ? "已切换到离线账户 " + name + "，但没能确定要回到哪个世界，需要你自己重新进入"
                    : "没能确定要回到哪个世界，需要你自己重新进入");
            }
            LOG.info("{}：没有重连目标（wasInWorld={}）",
                switchAccount ? "切换到离线账户 " + name : "重新登录", wasInWorld);
            reset();
            return;
        }

        ChatUtils.info(switchAccount
            ? "已切换到离线账户 " + name + "，正在下线……"
            : "正在退出到主界面，然后重新登录……");
        LOG.info("{}，下线后重连目标：{}",
            switchAccount ? "切换到离线账户 " + name : "重新登录（账户不变）",
            target.isServer() ? "服务器 " + target.server().ip : "存档 " + target.levelName());
        stage = Stage.WAIT;
        waitTicks = WAIT_TICKS;
        try {
            // 和「保存并退出」按钮同一个方法：单人游戏里它会等存档写完、服务端线程退出再返回
            client.disconnectFromWorld(Component.literal(switchAccount
                ? "正在切换到离线账户 " + name
                : "正在重新登录"));
        } catch (Throwable t) {
            ChatUtils.error("下线失败：" + t);
            LOG.error("下线失败", t);
            reset();
        }
    }

    /** 重新加入下线前所在的世界。 */
    private static void reconnect(Minecraft client) {
        Target where = target;
        reset();

        if (client.level != null || client.player != null || client.getSingleplayerServer() != null) {
            ChatUtils.error("已经重新进世界了，放弃自动重连（记得检查一下当前身份）");
            LOG.warn("放弃自动重连：已经在世界里了");
            return;
        }

        Screen parent = client.gui.screen() != null ? client.gui.screen() : new TitleScreen();

        if (where.isServer()) {
            ServerData info = where.server();
            if (!ServerAddress.isValidAddress(info.ip)) {
                ChatUtils.error("服务器地址看起来不对，没法自动重连：" + info.ip);
                LOG.warn("服务器地址无效，没法自动重连：{}", info.ip);
                return;
            }
            ChatUtils.info("正在重新加入服务器 " + info.ip + " ……");
            LOG.info("重新加入服务器 {}", info.ip);
            ConnectScreen.startConnecting(parent, client, ServerAddress.parseString(info.ip), info, false, null);
        } else {
            ChatUtils.info("正在重新加入存档 " + where.levelName() + " ……");
            LOG.info("重新加入存档 {}", where.levelName());
            cancelReported = false;
            client.createWorldOpenFlows().openWorld(where.levelName(),
                () -> reportCancel(where.levelName()));
        }
    }

    /**
     * 存档加载失败的取消回调。
     *
     * <p>MC 在「加载失败」那个界面上会反复回调它（日志里一次失败能看到好几条），这里只报一次。
     */
    private static void reportCancel(String levelName) {
        if (cancelReported) return;
        cancelReported = true;
        ChatUtils.error("重新加入存档失败（存档目录名 " + levelName + "），需要你自己从世界列表进去");
        LOG.error("重新加入存档失败：{}", levelName);
    }

    /** 进世界之后补一条「用的是哪个账户」。 */
    public static void onJoin(Minecraft client) {
        if (announceName == null) return;
        ChatUtils.info("已用离线账户 " + announceName + " 进入世界（UUID "
            + client.getUser().getProfileId() + "）");
        LOG.info("已用离线账户 {} 进入世界（UUID {}）", announceName, client.getUser().getProfileId());
        announceName = null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 换掉身份相关的字段。
     *
     * <p>{@code gameProfileFuture} 是启动时按旧账户去查的档案 future，
     * 留着的话 {@code getGameProfile()} 会一直返回旧账户，所以这里直接换成「没有档案」，
     * 让 {@code getGameProfile()} 按新 session 现拼一个离线档案。
     */
    private static void applyOffline(Minecraft client, String name) {
        User offline = new User(name, UUIDUtil.createOfflinePlayerUUID(name), "",
            Optional.empty(), Optional.empty());

        MinecraftClientAccessor accessor = (MinecraftClientAccessor) client;
        accessor.playerController$setUser(offline);
        accessor.playerController$setUserApiService(UserApiService.OFFLINE);
        // 离线账户没有聊天签名密钥，用原版给「没有密钥」准备的那个实现
        accessor.playerController$setProfileKeyPairManager(ProfileKeyPairManager.EMPTY_KEY_MANAGER);
        // 26.3 的 PlayerSocialManager 要 FriendsService / RemoteFriendListUpdateHandler 才能构造，
        // 这里不再重建：离线账户本来就连不上好友服务，留着旧的实例不影响游戏
        // （1.21.11 里是 new PlayerSocialManager(client, UserApiService.OFFLINE)）

        announceName = name;
    }

    /** 记下要回到哪里：单人存档优先（大厅主机的世界也算），其次是服务器。 */
    private static Target captureTarget(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server != null) {
            String dir = worldDirectoryName(server);
            return dir == null ? null : new Target(dir, null);
        }

        ServerData info = client.getCurrentServer();
        if (info == null) return null;
        if (info.ip == null || info.ip.isEmpty()) {
            ChatUtils.error("拿不到服务器地址，没法自动重连");
            return null;
        }
        return new Target(null, info);
    }

    /**
     * 单人存档的目录名（就是存档文件夹的名字，中文名也没问题）。
     *
     * <p>注意 {@code LevelResource.ROOT} 的相对路径是 {@code "."}，所以
     * {@code getSavePath(ROOT)} 拿到的是 {@code saves/<存档>/.}，它的 {@code getFileName()} 是
     * {@code "."}，不能当存档名用（拿它去开会去找 {@code saves/level.dat}）。
     * 这里从 {@code level.dat} 往上推一层拿目录名，中文名原样返回。
     */
    private static String worldDirectoryName(IntegratedServer server) {
        Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).toAbsolutePath().normalize();
        Path dir = levelDat.getParent();
        if (dir == null || dir.getFileName() == null) {
            ChatUtils.error("拿不到单人存档的目录名，没法自动重连");
            return null;
        }
        String name = dir.getFileName().toString();
        if (name.isEmpty() || ".".equals(name)) {
            ChatUtils.error("单人存档目录名看起来不对（" + name + "），没法自动重连");
            return null;
        }
        // 兜底：确认这个目录里真的有 level.dat，免得点错路径之后弹一堆报错界面
        if (!Files.isRegularFile(levelDat)) {
            ChatUtils.error("找不到 " + levelDat + "，没法自动重连");
            return null;
        }
        return name;
    }

    private static void reset() {
        stage = Stage.IDLE;
        switchingAccount = false;
        pendingName = null;
        target = null;
        waitTicks = 0;
    }
}
