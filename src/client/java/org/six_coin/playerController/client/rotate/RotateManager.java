package org.six_coin.playerController.client.rotate;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 自动转头的调度：**所有**自动转头都经过这里。
 *
 * <p>转的方式很特别：**只给服务端发转头包，客户端自己的视角一动不动**。
 * 也就是说服务端以为我们正看着那个方块 / 那只生物，而玩家屏幕上看到的还是自己原本看的方向。
 *
 * <p>做法就是发 {@code PlayerMoveC2SPacket.LookAndOnGround}（只带 yaw / pitch，
 * 不带坐标，所以不会影响位置校验），客户端从来不调 {@code player.setYaw / setPitch}。
 *
 * <p><b>角度没变就不发</b>（见 {@link #rotateTo}）：原版自己的移动包也是「视角真的变了才发」。
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
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return active || player == null ? yaw : player.getYaw();
    }

    /** 现在让服务端以为在看的 pitch。 */
    public float pitch() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return active || player == null ? pitch : player.getPitch();
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /** 让服务端以为我们在看某个方块的中心。 */
    public boolean rotateTo(Owner requester, BlockPos pos) {
        return rotateTo(requester, Vec3d.ofCenter(pos));
    }

    /** 让服务端以为我们在看空间里的某个点。 */
    public boolean rotateTo(Owner requester, Vec3d target) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;

        Vec3d eye = player.getEyePos();
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

        float wrapped = MathHelper.wrapDegrees(yaw);
        float clamped = MathHelper.clamp(pitch, -90.0F, 90.0F);

        this.owner = requester;
        this.yaw = wrapped;
        this.pitch = clamped;
        this.active = true;

        if (!sent || wrapped != sentYaw || clamped != sentPitch) {
            send(wrapped, clamped);
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
     * @return {@code false} = 现在没有生效中的朝向（没人请求过转头）
     */
    public boolean resend() {
        if (!active) return false;
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

    /** 只发转头包：客户端视角不动。 */
    private static void send(float yaw, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || player.networkHandler == null) return;

        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            yaw, pitch, player.isOnGround(), player.horizontalCollision));
    }
}
