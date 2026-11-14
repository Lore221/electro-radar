package com.electroradar.radar.scan;

import com.electroradar.radar.network.TargetCategory;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import java.util.Objects;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

/** Адаптер игровых типов: ядро хранит категории без зависимости от классов CBC. */
public final class EntityTargetCategories {
    private static final Set<TargetCategory> SHELL = Set.of(TargetCategory.SHELL, TargetCategory.PROJECTILE);
    private static final Set<TargetCategory> PROJECTILE = Set.of(TargetCategory.PROJECTILE);
    private static final Set<TargetCategory> PLAYER = Set.of(TargetCategory.PLAYER);
    private static final Set<TargetCategory> HOSTILE = Set.of(TargetCategory.HOSTILE_MOB);
    private static final Set<TargetCategory> PASSIVE = Set.of(TargetCategory.PASSIVE_MOB);
    private static final Set<TargetCategory> OTHER = Set.of(TargetCategory.OTHER);

    private EntityTargetCategories() { }

    /** Метаданные уже разрешённой сущности; классификация не читает мир и не создаёт Entity. */
    public static Set<TargetCategory> classify(Class<? extends Entity> entityClass, MobCategory spawnCategory) {
        Objects.requireNonNull(entityClass, "entityClass");
        Objects.requireNonNull(spawnCategory, "spawnCategory");
        if (AbstractBigCannonProjectile.class.isAssignableFrom(entityClass)) return SHELL;
        if (Projectile.class.isAssignableFrom(entityClass)) return PROJECTILE;
        if (Player.class.isAssignableFrom(entityClass)) return PLAYER;
        if (Mob.class.isAssignableFrom(entityClass)) {
            if (spawnCategory == MobCategory.MONSTER) return HOSTILE;
            if (spawnCategory != MobCategory.MISC) return PASSIVE;
        }
        return OTHER;
    }
}
