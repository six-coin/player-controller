package org.six_coin.playerController.client.attack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.Targeter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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
 * 挑目标的规则是「最近的优先，一次只打一个」，范围来自
 * {@code /pc config actions attack_range}（默认 4.5）和 {@code attack_wall_range}
 * （穿墙也够得着的范围，默认 4.5）。
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

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            idle(State.NO_TARGET);
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            idle(State.OFF);
            return;
        }
        if (player.isUsingItem() || mc.interactionManager.isBreakingBlock()) {
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
        if (!isWeapon(player.getInventory().getSelectedStack())) {
            idle(State.NO_WEAPON);
            return;
        }
        if (!RotateManager.get().rotateTo(Owner.ATTACK, aimPoint(target))) {
            idle(State.ROTATE_DENIED);
            return;
        }

        targetName = nameOf(target);

        // 原版的攻击间隔：这把武器的冷却满了才打
        if (player.getAttackCooldownProgress(COOLDOWN_BASE_TIME) < 1.0F) {
            state = State.COOLDOWN;
            return;
        }

        AttackProfile.Rule rule = profile.rule(target.getType());
        if (profile.effective(AttackProfile.Field.CRITICAL, rule)) {
            SilentCriticals.crit(mc);
        }

        mc.interactionManager.attackEntity(player, target);
        player.swingHand(Hand.MAIN_HAND);

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
    private LivingEntity findTarget(MinecraftClient mc, AttackProfile profile) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return null;

        double reach = Math.max(PlayerControllerConfig.getAttackRange(),
            PlayerControllerConfig.getAttackWallRange());

        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : mc.world.getEntitiesByClass(LivingEntity.class,
            player.getBoundingBox().expand(reach), entity -> isTarget(mc, profile, entity))) {

            double distance = player.squaredDistanceTo(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 这个生物该不该打。 */
    private boolean isTarget(MinecraftClient mc, AttackProfile profile, LivingEntity entity) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return false;
        if (entity == player || entity == mc.getCameraEntity()) return false;
        if (entity.isDead() || !entity.isAlive()) return false;

        // 没写在 profile 里的生物一律不打
        AttackProfile.Rule rule = profile.rule(entity.getType());
        if (rule == null) return false;

        double range = PlayerControllerConfig.getAttackRange();
        if (!withinHitboxRange(player, entity, range)) return false;

        // 看不见的时候，要求离得足够近（attack_wall_range）
        double wallRange = PlayerControllerConfig.getAttackWallRange();
        if (!canSee(mc, player, entity) && !withinFeetRange(player, entity, wallRange)) return false;

        if (profile.effective(AttackProfile.Field.IGNORE_NAMED, rule) && entity.hasCustomName()) {
            return false;
        }
        if (profile.effective(AttackProfile.Field.IGNORE_TAMED, rule) && isTamedBy(entity, player)) {
            return false;
        }
        if (profile.effective(AttackProfile.Field.IGNORE_BABY, rule) && entity.isBaby()) {
            return false;
        }

        // 玩家不受「忽略被动」影响：玩家不会「对你有仇恨」，那这条一开就永远不打人了
        if (!(entity instanceof PlayerEntity)
            && profile.effective(AttackProfile.Field.IGNORE_PASSIVE, rule)
            && !isHostileTowards(entity, player)) {
            return false;
        }

        if (entity instanceof PlayerEntity other) {
            if (other.isCreative() || other.isSpectator()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 距离 / 视线
    // ------------------------------------------------------------------

    /** 眼睛到「离自己最近的那一点」的距离够不够得着（和原版打实体用的判定一样）。 */
    private static boolean withinHitboxRange(ClientPlayerEntity player, LivingEntity entity, double range) {
        Box box = entity.getBoundingBox();
        double x = MathHelper.clamp(player.getX(), box.minX, box.maxX);
        double y = MathHelper.clamp(player.getEyeY(), box.minY, box.maxY);
        double z = MathHelper.clamp(player.getZ(), box.minZ, box.maxZ);
        return player.getEyePos().squaredDistanceTo(x, y, z) <= range * range;
    }

    /** 脚到脚的距离够不够近（穿墙时用的那一条）。 */
    private static boolean withinFeetRange(ClientPlayerEntity player, LivingEntity entity, double range) {
        return player.squaredDistanceTo(entity) <= range * range;
    }

    /** 眼睛到这只生物的脸 / 脚之间有没有方块挡着。 */
    private static boolean canSee(MinecraftClient mc, ClientPlayerEntity player, LivingEntity entity) {
        if (mc.world == null) return false;

        Vec3d eye = player.getEyePos();
        Vec3d feet = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        if (clear(mc, player, eye, feet)) return true;

        Vec3d face = new Vec3d(entity.getX(), entity.getEyeY(), entity.getZ());
        return clear(mc, player, eye, face);
    }

    private static boolean clear(MinecraftClient mc, ClientPlayerEntity player, Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(from, to,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player)
        ).getType() == HitResult.Type.MISS;
    }

    /** 这东西现在对你有仇恨吗（有目标，而且目标就是你）。 */
    private static boolean isHostileTowards(LivingEntity entity, ClientPlayerEntity player) {
        return entity instanceof Targeter targeter && targeter.getTarget() == player;
    }

    private static boolean isTamedBy(LivingEntity entity, ClientPlayerEntity player) {
        return entity instanceof Tameable tameable && tameable.getOwner() == player;
    }

    // ------------------------------------------------------------------
    // 武器
    // ------------------------------------------------------------------

    /**
     * 该用哪一格。
     *
     * @return -1 = 没有合用的武器（这一次不打）
     */
    private static int weaponSlot(PlayerInventory inventory, AttackProfile profile) {
        if (!profile.autoSwitch()) {
            int selected = inventory.getSelectedSlot();
            return isWeapon(inventory.getSelectedStack()) ? selected : -1;
        }

        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (isWeapon(inventory.getStack(i))) return i;
        }
        return -1;
    }

    /** 剑类，而且剩余耐久还够。 */
    private static boolean isWeapon(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.SWORDS)) return false;
        return remainingDurability(stack) > MIN_DURABILITY;
    }

    /** 剩余耐久；不会坏的物品算无限。 */
    private static int remainingDurability(ItemStack stack) {
        if (!stack.isDamageable()) return Integer.MAX_VALUE;
        return stack.getMaxDamage() - stack.getDamage();
    }

    // ------------------------------------------------------------------

    /** 转头瞄的那个点：身体正中间。 */
    private static Vec3d aimPoint(LivingEntity entity) {
        return new Vec3d(entity.getX(), entity.getY() + entity.getHeight() * 0.5, entity.getZ());
    }

    /** 「僵尸 (minecraft:zombie)」这种。 */
    public static String nameOf(EntityType<?> type) {
        return type.getName().getString() + " (" + Registries.ENTITY_TYPE.getId(type) + ")";
    }

    private static String nameOf(Entity entity) {
        return nameOf(entity.getType());
    }
}
