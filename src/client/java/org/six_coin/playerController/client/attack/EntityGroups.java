package org.six_coin.playerController.client.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.six_coin.playerController.client.util.ChatUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生物分组：{@code /pc attack show_all_mobs} 按组列，批量操作（{@code add_batch} 之类）的参数也是组 id。
 *
 * <p>分组是按**原版的类继承**推出来的，一共七组（顺序就是列出时的顺序）：
 *
 * <pre>
 * players        玩家
 * bosses         首领      末影龙、凋灵、监守者、远古守卫者
 * hostile_mobs   敌对生物  原版那些 Monster（僵尸、苦力怕、末影人、猪灵…）
 * water_mobs     水生生物  WaterAnimal（鱼、鱿鱼、海豚…）
 * passive_mobs   被动生物  Animal / AgeableMob（牛、羊、村民…）
 * neutral_mobs   中立生物  剩下的 Mob（铁傀儡、雪傀儡、蝙蝠…）
 * others         其他      剩下的活物（盔甲架…）
 * </pre>
 *
 * <p>判定顺序就是上面这个顺序，所以「守卫者」算敌对生物（它是 Monster），
 * 「美西螈」算被动生物（它是 Animal）。只有活着的（{@code LivingEntity}）才会出现在这里。
 *
 * <p>怎么知道一个实体类型是不是活物：{@link EntityType#getBaseClass()} 现在只会返回
 * {@code Entity.class}（没法用），所以这里让原版造一个实例出来看它到底是什么
 * （造的实例不进世界、用完就扔）。**每个类型只算一次**，结果记住。
 * 玩家是唯一的例外（原版那个工厂返回 null），单独认。
 */
public final class EntityGroups {

    /** 分组。 */
    public enum Group {

        PLAYERS("players", "玩家"),
        BOSSES("bosses", "首领"),
        HOSTILE("hostile_mobs", "敌对生物"),
        WATER("water_mobs", "水生生物"),
        PASSIVE("passive_mobs", "被动生物"),
        NEUTRAL("neutral_mobs", "中立生物"),
        OTHERS("others", "其他");

        private final String id;
        private final String label;

        Group(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** 命令里写的、显示在括号里的那个 id。 */
        public String id() {
            return id;
        }

        /** 中文名。 */
        public String label() {
            return label;
        }

        /** 这一组里的所有生物（按 id 排好序）。 */
        public List<EntityType<?>> mobs() {
            List<EntityType<?>> result = new ArrayList<>();
            for (EntityType<?> type : allMobs()) {
                if (groupOf(type) == this) result.add(type);
            }
            return result;
        }

        @Nullable
        public static Group byId(String id) {
            for (Group group : values()) {
                if (group.id.equalsIgnoreCase(id)) return group;
            }
            return null;
        }
    }

    /** 已经算过的：实体类型 -> 组。 */
    private static final Map<EntityType<?>, Group> GROUPS = new LinkedHashMap<>();

    /** 已经算过、确认不是活物的（免得反复造实例去看）。 */
    private static final Set<EntityType<?>> NOT_LIVING = new LinkedHashSet<>();

    /** 所有活物，排好序；注册表进来之后就不变了。 */
    @Nullable
    private static List<EntityType<?>> cache;

    private EntityGroups() {
    }

    /** 所有活着的实体类型，按组的顺序、组内按 id 排序（还没进世界的时候是空的）。 */
    public static List<EntityType<?>> allMobs() {
        if (cache != null) return cache;
        if (Minecraft.getInstance().level == null) return List.of();

        List<EntityType<?>> mobs = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (groupOf(type) != null) mobs.add(type);
        }
        mobs.sort(Comparator
            .comparingInt((EntityType<?> type) -> groupOf(type).ordinal())
            .thenComparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));

        cache = List.copyOf(mobs);
        return cache;
    }

    /**
     * 这个实体类型在哪一组。
     *
     * @return 不是活物（或者现在判断不了，比如还没进世界）返回 null
     */
    @Nullable
    public static Group groupOf(EntityType<?> type) {
        Group cached = GROUPS.get(type);
        if (cached != null) return cached;
        if (NOT_LIVING.contains(type)) return null;

        // 还没进世界的时候造不出实例，这时候不缓存结果（不然会一辈子算成「不是活物」）
        if (Minecraft.getInstance().level == null) return null;

        Group computed = computeGroup(type);
        if (computed == null) {
            NOT_LIVING.add(type);
        } else {
            GROUPS.put(type, computed);
        }
        return computed;
    }

    @Nullable
    private static Group computeGroup(EntityType<?> type) {
        // 玩家是唯一造不出实例的（原版那个工厂返回 null），单独认
        if (type == EntityTypes.PLAYER) return Group.PLAYERS;

        Level level = Minecraft.getInstance().level;
        if (level == null) return null;

        Entity entity;
        try {
            entity = type.create(level, EntitySpawnReason.COMMAND);
        } catch (Exception e) {
            ChatUtils.debug("造一个 " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " 的实例失败：" + e);
            return null;
        }
        if (entity == null) return null;
        if (entity instanceof Player) return Group.PLAYERS;
        if (!(entity instanceof LivingEntity)) return null;

        if (entity instanceof EnderDragon
            || entity instanceof WitherBoss
            || entity instanceof Warden
            || entity instanceof ElderGuardian) {
            return Group.BOSSES;
        }
        if (entity instanceof Monster) return Group.HOSTILE;
        if (entity instanceof WaterAnimal) return Group.WATER;
        if (entity instanceof Animal || entity instanceof AgeableMob) return Group.PASSIVE;
        if (entity instanceof Mob) return Group.NEUTRAL;
        return Group.OTHERS;
    }
}
