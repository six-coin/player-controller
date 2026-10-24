package org.six_coin.playerController.client.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.mixin.LocalPlayerAccessor;
import org.six_coin.playerController.client.mixin.ServerboundMovePlayerPacketAccessor;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 26.3 的「一个 tick 只能发一个带位置的移动包」规则。
 *
 * <p><b>服务端到底怎么判</b>（反编译 {@code ServerGamePacketListenerImpl} 得到的）：
 *
 * <pre>
 * public void handleMovePlayer(ServerboundMovePlayerPacket packet) {
 *     if (containsInvalidValues(x, y, z, yRot, xRot)) {                     // 有 NaN / Infinity
 *         disconnect("multiplayer.disconnect.invalid_player_movement");     // ← 断开
 *     }
 *     if (packet.hasPosition()) {
 *         if (this.receivedPositionThisTick) {
 *             disconnect("multiplayer.disconnect.invalid_player_movement"); // ← 断开
 *         }
 *         this.receivedPositionThisTick = true;
 *     }
 *     ...
 * }
 *
 * public void handleClientTickEnd(ServerboundClientTickEndPacket packet) {
 *     this.receivedPositionThisTick = false;      // ← 「一个 tick」就是在这里重新开始的
 * }
 * </pre>
 *
 * <p>两个要点：
 * <ol>
 *   <li>只带朝向的包（{@code Rot} / {@code StatusOnly}）<b>不受</b>这条限制，随便发；</li>
 *   <li>「一个 tick」指的是 <b>{@code ClientTickEnd} 包之间的那一段</b>，不是客户端 tick。
 *       这一点很坑：{@code Minecraft#tick()} 里 {@code ClientTickEnd} 是在<b>中段</b>发出去的
 *       （在 {@code END_CLIENT_TICK} 回调之前），而原版 {@code LocalPlayer.sendPosition()}
 *       是在 {@code player.tick()} 里发的：</li>
 * </ol>
 *
 * <pre>
 * player.tick() 发位置包 ──────┐
 * ClientTickEnd ───────────────┴─ 服务端在这里把 receivedPositionThisTick 清零
 * END_CLIENT_TICK 回调（挖掘 / 攻击 / 防踢都在这里）──┐
 * START_CLIENT_TICK 回调                              ├─ 这个窗口
 * player.tick() 发位置包 ─────────────────────────────┘
 * ClientTickEnd
 * </pre>
 *
 * <p>所以在 {@code START_CLIENT_TICK} 里清标记是<b>错的</b>：{@code END_CLIENT_TICK}
 * 阶段发出去的包和紧接着 {@code player.tick()} 发出去的包，在服务端看来是同一个 tick 的两个包，
 * 照样被踢。这里改成在 {@code ClientTickEnd} 发出去的时候清（见 {@link #endWindow()}）。
 *
 * <p><b>本类干的事</b>：不再「多发包」，而是把这一窗口里唯一那个位置包<b>改写成我们想要的样子</b>：
 * <ul>
 *   <li>{@link #overrideNext}：把 y / onGround 换成指定值（防踢往下一格用）；</li>
 *   <li>{@link #overrideRotation}：把朝向换成指定值（自动转头用，见 {@code RotateManager}）；</li>
 *   <li>{@link #forceNext}：原版站着不动时一个包都不发，这里逼它发一个；</li>
 *   <li>顺手把 NaN / Infinity 的坐标换掉 —— 那是另一条必踢的路径。</li>
 * </ul>
 *
 * <p>需要朝向、但原版这个包没有朝向字段时（{@code Pos}），会换成 {@code PosRot}。
 * 这样「自动转头」不需要额外发任何包。
 */
public final class MovePackets {

    /** 服务端对坐标的夹取范围（服务端自己也会夹，这里提前夹住，免得触发 moved wrongly）。 */
    private static final double HORIZONTAL_LIMIT = 3.0E7;
    private static final double VERTICAL_LIMIT = 2.0E7;

    /**
     * 服务端窗口号：每发出去一个 {@code ClientTickEnd} 就 +1。
     *
     * <p>服务端就是在收到 {@code ClientTickEnd} 的时候把 {@code receivedPositionThisTick}
     * 清零的，所以「窗口号相同」= 服务端会算成同一个 tick。
     */
    private static int window;

    /** 上一次发<b>带位置</b>的包是在哪个窗口（{@code -1} = 还没发过）。 */
    private static int lastPosWindow = -1;

    /** 兜底：多久没见过 {@code ClientTickEnd} 了（暂停 / 断线时它不会发）。 */
    private static int ticksSinceWindowEnd;

    /** 下一次要用的 y；{@code NaN} = 不改。 */
    private static double pendingY = Double.NaN;

    /** 下一次要用的 onGround。 */
    private static boolean pendingOnGround;

    /** 下一次要用的朝向；{@code NaN} = 不改。 */
    private static float pendingYaw = Float.NaN;
    private static float pendingPitch = Float.NaN;

    /** 上面这些改动到哪个窗口之后作废（防止一直没发包时留到很久以后）。 */
    private static int pendingExpire = -1;

    /** 正在发我们自己拼的替代包（别再进 {@link #filter} 里绕一圈）。 */
    private static boolean replacing;

    private MovePackets() {
    }

    // ------------------------------------------------------------------
    // 窗口
    // ------------------------------------------------------------------

    /**
     * 服务端窗口结束（{@code ClientTickEnd} 发出去的时候调）。
     *
     * <p>必须在这里清标记，理由见类注释。
     */
    public static void endWindow() {
        window++;
        ticksSinceWindowEnd = 0;
    }

    /**
     * 每个客户端 tick 开头调一次（{@code START_CLIENT_TICK}）。
     *
     * <p>这里<b>不</b>清标记 —— 清标记是 {@link #endWindow()} 的活。
     * 只做一件事：万一 {@code ClientTickEnd} 一直没发出去（游戏暂停 / 连接卡住），
     * 连续几个 tick 都没见过窗口结束，就强行换一个窗口，免得我们永远发不出包。
     */
    public static void beginTick() {
        if (ticksSinceWindowEnd < 3) {
            ticksSinceWindowEnd++;
            return;
        }
        window++;
        ticksSinceWindowEnd = 0;
    }

    /** 这个窗口里已经发过带位置的包了吗（给「必须马上发一个 Rot」的地方参考）。 */
    public static boolean positionSentThisWindow() {
        return lastPosWindow == window;
    }

    // ------------------------------------------------------------------
    // 请求改写
    // ------------------------------------------------------------------

    /**
     * 让下一个发出去的移动包把 y 换成 {@code y}、onGround 换成 {@code onGround}。
     *
     * <p>只改包、不改客户端自己的位置 —— 客户端看起来什么都没发生，服务端那边看到的是另一回事。
     */
    public static void overrideNext(double y, boolean onGround) {
        if (!Double.isFinite(y)) return;   // 绝不能把 NaN / Infinity 塞进包里，那才是真的会被踢
        pendingY = y;
        pendingOnGround = onGround;
        pendingExpire = window + 2;
    }

    /**
     * 让下一个发出去的移动包带上这个朝向（只改服务端看到的，客户端视角一动不动）。
     *
     * <p>这就是「自动转头」唯一的出口：不再额外发 {@code Rot} 包，
     * 而是塞进原版本来就要发的那个包（必要时把 {@code Pos} 升级成 {@code PosRot}）。
     */
    public static void overrideRotation(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        pendingYaw = yaw;
        pendingPitch = pitch;
        pendingExpire = window + 2;
    }

    /**
     * 强制下一个 tick 原版发一个移动包。
     *
     * <p>原版 {@code LocalPlayer.sendPosition} 只在「位置变了」或者
     * {@code positionReminder >= 20} 的时候发；站着不动的时候一个包都不发，
     * 那我们改的东西就永远没机会出去。这里把 {@code positionReminder} 顶到 20，
     * 下一个 tick 原版就会老老实实发一包（和 Meteor 的 {@code meteor$setPositionReminder(20)} 一样）。
     */
    public static void forceNext() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ((LocalPlayerAccessor) player).playerController$setPositionReminder(20);
    }

    // ------------------------------------------------------------------
    // 过滤器
    // ------------------------------------------------------------------

    /**
     * 从 {@code ClientPacketSendMixin} 调用：改写 / 丢掉即将发出去的移动包。
     *
     * @return {@code false} = 这一包要丢掉（这个窗口已经发过带位置的包了）
     */
    public static boolean filter(Packet<?> packet) {
        if (replacing) return true;
        if (!(packet instanceof ServerboundMovePlayerPacket move)) return true;

        LocalPlayer player = Minecraft.getInstance().player;

        boolean wantY = !Double.isNaN(pendingY) && window <= pendingExpire;
        boolean wantRot = !Float.isNaN(pendingYaw) && window <= pendingExpire;

        // ---------------- 带位置的包（Pos / PosRot）：一个窗口只准一个 ----------------
        if (move.hasPosition()) {
            if (lastPosWindow == window) return false;   // 服务端会直接断开，这里掐掉
            lastPosWindow = window;

            // 先把坐标修成合法的（NaN / Infinity 会被踢，超出范围会被拉回）
            double x = sane(move.getX(0), player, 0);
            double y = sane(move.getY(0), player, 1);
            double z = sane(move.getZ(0), player, 2);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                // 连玩家自己的坐标都是坏的：这一包干脆不发，比被踢下线强
                ChatUtils.debug("移动包里的坐标是 NaN，这一包丢掉");
                return false;
            }

            if (wantY) {
                y = pendingY;
                setOnGround(move, pendingOnGround);
                clearY();
            }

            if (wantRot && !move.hasRotation()) {
                // Pos 没有朝向字段，换成 PosRot（还是只发一个包）
                sendReplacement(new ServerboundMovePlayerPacket.PosRot(
                    clampHorizontal(x), clampVertical(y), clampHorizontal(z),
                    pendingYaw, pendingPitch, move.isOnGround(), move.horizontalCollision()));
                clearRotation();
                return false;
            }

            writePosition(move, x, y, z);
            writeRotation(move,
                wantRot ? pendingYaw : saneRot(move.getYRot(0), player, true),
                wantRot ? pendingPitch : saneRot(move.getXRot(0), player, false));
            if (wantRot) clearRotation();
            return true;
        }

        // ---------------- 只带朝向的包（Rot）：不参与「一个位置包」的规则 ----------------
        if (move.hasRotation()) {
            float yaw = wantRot ? pendingYaw : saneRot(move.getYRot(0), player, true);
            float pitch = wantRot ? pendingPitch : saneRot(move.getXRot(0), player, false);

            if (wantY && !positionSentThisWindow()) {
                // 原版只想报朝向，但我们要改 y：升级成 PosRot（这才算这个窗口的位置包）
                lastPosWindow = window;
                sendReplacement(new ServerboundMovePlayerPacket.PosRot(
                    clampHorizontal(player == null ? 0.0 : player.getX()),
                    clampVertical(pendingY),
                    clampHorizontal(player == null ? 0.0 : player.getZ()),
                    yaw, pitch, pendingOnGround, move.horizontalCollision()));
                clearPending();
                return false;
            }

            writeRotation(move, yaw, pitch);
            if (wantRot) clearRotation();
            return true;
        }

        // ---------------- 什么都不带的包（StatusOnly） ----------------
        // 它既不报位置也不报朝向，原版只在「站着不动、但 20 tick 到了」的时候发它。
        // 我们要改 y / 报朝向的话，就把它升级成带这些东西的包（还是只发一个）。
        boolean posFree = !positionSentThisWindow();

        if (wantY && posFree) {
            lastPosWindow = window;
            sendReplacement(new ServerboundMovePlayerPacket.PosRot(
                clampHorizontal(player == null ? 0.0 : player.getX()),
                clampVertical(pendingY),
                clampHorizontal(player == null ? 0.0 : player.getZ()),
                wantRot ? pendingYaw : saneRot(0.0F, player, true),
                wantRot ? pendingPitch : saneRot(0.0F, player, false),
                pendingOnGround, move.horizontalCollision()));
            clearY();
            if (wantRot) clearRotation();
            return false;
        }

        if (wantRot) {
            sendReplacement(new ServerboundMovePlayerPacket.Rot(
                pendingYaw, pendingPitch, move.isOnGround(), move.horizontalCollision()));
            clearRotation();
            return false;
        }

        return true;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static void clearY() {
        pendingY = Double.NaN;
    }

    private static void clearRotation() {
        pendingYaw = Float.NaN;
        pendingPitch = Float.NaN;
    }

    private static void clearPending() {
        clearY();
        clearRotation();
        pendingExpire = -1;
    }

    /** 把我们自己拼的包发出去（{@link #replacing} 挡住递归）。 */
    private static void sendReplacement(Packet<?> packet) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        replacing = true;
        try {
            connection.send(packet);
        } finally {
            replacing = false;
        }
    }

    /** 坐标不合法（NaN / Infinity）就换成玩家自己的坐标；玩家也没有就返回 NaN。 */
    private static double sane(double value, @Nullable LocalPlayer player, int axis) {
        if (Double.isFinite(value)) return value;
        if (player == null) return Double.NaN;

        double fallback = axis == 0 ? player.getX() : axis == 1 ? player.getY() : player.getZ();
        if (!Double.isFinite(fallback)) return Double.NaN;
        ChatUtils.debug("移动包里的坐标不是有限值（" + value + "），换成玩家自己的");
        return fallback;
    }

    /** 朝向不合法就换成玩家自己的朝向（玩家也没有就用 0）。 */
    private static float saneRot(float value, @Nullable LocalPlayer player, boolean yaw) {
        if (Float.isFinite(value)) return value;
        if (player == null) return 0.0F;
        float fallback = yaw ? player.getYRot() : player.getXRot();
        return Float.isFinite(fallback) ? fallback : 0.0F;
    }

    private static double clampHorizontal(double value) {
        return Math.max(-HORIZONTAL_LIMIT, Math.min(HORIZONTAL_LIMIT, value));
    }

    private static double clampVertical(double value) {
        return Math.max(-VERTICAL_LIMIT, Math.min(VERTICAL_LIMIT, value));
    }

    private static void writePosition(ServerboundMovePlayerPacket packet, double x, double y, double z) {
        ServerboundMovePlayerPacketAccessor accessor = (ServerboundMovePlayerPacketAccessor) packet;
        accessor.playerController$setX(clampHorizontal(x));
        accessor.playerController$setY(clampVertical(y));
        accessor.playerController$setZ(clampHorizontal(z));
    }

    private static void writeRotation(ServerboundMovePlayerPacket packet, float yaw, float pitch) {
        ServerboundMovePlayerPacketAccessor accessor = (ServerboundMovePlayerPacketAccessor) packet;
        accessor.playerController$setYRot(yaw);
        accessor.playerController$setXRot(pitch);
    }

    private static void setOnGround(ServerboundMovePlayerPacket packet, boolean onGround) {
        ((ServerboundMovePlayerPacketAccessor) packet).playerController$setOnGround(onGround);
    }
}
