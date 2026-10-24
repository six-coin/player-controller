package org.six_coin.playerController.client.hotbar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 快捷栏选中格的调度：**所有**切换快捷栏都要经过这里。
 *
 * <p>为什么要有它：攻击（{@code /pc attack}）是常驻的，它需要把剑切到手上；
 * 备货也要切快捷栏（挖方块、放潜影盒）。两边各自 {@code setSelectedSlot} 的话，
 * 就会互相把对方切走，谁也做不成。所以统一在这里登记：
 *
 * <ul>
 *   <li>{@link #request} 拿到才算允许切；被拒绝的时候调用方放弃这一次操作
 *       （攻击那边就是「本次攻击取消」）。</li>
 *   <li>优先级高的可以抢优先级低的（{@link Owner#priority()}）：跑备货的时候，
 *       攻击发起的切换请求一律不作数。</li>
 *   <li>用完 {@link #release}（备货的动作结束、攻击不动手的时候）。</li>
 * </ul>
 *
 * <p>切的时候会**立刻**补一个 {@code ServerboundSetCarriedItemPacket}：原版是每 tick 自己同步一次，
 * 同一 tick 里先切再打的话，服务端那一头还在用旧的那格（武器不对、伤害和冷却都按旧的算）。
 */
public final class HotbarManager {

    private static final HotbarManager INSTANCE = new HotbarManager();

    /** 现在谁占着，没人占是 null。 */
    @Nullable
    private Owner owner;

    /** 现在占的是哪一格，没人占是 -1。 */
    private int slot = -1;

    private HotbarManager() {
    }

    public static HotbarManager get() {
        return INSTANCE;
    }

    /** 现在谁占着（没有就是 null）。 */
    @Nullable
    public Owner owner() {
        return owner;
    }

    /** 现在占着哪一格（没有就是 -1）。 */
    public int slot() {
        return slot;
    }

    /** 是不是这个人在用。 */
    public boolean isHeldBy(Owner requester) {
        return owner == requester;
    }

    /**
     * 请求把快捷栏切到第 {@code slot} 格。
     *
     * @return {@code false} = 这次不让切（别人用着，而且优先级更高），调用方应当放弃这一次操作
     */
    public boolean request(Owner requester, int slot) {
        if (!Inventory.isHotbarSlot(slot)) {
            ChatUtils.error("快捷栏没有第 " + (slot + 1) + " 格");
            return false;
        }

        if (owner != null && owner != requester && owner.priority() > requester.priority()) {
            ChatUtils.debug("不让切快捷栏：现在是 " + owner + " 在用第 " + (this.slot + 1) + " 格");
            return false;
        }

        if (owner != requester || this.slot != slot) {
            ChatUtils.debug("快捷栏切到第 " + (slot + 1) + " 格（" + requester + "）：" + describe());
        }
        owner = requester;
        this.slot = slot;
        apply(slot);
        return true;
    }

    /** 用完还回去（不是自己在用的时候什么都不做）。 */
    public void release(Owner requester) {
        if (owner != requester) return;
        ChatUtils.debug("快捷栏不再归 " + requester + " 用（原来在第 " + (slot + 1) + " 格）");
        owner = null;
        slot = -1;
    }

    /** 进世界 / 断线的时候清一下（不动快捷栏）。 */
    public void reset() {
        owner = null;
        slot = -1;
    }

    /**
     * 真的把选中格切过去，并且立刻告诉服务端。
     *
     * <p>客户端这一头只改 {@code selectedSlot}，不碰任何按键，也不动玩家的视角。
     */
    private static void apply(int slot) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        Inventory inventory = player.getInventory();
        if (inventory.getSelectedSlot() == slot) return;

        inventory.setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static String describe() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return "手上一格";

        ItemStack held = player.getInventory().getSelectedItem();
        if (held.isEmpty()) return "空手";
        return BuiltInRegistries.ITEM.getKey(held.getItem()) + " x" + held.getCount();
    }
}
