package org.six_coin.playerController.client.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 悬停（假飞行）的时候，别让「反飞行检测」把视角抖来抖去。
 *
 * <p><b>症状</b>：{@code /pc mine debug_chunk_miner}（或者 Meteor 的 Flight 用 velocity 模式）时，
 * 视角每秒左右摆十几次、幅度一两度，**只有水平方向**，跟朝哪边没关系。
 *
 * <p><b>根因在别的模组身上，但只有「悬停」时才露出来</b>。Meteor 的 {@code Flight} 里有一段反检测：
 *
 * <pre>
 * // onPreTick
 * float currentYaw = player.getYRot();
 * if (player.fallDistance >= 3 &amp;&amp; currentYaw == lastYaw &amp;&amp; player.getDeltaMovement().length() &lt; 0.003) {
 *     player.setYRot(currentYaw + (flip ? 1 : -1));   // 假装在转头
 *     flip = !flip;
 * }
 * lastYaw = currentYaw;
 *
 * // onPostTick
 * if (player.getYRot() != lastYaw) player.setYRot(lastYaw);   // 再改回来
 * </pre>
 *
 * <p>服务端看到的是「玩家在转头」，客户端这边却出了岔子：原版
 * {@code Entity#commonTick()} 里的 {@code setOldPosAndRot()} 正好落在 Pre 和 Post 之间，
 * 所以 {@code yRotO} 记下的是<b>被改过</b>的角度，而 {@code yRot} 又被改回来了。
 * 渲染时 {@code Entity#getViewYRot(partialTick)} 在 {@code yRotO} 和 {@code yRot} 之间插值，
 * 于是镜头每 tick 从 {@code Y±1} 滑回 {@code Y}，看起来就是一直在左右摆（一来一回约 2°）。
 *
 * <p><b>为什么 Abilities 模式没事</b>：那种模式下 {@code abilities.flying = true}，
 * 玩家是真在飞，{@code fallDistance} 一直是 0，上面的条件根本进不去。
 * velocity 模式会把 {@code abilities.flying} 关掉、每 tick 把速度清零，人停在半空，
 * {@code fallDistance} 却一直留着之前掉下来的那个值（≥3），条件就一直成立。
 *
 * <p><b>怎么修</b>：既然触发条件是 {@code fallDistance >= 3}，那就在「悬空且几乎没在动」的时候
 * 把它压回 0 —— 条件不成立，反检测就不会去改朝向，视角自然不抖了。
 * 服务端那边的「浮空太久」由 {@link AntiKick} 的补包负责，这边只影响客户端自己的插值。
 *
 * <p>真的在往下掉（竖直速度超过 {@link #STILL_SPEED}）的时候不动它，
 * 免得影响落地摔伤的客户端预测（服务端本来也是自己算的）。
 */
public final class HoverJitter {

    /**
     * 竖直速度小于这个值就算「悬停」。
     *
     * <p>原版重力一 tick 是 0.078 格，velocity 模式悬停时（把速度清零之前）就是这个量级；
     * 真掉起来两 tick 就超过 0.15 了，所以这个门槛刚好把「悬停」和「在掉」分开。
     */
    private static final double STILL_SPEED = 0.15;

    /** {@link #fixInterpolationDrift} 只处理这么小的偏差（反检测抖的就是 1 度）。 */
    private static final float MAX_DRIFT = 1.0F;

    /** 上一次 tick 有没有压过（只用来避免刷屏日志）。 */
    private static boolean clamping;

    private HoverJitter() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) {
            clamping = false;
            return;
        }

        fixInterpolationDrift(player);

        if (!shouldClamp(player)) {
            if (clamping) {
                ChatUtils.debug("悬停结束：不再压 fallDistance");
                clamping = false;
            }
            return;
        }

        player.fallDistance = 0.0;

        if (!clamping) {
            ChatUtils.debug("悬停中：把 fallDistance 压回 0（免得反飞行检测每 tick 抖一下视角）");
            clamping = true;
        }
    }

    /**
     * 兜底：把「这一 tick 里被改过又改回来」的朝向差补上。
     *
     * <p>{@code yRotO} 是渲染插值的起点。要是有人（比如上面说的反检测）在 tick 中段把朝向
     * 改了、又在 tick 末尾改回来，{@code yRotO} 就会停在被改过的那个值上，镜头每帧都要从
     * 那儿往真实角度滑 —— 看起来就是抖。
     *
     * <p>只在差值 ≤ {@link #MAX_DRIFT} 的时候动手：正常转鼠标一 tick 远不止 1 度，
     * 所以不会影响正常的视角平滑（差值这么小的时候补不补看不出来）。
     */
    private static void fixInterpolationDrift(LocalPlayer player) {
        float drift = Mth.wrapDegrees(player.yRotO - player.getYRot());
        if (drift == 0.0F || Math.abs(drift) > MAX_DRIFT) return;
        player.yRotO = player.getYRot();
    }

    private static boolean shouldClamp(LocalPlayer player) {
        if (player.fallDistance <= 0.0) return false;             // 本来就是 0，不用动
        if (player.onGround() || player.isPassenger()) return false;
        if (player.isInWater() || player.isInLava()) return false;
        // 只要不是在真往下掉（往上升也算），就算悬停
        return player.getDeltaMovement().y > -STILL_SPEED;
    }

    /** 进世界 / 断线的时候清一下状态。 */
    public static void reset() {
        clamping = false;
    }
}
