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
 * 原版自己的移动包只有「视角真的变了」的时候才带转头，所以这个假朝向不会被位置包盖掉；
 * 反过来，用户自己动了鼠标就会发一个真朝向的包把它盖掉 —— 想要一直盖着它的人
 * （比如攻击）每个 tick 重新 {@link #rotateTo} 一次就行。
 *
 * <p>请求用完要 {@link #release}：它会把服务端那一头的朝向改回客户端真实朝向。
 * 万一谁忘了 release，{@link #tick()} 也会在请求太久没人刷新的时候自动还回去。
 *
 * <p>谁能抢谁见 {@link Owner#priority()}。
 */
public final class RotateManager {

    private static final RotateManager INSTANCE = new RotateManager();

    /** 请求多久没人刷新就自动还回去（tick）。 */
    private static final int STALE_TICKS = 100;

    /** 现在谁在用，没人用是 null。 */
    @Nullable
    private Owner owner;

    /** 请求的朝向。 */
    private float yaw;
    private float pitch;

    /** 现在有没有生效中的请求。 */
    private boolean active;

    /** 距离上一次 {@link #rotateTo} 过了多少 tick。 */
    private int staleTicks;

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
     * @return {@code false} = 这次不让转（别人用着，而且优先级更高）
     */
    public boolean rotateTo(Owner requester, float yaw, float pitch) {
        if (owner != null && owner != requester && owner.priority() > requester.priority()) {
            ChatUtils.debug("不让转头：现在是 " + owner + " 在用");
            return false;
        }

        this.owner = requester;
        this.yaw = MathHelper.wrapDegrees(yaw);
        this.pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
        this.active = true;
        this.staleTicks = 0;
        send(this.yaw, this.pitch);
        return true;
    }

    /** 用完还回去：把服务端那一头的朝向改回客户端真实朝向。 */
    public void release(Owner requester) {
        if (owner != requester) return;

        owner = null;
        active = false;
        staleTicks = 0;

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        send(player.getYaw(), player.getPitch());
    }

    /** 每个 tick 调一次：请求太久没人刷新就自动还回去（防止谁忘了 release）。 */
    public void tick() {
        if (!active) return;
        if (++staleTicks <= STALE_TICKS) return;

        ChatUtils.debug("转头请求太久没人刷新，自动把服务端的朝向还回去");
        release(owner);
    }

    /** 进世界 / 断线的时候清一下（不发任何包）。 */
    public void reset() {
        owner = null;
        active = false;
        staleTicks = 0;
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
