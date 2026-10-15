package org.six_coin.playerController.client.feature;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.WorldSavePath;
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
 * {@code /pc account <player_id>}：把当前身份换成这个离线账户，然后重新加入刚才的世界。
 *
 * <p>流程分三步，都是分 tick 做的：
 * <ol>
 *   <li>{@code /pc account} 只做校验和登记（{@link #request}），真正下线放到下一个 tick ——
 *       命令是在聊天栏回调里执行的，直接在里面走「保存世界 → 断开 → 重连」容易踩重入；</li>
 *   <li>下一个 tick 换掉 session 等身份字段，然后调用原版的下线流程
 *       （和「保存并退出」按钮同一个方法：它会等存档写完、服务端线程真正退出再返回，
 *       所以返回之后存档锁已经放开了，可以马上重新进同一个世界）；</li>
 *   <li>等 {@link #WAIT_TICKS} tick 之后，按下线前记下来的目标重连：
 *       服务器就重新连那个地址，单人游戏就重新打开那个存档目录。</li>
 * </ol>
 *
 * <p>注意：单人游戏里玩家存档是按 UUID 存的，换成离线账户就是换了一个 UUID，
 * 所以重新进世界时通常是一个「新玩家」（出生点开局、背包是空的），除非以前就用这个离线名字
 * 玩过这个存档。这是换账户本身决定的。
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
    private record Target(String levelName, ServerInfo server) {

        boolean isServer() {
            return server != null;
        }
    }

    private static Stage stage = Stage.IDLE;
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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return "客户端还没启动";

        Session session = client.getSession();
        if (session != null
            && playerName.equals(session.getUsername())
            && session.getAccessToken().isEmpty()) {
            return "当前已经是以离线账户 " + playerName + " 的身份登录了";
        }

        pendingName = playerName;
        stage = Stage.START;
        LOG.info("请求切换到离线账户 {}（当前 {}）", playerName, describeCurrent(client));
        return null;
    }

    /** 校验离线名字；返回错误信息，{@code null} 表示合法。 */
    public static String validateName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "要写玩家名字，例如：/pc account Steve";
        }
        if (!VALID_NAME.matcher(playerName).matches()) {
            return "离线名字只能是 3~16 个字母、数字、下划线（原版服务端就是这么校验的），这个名字不行："
                + playerName;
        }
        return null;
    }

    /** 当前身份的一行描述，给 {@code /pc account} 不带参数时看。 */
    public static String describeCurrent(MinecraftClient client) {
        Session session = client.getSession();
        if (session == null) return "拿不到当前登录信息";

        String type = session.getAccessToken().isEmpty() ? "离线（没有登录令牌）" : "有登录令牌";
        return session.getUsername() + "（UUID " + session.getUuidOrNull() + "，" + type + "）";
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    public static void tick(MinecraftClient client) {
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

    /** 换身份 + 下线。 */
    private static void start(MinecraftClient client) {
        String name = pendingName;
        target = captureTarget(client);
        applyOffline(client, name);
        ActionManager.get().cancelAll();

        if (target == null) {
            boolean wasInWorld = client.world != null || client.getServer() != null;
            ChatUtils.info(wasInWorld
                ? "已切换到离线账户 " + name + "，但没能确定要回到哪个世界，需要你自己重新进入"
                : "已切换到离线账户 " + name + "（当前不在世界里，没有重新加入）");
            LOG.info("切换到离线账户 {}：没有重连目标（wasInWorld={}）", name, wasInWorld);
            reset();
            return;
        }

        ChatUtils.info("已切换到离线账户 " + name + "，正在下线……");
        LOG.info("切换到离线账户 {}，下线后重连目标：{}", name, target.isServer()
            ? "服务器 " + target.server().address
            : "存档 " + target.levelName());
        stage = Stage.WAIT;
        waitTicks = WAIT_TICKS;
        try {
            // 和「保存并退出」按钮同一个方法：单人游戏里它会等存档写完、服务端线程退出再返回
            client.disconnect(Text.literal("正在切换到离线账户 " + name));
        } catch (Throwable t) {
            ChatUtils.error("下线失败：" + t);
            LOG.error("下线失败", t);
            reset();
        }
    }

    /** 重新加入下线前所在的世界。 */
    private static void reconnect(MinecraftClient client) {
        Target where = target;
        reset();

        if (client.world != null || client.player != null || client.getServer() != null) {
            ChatUtils.error("已经重新进世界了，放弃自动重连（记得检查一下当前身份）");
            LOG.warn("放弃自动重连：已经在世界里了");
            return;
        }

        Screen parent = client.currentScreen != null ? client.currentScreen : new TitleScreen();

        if (where.isServer()) {
            ServerInfo info = where.server();
            if (!ServerAddress.isValid(info.address)) {
                ChatUtils.error("服务器地址看起来不对，没法自动重连：" + info.address);
                LOG.warn("服务器地址无效，没法自动重连：{}", info.address);
                return;
            }
            ChatUtils.info("正在重新加入服务器 " + info.address + " ……");
            LOG.info("重新加入服务器 {}", info.address);
            ConnectScreen.connect(parent, client, ServerAddress.parse(info.address), info, false, null);
        } else {
            ChatUtils.info("正在重新加入存档 " + where.levelName() + " ……");
            LOG.info("重新加入存档 {}", where.levelName());
            cancelReported = false;
            client.createIntegratedServerLoader().start(where.levelName(),
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
    public static void onJoin(MinecraftClient client) {
        if (announceName == null) return;
        ChatUtils.info("已用离线账户 " + announceName + " 进入世界（UUID "
            + client.getSession().getUuidOrNull() + "）");
        LOG.info("已用离线账户 {} 进入世界（UUID {}）", announceName, client.getSession().getUuidOrNull());
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
    private static void applyOffline(MinecraftClient client, String name) {
        Session offline = new Session(name, Uuids.getOfflinePlayerUuid(name), "",
            Optional.empty(), Optional.empty());

        MinecraftClientAccessor accessor = (MinecraftClientAccessor) client;
        accessor.playerController$setSession(offline);
        accessor.playerController$setGameProfileFuture(CompletableFuture.completedFuture(null));
        accessor.playerController$setUserApiService(UserApiService.OFFLINE);
        // 离线账户没有聊天签名密钥，用原版给「没有密钥」准备的那个实现
        accessor.playerController$setProfileKeys(ProfileKeys.MISSING);
        accessor.playerController$setSocialInteractionsManager(
            new SocialInteractionsManager(client, UserApiService.OFFLINE));

        announceName = name;
    }

    /** 记下要回到哪里：单人存档优先（大厅主机的世界也算），其次是服务器。 */
    private static Target captureTarget(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server != null) {
            String dir = worldDirectoryName(server);
            return dir == null ? null : new Target(dir, null);
        }

        ServerInfo info = client.getCurrentServerEntry();
        if (info == null) return null;
        if (info.address == null || info.address.isEmpty()) {
            ChatUtils.error("拿不到服务器地址，没法自动重连");
            return null;
        }
        return new Target(null, info);
    }

    /**
     * 单人存档的目录名（就是存档文件夹的名字，中文名也没问题）。
     *
     * <p>注意 {@code WorldSavePath.ROOT} 的相对路径是 {@code "."}，所以
     * {@code getSavePath(ROOT)} 拿到的是 {@code saves/<存档>/.}，它的 {@code getFileName()} 是
     * {@code "."}，不能当存档名用（拿它去开会去找 {@code saves/level.dat}）。
     * 这里从 {@code level.dat} 往上推一层拿目录名，中文名原样返回。
     */
    private static String worldDirectoryName(IntegratedServer server) {
        Path levelDat = server.getSavePath(WorldSavePath.LEVEL_DAT).toAbsolutePath().normalize();
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
        pendingName = null;
        target = null;
        waitTicks = 0;
    }
}
