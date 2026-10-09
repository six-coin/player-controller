package org.six_coin.playerController.client.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * 参考 Meteor 的 Flight（velocity 模式）实现的移动方式。
 *
 * <p>Meteor velocity 模式的做法是：
 * <ol>
 *   <li>关掉原版飞行（{@code abilities.flying = false}）</li>
 *   <li>每 tick 把速度清零，然后按输入方向重新加上 {@code speed}</li>
 * </ol>
 * 因为原版 {@code LivingEntity.travelMidAir} 的顺序是
 * “updateVelocity → move → 扣重力/摩擦”，所以在玩家 tick 之前把速度写好，
 * 玩家这一 tick 就会正好按我们给的速度位移，重力对位移没有影响 —— 也就是“悬停飞行”。
 *
 * <p>控制器不需要键盘输入，这里直接把“方向 × speed”当成速度写进去。
 *
 * <p>{@link #begin()} / {@link #end()} 是**引用计数**的：外层成套动作持有飞行的时候，
 * 里面每一段移动自己 begin/end 不会把飞行提前关掉。
 */
public final class FlightVelocity {

    /** 需求指定的速度。 */
    public static final double SPEED = 0.3;

    private static boolean active;

    /**
     * 「悬停飞行」的持有层数。
     *
     * <p>一套连着的动作（比如备货里「挖盒子 → 落下去捡 → 飞上来 → 走回站立点」）中间不能关飞行，
     * 不然玩家会在两步之间自己往下掉。所以 begin / end 做成引用计数：只要还有一层没释放，
     * 就不会真的关掉（也不会把速度清零）。
     */
    private static int holds;

    private FlightVelocity() {
    }

    public static boolean isActive() {
        return active;
    }

    /** 现在有几层还持有飞行。 */
    public static int holds() {
        return holds;
    }

    public static void begin() {
        holds++;
        active = true;
    }

    public static void end() {
        if (holds > 0) holds--;
        if (holds > 0) return;

        active = false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.setVelocity(Vec3d.ZERO);
        }
    }

    /** 这一 tick 的移动速度（在客户端 tick 开始时调用）。 */
    public static void apply(Vec3d velocity) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;

        // 与 Meteor 的 velocity 模式保持一致
        player.getAbilities().flying = false;
        player.setVelocity(velocity);
    }

    public static Vec3d velocityFor(net.minecraft.util.math.Direction.Axis axis, double step) {
        return switch (axis) {
            case X -> new Vec3d(step, 0.0, 0.0);
            case Y -> new Vec3d(0.0, step, 0.0);
            case Z -> new Vec3d(0.0, 0.0, step);
        };
    }
}
