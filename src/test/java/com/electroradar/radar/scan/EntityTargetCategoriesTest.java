package com.electroradar.radar.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.electroradar.radar.network.TargetCategory;
import java.util.Set;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

class EntityTargetCategoriesTest {
    @Test void bigCannonProjectileBelongsToShellAndProjectileCategories() {
        assertEquals(Set.of(TargetCategory.SHELL, TargetCategory.PROJECTILE),
                EntityTargetCategories.classify(AbstractBigCannonProjectile.class, MobCategory.MISC));
    }
    @ParameterizedTest @MethodSource("entityFamilies")
    void entityFamiliesHaveDistinctCategories(Class<? extends Entity> type, MobCategory spawnCategory,
                                              TargetCategory expected) {
        assertEquals(Set.of(expected), EntityTargetCategories.classify(type, spawnCategory));
    }
    static Stream<Arguments> entityFamilies() {
        return Stream.of(
                Arguments.of(Arrow.class, MobCategory.MISC, TargetCategory.PROJECTILE),
                Arguments.of(AbstractCannonProjectile.class, MobCategory.MISC, TargetCategory.PROJECTILE),
                Arguments.of(Player.class, MobCategory.MISC, TargetCategory.PLAYER),
                Arguments.of(Pig.class, MobCategory.CREATURE, TargetCategory.PASSIVE_MOB),
                Arguments.of(Squid.class, MobCategory.WATER_CREATURE, TargetCategory.PASSIVE_MOB),
                Arguments.of(Zombie.class, MobCategory.MONSTER, TargetCategory.HOSTILE_MOB),
                Arguments.of(ItemEntity.class, MobCategory.MISC, TargetCategory.OTHER),
                Arguments.of(ArmorStand.class, MobCategory.MISC, TargetCategory.OTHER));
    }
}
