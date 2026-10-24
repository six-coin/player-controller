package org.six_coin.playerController.client.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.config.PlayerControllerConfig;
import org.six_coin.playerController.client.feature.ServerTickRate;
import org.six_coin.playerController.client.hotbar.HotbarManager;
import org.six_coin.playerController.client.owner.Owner;
import org.six_coin.playerController.client.rotate.RotateManager;
import org.six_coin.playerController.client.util.ChatUtils;

/**
 * 攻击：常驻扫描身边的生物，按当前 profile 挑一个最近的打。
 *
 * <p>和原版键位、和备货的动作队列都**无关**：它自己每 tick 跑一遍，不排队。
 * 挑目标的规则是「最近的优先，一次只打一个」，范围就是 {@code /pc config actions reach}
 * （默认 4.5，和挖方块、开容器用的是同一个数，不区分看不看得见）。
 *
 * <p>固定开着的几件事（不给开关）：
 *
 * <ul>
 *   <li>只有在**原版攻击间隔**满了之后才打（{@code getAttackCooldownProgress}）；</li>
 *   <li>服务端卡着的时候不打（{@link ServerTickRate#isLagging()}）；</li>
 *   <li>正在用物品 / 正在挖方块的时候不打；</li>
 *   <li>开着界面（背包、箱子）**照打**。</li>
 * </ul>
 *
 * <p>武器的要求：快捷栏里第一个**剑类**（{@code #minecraft:swords}）且**剩余耐久 > 100** 的那一格。
 * 一个都没有就这一次不打。{@code auto_switch} 关掉的时候只看当前选中的那一格。
 *
 * <p>切快捷栏、转头都走 {@link HotbarManager} / {@link RotateManager}：被拒绝了（备货在用）
 * 就这一次不打。转头只改服务端那边看到的朝向，客户端视角不动（见 {@link RotateManager}）。
 */
public final class AttackManager {

    /** 剑的剩余耐久要**严格大于**这个数才算合法武器。 */
    public static final int MIN_DURABILITY = 100;

    /** 攻击间隔用这把武器原版的那套，所以这里传原版的基础值。 */
    private static final float COOLDOWN_BASE_TIME = 0.5F;

    private static final AttackManager INSTANCE = new AttackManager();

    /** 现在为什么在打 / 没在打（{@code /pc attack} 会显示）。 */
    public enum State {

        OFF("没开"),
        NO_PROFILE("当前 profile 不存在或者读不出来"),
        PAUSED_USE("正在用物品 / 正在挖方块"),
        PAUSED_LAG("服务端卡了"),
        NO_TARGET("范围内没有要打的生物"),
        NO_WEAPON("快捷栏里没有合用的剑（#minecraft:swords，剩余耐久 > " + MIN_DURABILITY + "）"),
        HOTBAR_DENIED("快捷栏被备货占着，这一次不打"),
        ROTATE_DENIED("不准转头，这一次不打"),
        COOLDOWN("等原版攻击间隔"),
        CRIT_WAIT("补暴击包（下一 tick 出手）"),
        ATTACKING("正在攻击");

        private final String text;

