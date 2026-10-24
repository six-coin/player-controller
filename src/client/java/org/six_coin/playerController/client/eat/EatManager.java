package org.six_coin.playerController.client.eat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.mixin.ClientPlayerInteractionManagerAccessor;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 自动进食（{@code /pc eat} 开关，存在 {@code world_<n>/eat.json}）。
 *
 * <p>规则：开关打开、饱食度降到 {@value #TRIGGER_FOOD}（满 20）的时候，
 * 把快捷栏切到第 9 格（食物那一格）、右键吃掉，吃完把快捷栏切回原来那一格。
 *
 * <p><b>优先级</b>：{@link Owner#EAT} 比挖掘高、比攻击（Killaura）低 ——
 * 攻击要打的时候让给攻击，挖掘不能把食物格抢走。
 *
 * <p><b>为什么自己拼 {@code use_item} 包</b>：原版的 {@code MultiPlayerGameMode#useItem} 会在
 * 客户端也进入「正在使用物品」的状态，而 {@code Minecraft#handleKeybinds} 一旦发现
 * 「正在使用物品但右键没按住」就会立刻 {@code releaseUsingItem} 把进食打断。
 * 我们不发按键、也不改客户端视角，所以直接发一个裸包：服务端自己按 useItemRemaining
 * 计时，到点就把东西吃掉（客户端什么都不用做）。
 */
public final class EatManager {

    /** 饱食度降到这个数（含）就开始吃。 */
    public static final int TRIGGER_FOOD = 12;

    /** 快捷栏 9（食物那一格）在 {@code Inventory} 里的下标。 */
    public static final int FOOD_HOTBAR = 8;

    /** 吃一个东西最多等多少 tick（原版 32 tick，给足余量）。 */
    private static final int BITE_TIMEOUT_TICKS = 60;

    /** 一口最多重发几次右键包。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 这一轮放弃之后先歇多少 tick 再试（免得一直发包）。 */
    private static final int RETRY_COOLDOWN_TICKS = 40;

    private static final EatManager INSTANCE = new EatManager();

    /** 现在是什么情况（{@code /pc eat} 会显示）。 */
    public enum State {

        OFF("没开"),
        NO_PLAYER("没有玩家或世界"),
        IDLE("不用吃（饱食度还够）"),
        EATING("正在吃"),
        NO_FOOD("快捷栏 9 里没有能吃的东西"),
        HOTBAR_DENIED("快捷栏被别的功能占着，这一次不吃"),
        COOLDOWN("刚才没吃成，等一会儿再试");

        private final String text;

        State(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private State state = State.OFF;

    private boolean eating;
    /** 开始吃的时候的饱食度（涨了就说明吃到了）。 */
    private int startFood;
    private int ticks;
    private int attempts;
    private int cooldown;

    /** 吃之前选中的是哪一格（吃完切回去）。 */
    private int previousSlot = -1;

    /** 这一口是不是我们占着快捷栏。 */
    private boolean hotbarHeld;

    private EatManager() {
    }

    public static EatManager get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    public boolean isEating() {
        return eating;
    }

    /** 进世界 / 断线的时候清一下（不发任何包）。 */
    public void reset() {
        eating = false;
        previousSlot = -1;
        ticks = 0;
        attempts = 0;
        cooldown = 0;
        hotbarHeld = false;
        state = State.OFF;
    }

    // ------------------------------------------------------------------

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (!EatConfig.get().enabled()) {
            if (eating) abort(player, "自动进食被关掉了");
            setState(State.OFF);
            return;
        }

        if (player == null || mc.level == null) {
            if (eating) abort(player, "玩家或世界不存在");
            setState(State.NO_PLAYER);
            return;
        }

        if (!player.isAlive() || player.isSpectator()) {
            if (eating) abort(player, "玩家死亡 / 旁观");
            setState(State.NO_PLAYER);
            return;
        }

        // 开着界面（背包 / 箱子）的时候不动手：备货那套流程正开着容器点格子，
        // 这时候切快捷栏、右键会把它搅乱
        if (mc.gui != null && mc.gui.screen() != null) {
            if (eating) abort(player, "打开了界面");
            setState(State.IDLE);
            return;
        }

        FoodData food = player.getFoodData();

        if (eating) {
            tickEating(mc, player, food);
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            setState(State.COOLDOWN);
            return;
        }

        if (food.getFoodLevel() > TRIGGER_FOOD) {
            setState(State.IDLE);
            return;
        }

        if (!player.canEat(false)) {
            setState(State.IDLE);
            return;
        }

        if (!isFood(player.getInventory().getItem(FOOD_HOTBAR))) {
            setState(State.NO_FOOD);
            return;
        }

        start(player, food);
    }

    // ------------------------------------------------------------------
    // 吃
    // ------------------------------------------------------------------

    private void start(LocalPlayer player, FoodData food) {
        previousSlot = player.getInventory().getSelectedSlot();

        if (!HotbarManager.get().request(Owner.EAT, FOOD_HOTBAR)) {
            previousSlot = -1;
            cooldown = 5;
            setState(State.HOTBAR_DENIED);
            return;
        }
        hotbarHeld = true;

        startFood = food.getFoodLevel();
        ticks = 0;
        attempts = 0;
        eating = true;

        ChatUtils.debug("自动进食：饱食度 " + startFood + "，切到快捷栏 9 吃一口");
        use(player);
    }

    private void tickEating(Minecraft mc, LocalPlayer player, FoodData food) {
        // 吃到了（饱食度涨了）或者已经吃饱了：这一口结束
        if (food.getFoodLevel() > startFood || !player.canEat(false)) {
            finish(player);
            return;
        }

        // 每 tick 重新占一下快捷栏：中途被优先级更高的功能抢走时，这一 tick 就不发包
        if (!hotbarHeld || !HotbarManager.get().isHeldBy(Owner.EAT)) {
            if (HotbarManager.get().request(Owner.EAT, FOOD_HOTBAR)) {
                hotbarHeld = true;
            } else {
                setState(State.HOTBAR_DENIED);
                return;
            }
        }

        ticks++;
        if (ticks < BITE_TIMEOUT_TICKS) {
            setState(State.EATING);
            return;
        }

        // 等了一口的时间还没吃到：再发一次（服务端那边的「正在使用」可能被打断了）
        if (++attempts >= MAX_ATTEMPTS) {
            abort(player, "发了 " + attempts + " 次右键包还是没吃到（快捷栏 9 里是 "
                + describe(player.getInventory().getItem(FOOD_HOTBAR)) + "）");
            cooldown = RETRY_COOLDOWN_TICKS;
            return;
        }

        ticks = 0;
        startFood = food.getFoodLevel();
        ChatUtils.debug("自动进食：还没吃到，再发一次右键包（第 " + (attempts + 1) + " 次）");
        use(player);
    }

    /** 吃完了：把快捷栏切回原来那一格。 */
    private void finish(LocalPlayer player) {
        ChatUtils.debug("自动进食：吃完了，饱食度 " + player.getFoodData().getFoodLevel());
        restore(player);
        eating = false;
        ticks = 0;
        attempts = 0;
        setState(State.IDLE);
    }

    /** 半路停掉：让服务端别再吃了，然后把快捷栏还回去。 */
    private void abort(@Nullable LocalPlayer player, String why) {
        if (!eating) {
            restore(player);
            return;
        }
        ChatUtils.debug("自动进食中断：" + why);
        release(player);
        restore(player);
        eating = false;
        ticks = 0;
        attempts = 0;
    }

    /** 发一个裸的 use_item 包（不让客户端进入「正在使用物品」状态，见类注释）。 */
    private void use(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameMode == null) return;

        ItemStack held = player.getInventory().getSelectedItem();
        if (held.isEmpty()) return;

        ((ClientPlayerInteractionManagerAccessor) mc.gameMode)
            .playerController$startPrediction((ClientLevel) mc.level, sequence ->
                new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence,
                    player.getYRot(), player.getXRot()));
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        setState(State.EATING);
    }

    /** 告诉服务端「别吃了」（不发的话服务端会一直按计时器吃到嘴里）。 */
    private void release(@Nullable LocalPlayer player) {
        if (player == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameMode == null) return;

        ((ClientPlayerInteractionManagerAccessor) mc.gameMode)
            .playerController$startPrediction((ClientLevel) mc.level, sequence ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO, Direction.DOWN, sequence));
    }

    /** 把快捷栏切回吃之前那一格（切不回去就只是放手）。 */
    private void restore(@Nullable LocalPlayer player) {
        if (hotbarHeld) {
            if (player != null && previousSlot >= 0
                && HotbarManager.get().isHeldBy(Owner.EAT)) {
                HotbarManager.get().request(Owner.EAT, previousSlot);
            }
            HotbarManager.get().release(Owner.EAT);
            hotbarHeld = false;
        }
        previousSlot = -1;
    }

    // ------------------------------------------------------------------

    /** 这一叠能不能吃（有 food 组件）。 */
    private static boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "空";
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
            + " x" + stack.getCount();
    }

    private void setState(State next) {
        if (state == next) return;
        ChatUtils.debug("自动进食：" + state.text() + " → " + next.text());
        state = next;
    }
}
