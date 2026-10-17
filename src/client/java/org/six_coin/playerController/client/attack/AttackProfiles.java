package org.six_coin.playerController.client.attack;

import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.util.ChatUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 全局的攻击配置文件夹：{@code config/player-controller/attack/<名字>.json}。
 *
 * <p>里面有多少个文件就有多少个 profile；当前用哪个记在
 * {@code world_<n>/attack.json} 的 {@code profile} 里（见 {@link AttackConfig}）。
 */
public final class AttackProfiles {

    /** 这些 profile 所在的文件夹。 */
    public static final String FOLDER_NAME = "attack";

    /** 名字同时也是文件名，限制一下别让它跑出 attack 目录。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    private AttackProfiles() {
    }

    public static Path dir() {
        return PlayerControllerConfig.configDir().resolve(FOLDER_NAME);
    }

    public static Path file(String name) {
        return dir().resolve(name + ".json");
    }

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public static boolean exists(String name) {
        return isValidName(name) && Files.exists(file(name));
    }

    /** 所有 profile 的文件名（不含 .json），排好序。 */
    public static List<String> list() {
        Path dir = dir();
        if (!Files.exists(dir)) return List.of();

        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                String name = fileName.substring(0, fileName.length() - 5);
                if (isValidName(name)) names.add(name);
            }
        } catch (IOException e) {
            ChatUtils.error("读 " + dir + " 失败：" + e.getMessage());
            return Collections.emptyList();
        }
        Collections.sort(names);
        return names;
    }

    /** 删掉一份 profile（文件不在返回 false）。 */
    public static boolean delete(String name) {
        if (!isValidName(name)) return false;
        try {
            return Files.deleteIfExists(file(name));
        } catch (IOException e) {
            ChatUtils.error("删 " + file(name) + " 失败：" + e.getMessage());
            return false;
        }
    }
}
