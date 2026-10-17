package org.six_coin.playerController.client.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;

import java.lang.reflect.Method;

/**
 * 和 meteor 的兼容处理（用反射，不依赖 meteor，也不 require 它）。
 *
 * <p>要处理的是 <b>anti-hunger</b>：它会在每个 {@code PlayerMoveC2SPacket} 发出去之前
 * 把 {@code onGround} 改成 {@code false}（见它的 {@code onSendPacket}），
 * 于是服务端认为我们一直悬空。而原版判「扫击」的条件里有 {@code isOnGround()}：
 *
 * <pre>
 * 冷却满了 &amp;&amp; 不是暴击 &amp;&amp; isOnGround() &amp;&amp; 水平位移足够小 &amp;&amp; 不是跑着打
 * </pre>
 *
 * <p>所以在开着攻击的时候把它关掉，不然站着打人打不出剑气。
 *
 * <p>找模块的方式：{@code Modules.get().get(AntiHunger.class)}，再调 {@code isActive()} /
 * {@code disable()}。meteor 没装就全部老老实实返回 false。
 */
public final class MeteorCompat {

    private static final String MOD_ID = "meteor-client";

    private static final String MODULES_CLASS = "meteordevelopment.meteorclient.systems.modules.Modules";

    private static final String ANTI_HUNGER_CLASS =
        "meteordevelopment.meteorclient.systems.modules.player.AntiHunger";

    /** 找过没有（找不到就记住，别每 tick 反射一次）。 */
    private static boolean looked;

    /** meteor 的 AntiHunger 模块实例。 */
    @Nullable
    private static Object antiHunger;

    /** {@code Module.isActive()} / {@code Module.disable()}，每个 tick 都要用，缓存住。 */
    @Nullable
    private static Method isActiveMethod;

    @Nullable
    private static Method disableMethod;

    private MeteorCompat() {
    }

    /** meteor 装了没。 */
    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** anti-hunger 现在开着吗（没装 meteor / 找不到模块都算没开）。 */
    public static boolean isAntiHungerActive() {
        if (!lookup() || isActiveMethod == null) return false;

        try {
            return (boolean) isActiveMethod.invoke(antiHunger);
        } catch (ReflectiveOperationException e) {
            ChatUtils.debug("问 meteor 的 anti-hunger 开着没失败：" + e);
            return false;
        }
    }

    /**
     * 把 anti-hunger 关掉。
     *
     * @return 这一次是不是真的从「开着」关成了「关掉」（本来就关着 / 没装都是 false）
     */
    public static boolean disableAntiHunger() {
        if (!isAntiHungerActive() || disableMethod == null) return false;

        try {
            disableMethod.invoke(antiHunger);
            ChatUtils.debug("已关掉 meteor 的 anti-hunger");
            return true;
        } catch (ReflectiveOperationException e) {
            ChatUtils.debug("关 meteor 的 anti-hunger 失败：" + e);
            return false;
        }
    }

    /** 找到模块和它的两个方法了没（只找一次）。 */
    private static boolean lookup() {
        if (looked) return antiHunger != null;
        looked = true;

        if (!isLoaded()) return false;

        try {
            Class<?> modulesClass = Class.forName(MODULES_CLASS);
            Class<?> antiHungerClass = Class.forName(ANTI_HUNGER_CLASS);

            Object modules = modulesClass.getMethod("get").invoke(null);
            if (modules == null) {
                ChatUtils.debug("meteor 的 Modules 还没初始化好");
                return false;
            }

            Object module = modulesClass.getMethod("get", Class.class).invoke(modules, antiHungerClass);
            if (module == null) {
                ChatUtils.debug("meteor 里没有 anti-hunger 这个模块");
                return false;
            }

            Method isActive = module.getClass().getMethod("isActive");
            Method disable = module.getClass().getMethod("disable");

            antiHunger = module;
            isActiveMethod = isActive;
            disableMethod = disable;
        } catch (ClassNotFoundException e) {
            ChatUtils.debug("装了 meteor，但找不到 " + e.getMessage() + "（版本对不上？）");
        } catch (ReflectiveOperationException e) {
            ChatUtils.debug("找 meteor 的 anti-hunger 失败：" + e);
        }
        return antiHunger != null;
    }
}
