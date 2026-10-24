package org.six_coin.playerController.client.mine;

import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 挖掘时「哪些方块要挖」的判定：白名单 / 黑名单 + 一串方块。
 *
 * <p>列表本身存在 {@code config/player-controller/mine/} 下的三份文件里（见 {@link MineFilterConfig}），
 * 开任务的时候取一份**快照**放进任务里：之后改列表不会影响正在跑的任务。
 */
public final class MineFilter {

    /** 一个方块都不排除：黑名单 + 空列表。 */
    public static final MineFilter ALL = new MineFilter(Mode.BLACKLIST, Set.of());

    /** 用哪张表。 */
    public enum Mode {

        WHITELIST("whitelist", "白名单"),
        BLACKLIST("blacklist", "黑名单");

        private final String id;
        private final String label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** 名字 -> 模式，写错就抛 {@link IllegalArgumentException}（消息直接给玩家看）。 */
        public static Mode of(String text) {
            for (Mode mode : values()) {
                if (mode.id.equalsIgnoreCase(text.trim())) return mode;
            }
            throw new IllegalArgumentException("只能是 \"whitelist\" 或 \"blacklist\"，现在是 \""
                + text + "\"");
        }
    }

    private final Mode mode;
    private final Set<Block> blocks;

    private MineFilter(Mode mode, Set<Block> blocks) {
        this.mode = mode;
        this.blocks = blocks;
    }

    /** 用某一刻的列表做一份快照。 */
    public static MineFilter of(Mode mode, Collection<Block> blocks) {
        return new MineFilter(mode,
            Collections.unmodifiableSet(new LinkedHashSet<>(blocks)));
    }

    public Mode mode() {
        return mode;
    }

    public Set<Block> blocks() {
        return blocks;
    }

    /** 这个方块在这一片区域里该不该挖。 */
    public boolean allows(Block block) {
        boolean listed = blocks.contains(block);
        return mode == Mode.WHITELIST ? listed : !listed;
    }

    /** 「黑名单：3 种（minecraft:dirt, …）」这样一行。 */
    public String describe() {
        return mode.label() + "：" + blocks.size() + " 种" + listText(8);
    }

    /** 方块的 id 列表，最多列 {@code limit} 个。 */
    public String listText(int limit) {
        if (blocks.isEmpty()) return "（空）";

        String list = blocks.stream()
            .limit(limit)
            .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
            .collect(Collectors.joining(", "));
        if (blocks.size() > limit) list = list + ", …（还有 " + (blocks.size() - limit) + " 种）";
        return "（" + list + "）";
    }
}
