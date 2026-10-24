package org.six_coin.playerController.client.attack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;
import org.six_coin.playerController.client.waypoint.WaypointManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 攻击的开关和「现在用哪份 profile」，记在 {@code config/player-controller/world_<n>/attack.json}：
 *
 * <pre>
 * {"profile": "test", "enable": true}
 * </pre>
 *
 * <p>为什么放在 level 目录下：不同存档 / 服务器用的 profile 和开关不一样，
 * 换世界（{@code /pc config level <n>}）会自动换成那一份。
 *
 * <p>profile 本身是全局的，放在 {@code config/player-controller/attack/<名字>.json}
 * （见 {@link AttackProfiles} / {@link AttackProfile}）。
 */
public final class AttackConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String FILE_NAME = "attack.json";

    private static final AttackConfig INSTANCE = new AttackConfig();

    /** 盘上那一份。 */
    private static final class Data {
        @SerializedName("profile")
        private String profile = AttackProfile.DEFAULT_NAME;

        @SerializedName("enable")
        private boolean enable = false;
    }

    private Data data = new Data();
    private int loadedWorld = -1;
    private boolean loadedOk = false;

    /** 当前 profile 的对象（读不出来是 null）。 */
    @Nullable
    private AttackProfile profile;

    /** {@link #profile} 是按哪个名字读出来的（失败的也记着，免得每 tick 重试一次刷屏）。 */
    @Nullable
    private String profileName;

    private AttackConfig() {
    }

    public static AttackConfig get() {
        return INSTANCE;
    }

    public static Path file(int level) {
        return WaypointManager.worldDir(level).resolve(FILE_NAME);
    }

    // ------------------------------------------------------------------
    // 读 / 写
    // ------------------------------------------------------------------

    public void load() {
        load(PlayerControllerConfig.getWorld());
    }

    public void load(int level) {
        level = Math.max(1, level);
        loadedWorld = level;
        loadedOk = false;
        data = new Data();
        releaseProfile();

        Path path = file(level);
        if (!Files.exists(path)) {
            loadedOk = true;
            save();
            ChatUtils.debug("已创建攻击配置：" + path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            if (loaded != null) data = loaded;

            if (!AttackProfiles.isValidName(data.profile)) {
                ChatUtils.error("attack.json 里的 profile 名字不合法（" + data.profile
                    + "），改用 " + AttackProfile.DEFAULT_NAME);
                data.profile = AttackProfile.DEFAULT_NAME;
            }
            loadedOk = true;
            ChatUtils.debug("已读取攻击配置：" + path + "（profile=" + data.profile
                + ", enable=" + data.enable + "）");
        } catch (Exception e) {
            loadedOk = false;
            ChatUtils.error("读 " + path + " 失败：" + e.getMessage() + " —— 这一轮不会覆盖原文件");
        }
    }

    public void switchWorld(int level) {
        save();
        load(level);
        ChatUtils.info("攻击配置已切到 world_" + level + "（profile=" + profileName()
            + ", " + (enable() ? "开" : "关") + "）");
    }

    public void save() {
        if (loadedWorld < 0 || !loadedOk) return;

        Path path = file(loadedWorld);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            ChatUtils.error("保存 " + path + " 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 查 / 改
    // ------------------------------------------------------------------

    public int loadedWorld() {
        return loadedWorld;
    }

    /** 当前 profile 的名字。 */
    public String profileName() {
        return data.profile;
    }

    /** 攻击开着没。 */
    public boolean enable() {
        return data.enable;
    }

    /** 开 / 关攻击（立刻存盘）。 */
    public void setEnable(boolean value) {
        data.enable = value;
        save();
    }

    /**
     * 换成另一份 profile（立刻存盘）。
     *
     * @return 那份 profile 不存在返回 false（什么都不改）
     */
    public boolean switchProfile(String name) {
        if (!AttackProfiles.exists(name)) return false;

        data.profile = name;
        releaseProfile();
        save();
        return true;
    }

    /**
     * 当前 profile 的内容。
     *
     * <p>文件不在就照默认值建一份出来；文件坏了返回 null（不会拿默认值顶上去）。
     */
    @Nullable
    public AttackProfile currentProfile() {
        String name = data.profile;

        // 名字同时是文件名：别让手改出来的名字跑出 attack 目录
        if (!AttackProfiles.isValidName(name)) {
            ChatUtils.error("profile 名字不合法（" + name + "），按 "
                + AttackProfile.DEFAULT_NAME + " 算");
            name = AttackProfile.DEFAULT_NAME;
        }

        if (name.equals(profileName)) return profile;

        profile = AttackProfile.load(name);
        profileName = name;
        return profile;
    }

    /** 下一次要的时候重新读一遍（命令改完配置用它）。 */
    public void releaseProfile() {
        profile = null;
        profileName = null;
    }
}
