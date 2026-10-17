package org.six_coin.playerController.client.attack;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.WaterAnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 生物分组：{@code /pc attack show_all_mobs} 按组列，批量操作（{@code add_batch} 之类）的参数也是组 id。
 *
 * <p>分组是按**原版的类继承**推出来的，一共七组（顺序就是列出时的顺序）：
 *
 * <pre>
 * players        玩家
 * bosses         首领      末影龙、凋灵、监守者、远古守卫者
 * hostile_mobs   敌对生物  原版那些 Monster（僵尸、苦力怕、末影人、猪灵…）
 * water_mobs     水生生物  WaterAnimalEntity（鱼、鱿鱼、海豚…）
 * passive_mobs   被动生物  AnimalEntity / PassiveEntity（牛、羊、村民…）
 * neutral_mobs   中立生物  剩下的 MobEntity（铁傀儡、雪傀儡、蝙蝠…）
 * others         其他      剩下的活物（盔甲架…）
 * </pre>
 *
 * <p>判定顺序就是上面这个顺序，所以「守卫者」算敌对生物（它是 Monster），
 * 「美西螈」算被动生物（它是 AnimalEntity）。
 *
 * <p>只有活着的（{@code LivingEntity}）才会出现在这里。
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

    /** 缓存：注册表进来之后就不变了。 */
    @Nullable
    private static List<EntityType<?>> cache;

    private EntityGroups() {
    }

    /** 所有活着的实体类型，按组的顺序、组内按 id 排序。 */
    public static List<EntityType<?>> allMobs() {
        if (cache != null) return cache;

        List<EntityType<?>> mobs = new ArrayList<>();
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            if (isLiving(type)) mobs.add(type);
        }
        mobs.sort(Comparator
            .comparingInt((EntityType<?> type) -> groupOf(type).ordinal())
            .thenComparing(type -> Registries.ENTITY_TYPE.getId(type).toString()));

        cache = List.copyOf(mobs);
        return cache;
    }

    /** 这个实体类型是活的吗。 */
    public static boolean isLiving(EntityType<?> type) {
        Class<? extends Entity> base = type.getBaseClass();
        return base != null && LivingEntity.class.isAssignableFrom(base);
    }

    /** 这个实体类型在哪一组（不认的就归「其他」）。 */
    public static Group groupOf(EntityType<?> type) {
        Class<? extends Entity> base = type.getBaseClass();
        if (base == null) return Group.OTHERS;

        if (PlayerEntity.class.isAssignableFrom(base)) return Group.PLAYERS;

        if (EnderDragonEntity.class.isAssignableFrom(base)
            || WitherEntity.class.isAssignableFrom(base)
            || WardenEntity.class.isAssignableFrom(base)
            || ElderGuardianEntity.class.isAssignableFrom(base)) {
            return Group.BOSSES;
        }

        if (Monster.class.isAssignableFrom(base)) return Group.HOSTILE;

        if (WaterAnimalEntity.class.isAssignableFrom(base)) return Group.WATER;

        if (AnimalEntity.class.isAssignableFrom(base)
            || PassiveEntity.class.isAssignableFrom(base)) {
            return Group.PASSIVE;
        }

        if (MobEntity.class.isAssignableFrom(base)) return Group.NEUTRAL;

        return Group.OTHERS;
    }
}