        State(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private State state = State.OFF;

    /** 上一次打的是谁（显示用）。 */
    @Nullable
    private String targetName;

    /** 上一 tick 是不是在打（只在状态变化的时候打一行调试日志）。 */
    private boolean wasAttacking;

    private AttackManager() {
    }

    public static AttackManager get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    @Nullable
    public String targetName() {
        return targetName;
    }

    /** 换世界 / 断线的时候清一下（不发任何包）。 */
    public void reset() {
        state = State.OFF;
        targetName = null;
        wasAttacking = false;
        HotbarManager.get().release(Owner.ATTACK);
        RotateManager.get().release(Owner.ATTACK);
    }

    // ------------------------------------------------------------------
    // 每 tick
    // ------------------------------------------------------------------

    public void tick() {
        AttackConfig config = AttackConfig.get();
        if (!config.enable()) {
            idle(State.OFF);
            return;
        }

        AttackProfile profile = config.currentProfile();
        if (profile == null) {
            idle(State.NO_PROFILE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            idle(State.NO_TARGET);
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            idle(State.OFF);
            return;
        }
        if (player.isUsingItem() || mc.gameMode.isDestroying()) {
            idle(State.PAUSED_USE);
            return;
        }
        if (ServerTickRate.isLagging()) {
            idle(State.PAUSED_LAG);
            return;
        }

        LivingEntity target = findTarget(mc, profile);
        if (target == null) {
            idle(State.NO_TARGET);
            return;
        }

        int slot = weaponSlot(player.getInventory(), profile);
        if (slot < 0) {
            idle(State.NO_WEAPON);
            return;
        }
        if (profile.autoSwitch() && !HotbarManager.get().request(Owner.ATTACK, slot)) {
            idle(State.HOTBAR_DENIED);
            return;
        }
        if (!isWeapon(player.getInventory().getSelectedItem())) {
            idle(State.NO_WEAPON);
            return;
        }
        if (!RotateManager.get().rotateTo(Owner.ATTACK, aimPoint(target))) {
            idle(State.ROTATE_DENIED);
            return;
        }

        targetName = nameOf(target);

        // 原版的攻击间隔：这把武器的冷却满了才打
        if (player.getAttackStrengthScale(COOLDOWN_BASE_TIME) < 1.0F) {
            state = State.COOLDOWN;
            return;
        }

        AttackProfile.Rule rule = profile.rule(target.getType());
        if (profile.effective(AttackProfile.Field.CRITICAL, rule) && !SilentCriticals.step(mc)) {
            // 26.3 一个 tick 只准发一个带位置的移动包，所以「假装下落」得摊到前两个 tick：
            // 这一 tick 只把原版的位置包改好，真正的攻击包等下一 tick 再发
            state = State.CRIT_WAIT;
            return;
        }

        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

        state = State.ATTACKING;
        if (!wasAttacking) {
            ChatUtils.debug("开始攻击：" + targetName + "（快捷栏第 " + (slot + 1) + " 格）");
        }
        wasAttacking = true;
    }

    /** 这一 tick 不动手：把占着的东西还回去。 */
    private void idle(State reason) {
        state = reason;
        targetName = null;
        // 暴击准备被中断了：把阶段清掉，免得下次一上来就直接当「已经掉过了」
        SilentCriticals.reset();
        if (wasAttacking) {
            ChatUtils.debug("停止攻击：" + reason.text());
            wasAttacking = false;
        }
        HotbarManager.get().release(Owner.ATTACK);
        RotateManager.get().release(Owner.ATTACK);
    }

    // ------------------------------------------------------------------
    // 挑目标
    // ------------------------------------------------------------------

    @Nullable
    private LivingEntity findTarget(Minecraft mc, AttackProfile profile) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;

        double reach = PlayerControllerConfig.getReach();

        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : mc.level.getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox().inflate(reach), entity -> isTarget(mc, profile, entity))) {

            double distance = player.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 这个生物该不该打。 */
    private boolean isTarget(Minecraft mc, AttackProfile profile, LivingEntity entity) {
        LocalPlayer player = mc.player;
        if (player == null) return false;
        if (entity == player || entity == mc.getCameraEntity()) return false;
        if (entity.isRemoved() || !entity.isAlive()) return false;

        // 没写在 profile 里的生物一律不打
        AttackProfile.Rule rule = profile.rule(entity.getType());
        if (rule == null) return false;

        // 够不够得着：眼睛到最近命中点不超过 reach（不再区分看不看得见）
        if (!withinHitboxRange(player, entity, PlayerControllerConfig.getReach())) return false;

        if (profile.effective(AttackProfile.Field.IGNORE_NAMED, rule) && entity.hasCustomName()) {
            return false;
        }
        if (profile.effective(AttackProfile.Field.IGNORE_TAMED, rule) && isTamedBy(entity, player)) {
            return false;
        }
        if (profile.effective(AttackProfile.Field.IGNORE_BABY, rule) && entity.isBaby()) {
            return false;
        }

        // 「忽略没有仇恨的被动生物」：只特判下面这几种（和 meteor 的 killaura 一致），别的照打
        if (profile.effective(AttackProfile.Field.IGNORE_PASSIVE, rule) && isIdlePassive(entity)) {
            return false;
        }

        if (entity instanceof Player other) {
            if (other.isCreative() || other.isSpectator()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 距离
    // ------------------------------------------------------------------

    /** 眼睛到「离自己最近的那一点」的距离够不够得着（和原版打实体用的判定一样）。 */
    private static boolean withinHitboxRange(LocalPlayer player, LivingEntity entity, double range) {
        AABB box = entity.getBoundingBox();
        double x = Mth.clamp(player.getX(), box.minX, box.maxX);
        double y = Mth.clamp(player.getEyeY(), box.minY, box.maxY);
        double z = Mth.clamp(player.getZ(), box.minZ, box.maxZ);
        return player.getEyePosition().distanceToSqr(x, y, z) <= range * range;
    }

    /**
     * 这是「没有仇恨的被动生物」吗（{@code ignore_passive} 一开就不打它）。
     *
     * <p>和 meteor 的 killaura 一样，只特判这几种：
     *
     * <ul>
     *   <li>末影人：没被激怒（没瞪着你看）的时候不打；</li>
     *   <li>猪灵 / 僵尸猪灵 / 狼：没有攻击目标的时候不打。</li>
     * </ul>
     *
     * <p>别的生物（牛羊、村民、铁傀儡…）不在这张表里，一律算「不是被动的」，
     * 也就是 profile 说打就打 —— 玩家同理。
     */
    private static boolean isIdlePassive(LivingEntity entity) {
        if (entity instanceof Enderman enderman) {
            return !enderman.isAngry();
        }
        if (entity instanceof Piglin
            || entity instanceof ZombifiedPiglin
            || entity instanceof Wolf) {
            return !((Mob) entity).isAggressive();
        }
        return false;
    }

    private static boolean isTamedBy(LivingEntity entity, LocalPlayer player) {
        return entity instanceof OwnableEntity tameable && tameable.getOwner() == player;
    }

    // ------------------------------------------------------------------
    // 武器
    // ------------------------------------------------------------------

    /**
     * 该用哪一格。
     *
     * @return -1 = 没有合用的武器（这一次不打）
     */
    private static int weaponSlot(Inventory inventory, AttackProfile profile) {
        if (!profile.autoSwitch()) {
            int selected = inventory.getSelectedSlot();
            return isWeapon(inventory.getSelectedItem()) ? selected : -1;
        }

        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (isWeapon(inventory.getItem(i))) return i;
        }
        return -1;
    }

    /** 剑类，而且剩余耐久还够。 */
    private static boolean isWeapon(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.is(ItemTags.SWORDS)) return false;
        return remainingDurability(stack) > MIN_DURABILITY;
    }

    /** 剩余耐久；不会坏的物品算无限。 */
    private static int remainingDurability(ItemStack stack) {
        if (!stack.isDamageableItem()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    // ------------------------------------------------------------------

    /** 转头瞄的那个点：身体正中间。 */
    private static Vec3 aimPoint(LivingEntity entity) {
        return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
    }

    /** 「僵尸 (minecraft:zombie)」这种。 */
    public static String nameOf(EntityType<?> type) {
        return type.getDescription().getString() + " (" + BuiltInRegistries.ENTITY_TYPE.getKey(type) + ")";
    }

    private static String nameOf(Entity entity) {
        return nameOf(entity.getType());
    }
}
