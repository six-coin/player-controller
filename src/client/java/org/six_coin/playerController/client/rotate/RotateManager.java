package org.six_coin.playerController.client.rotate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.feature.MovePackets;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 自动转头的调度：**所有**自动转头都经过这里。
 *
 * <p>转的方式很特别：**只让服务端以为我们在看别处，客户端自己的视角一动不动**。
 * 也就是说服务端以为我们正看着那个方块 / 那只生物，而玩家屏幕上看到的还是自己原本看的方向。
 *
 * <p><b>怎么发</b>：不自己发 {@code Rot} 包，而是把朝向<b>写进原版这一 tick 本来就要发的移动包</b>
 * （见 {@link MovePackets#overrideRotation}）—— 需要的话原版那个 {@code Pos} 会被换成
 * {@code PosRot}，反正一个 tick 只出去一个包。客户端从来不调 {@code player.setYaw / setPitch}。
 *
 * <p>为什么不能自己发 {@code Rot}：26.3 的服务端一个 tick 只准有一个<b>带位置</b>的移动包，
 * 而别的模组（比如 Meteor 的 Flight 防踢）会把 {@code Rot} 包<b>升级成带位置的包</b>再发出去。
 * 一旦这一 tick 原版自己也发了一个位置包，服务端就判 {@code Invalid move player packet received}
 * 直接断开 —— {@code /pc mine simple} 就是因为这个秒掉线的。
 *
 * <p><b>角度没变就不改</b>（见 {@link #rotateTo}）：原版自己的移动包也是「视角真的变了才发」。
 * 多发的移动包只能白带一遍 onGround，而别的模组（比如 anti-hunger）会把这个 onGround 改成
 * false，服务端就会以为我们悬空 —— 原版判扫击（剑气）要求服务端认为我们站在地上，
 * 所以这种多余的包还会把剑气弄没。
 *
 * <p>{@link #release} 不发任何包：服务端那边保持最后那个朝向，
 * 等玩家自己动鼠标时自然会盖掉它。
 *
 * <p>谁能抢谁见 {@link Owner#priority()}。
 */
public final class RotateManager {

    private static final RotateManager INSTANCE = new RotateManager();

    /** 现在谁在用，没人用是 null。 */
    @Nullable
    private Owner owner;

    /** 请求的朝向。 */
    private float yaw;
    private float pitch;

    /** 现在有没有生效中的请求。 */
    private boolean active;

    /** 发过没有（没发过的时候 {@link #sentYaw} / {@link #sentPitch} 是 0，不能拿来比）。 */
    private boolean sent;

    /** 上一次真的发出去的朝向。 */
    private float sentYaw;
    private float sentPitch;

    private RotateManager() {
    }

    public static RotateManager get() {
        return INSTANCE;
    }

    @Nullable
    public Owner owner() {
        return owner;
    }

    public boolean isActive() {
        return active;
    }

    /** 现在让服务端以为在看的 yaw（没生效时就是客户端真实 yaw）。 */
    public float yaw() {
        LocalPlayer player = Minecraft.getInstance().player;
        return active || player == null ? yaw : player.getYRot();
    }

    /** 现在让服务端以为在看的 pitch。 */
    public float pitch() {
        LocalPlayer player = Minecraft.getInstance().player;
        return active || player == null ? pitch : player.getXRot();
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /** 让服务端以为我们在看某个方块的中心。 */
    public boolean rotateTo(Owner requester, BlockPos pos) {
        return rotateTo(requester, Vec3.atCenterOf(pos));
    }

    /** 让服务端以为我们在看空间里的某个点。 */
    public boolean rotateTo(Owner requester, Vec3 target) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;

        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return rotateTo(requester, yaw, pitch);
    }

    /**
     * 直接给朝向。
     *
     * <p>和上一次发出去的角度一样就什么都不发 —— 服务端那边记着的就是这个角度，
     * 没必要每 tick 重复一遍。（对着不动的东西的时候，一次动作只会有开头那一个包。）
     *
     * @return {@code false} = 这次不让转（别人用着，而且优先级更高）
     */
    public boolean rotateTo(Owner requester, float yaw, float pitch) {
        if (owner != null && owner != requester && owner.priority() > requester.priority()) {
            ChatUtils.debug("不让转头：现在是 " + owner + " 在用");
            return false;
        }

        float wrapped = Mth.wrapDegrees(yaw);
        float clamped = Mth.clamp(pitch, -90.0F, 90.0F);

        this.owner = requester;
        this.yaw = wrapped;
        this.pitch = clamped;
        this.active = true;

        if (!sent || wrapped != sentYaw || clamped != sentPitch) {
            // 不额外发 Rot 包：把朝向塞进原版这一 tick 本来就要发的移动包（见 MovePackets）。
            // 26.3 一个 tick 只准有一个带位置的包，多发的那些会被 Meteor 之类的模组
            // 升级成带位置的包，直接把人踢下线（Invalid move player packet received）。
            MovePackets.overrideRotation(wrapped, clamped);
            MovePackets.forceNext();
            sentYaw = wrapped;
            sentPitch = clamped;
            sent = true;
        }
        return true;
    }

    /**
     * 把当前生效的朝向**再发一遍**（不管上次是不是已经发过）。
     *
     * <p>给「靠服务端记着的朝向决定结果」的操作用：扔东西（{@code dropItem} 用服务端的 yaw / pitch
     * 算速度）、用桶（服务端会拿包里带的 yaw / pitch 覆盖自己的朝向再射线）……
     * 这些操作之前重发一次，就不用管中间有没有别的东西把服务端的朝向改掉了。
     *
     * <p>这里必须**马上**发出去（后面紧跟的 {@code use_item} / {@code drop} 包要靠它排前面），
     * 所以走的是真包 {@code Rot}。它不带坐标，不受「一个 tick 一个位置包」的限制。
     * 同时也把朝向记成「待写入」，万一这一包被别人改掉了，下一包还会带上。
     *
     * @return {@code false} = 现在没有生效中的朝向（没人请求过转头）
     */
    public boolean resend() {
        if (!active) return false;
        MovePackets.overrideRotation(yaw, pitch);
        send(yaw, pitch);
        sentYaw = yaw;
        sentPitch = pitch;
        sent = true;
        return true;
    }

    /** 用完还回去（不发包：服务端保持最后那个朝向）。 */
    public void release(Owner requester) {
        if (owner != requester) return;
        ChatUtils.debug("不再转头（" + requester + "）");
        clear();
    }

    /** 进世界 / 断线的时候清一下（不发任何包）。 */
    public void reset() {
        clear();
    }

    private void clear() {
        owner = null;
        active = false;
        sent = false;
    }

    // ------------------------------------------------------------------

    /**
     * 马上发一个只带朝向的包（客户端视角不动）。
     *
     * <p>只有 {@link #resend()} 用它：那个场景要求朝向包<b>排在后面的 use_item / drop 包前面</b>，
     * 不能等原版下一 tick 的移动包。{@code Rot} 不带坐标，本身不违反「一个 tick 一个位置包」。
     */
    private static void send(float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) return;

        player.connection.send(new ServerboundMovePlayerPacket.Rot(
            yaw, pitch, player.onGround(), player.horizontalCollision));
    }
}
