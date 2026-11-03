package com.electroradar.cannon.mount;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

/**
 * Единственное отражательное обращение к CBC: {@code getBallisticProperties()} у снарядов
 * защищённый, а свойства разных типов хранятся в разных обработчиках CBC. Метод виртуальный,
 * поэтому вызов через объявление в {@link AbstractCannonProjectile} попадает в реализацию типа.
 */
final class ProjectileBallistics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Method GETTER = findGetter();

    private ProjectileBallistics() {
    }

    static Optional<BallisticPropertiesComponent> of(AbstractCannonProjectile projectile) {
        if (GETTER == null || projectile == null) return Optional.empty();
        try {
            return GETTER.invoke(projectile) instanceof BallisticPropertiesComponent properties
                    ? Optional.of(properties) : Optional.empty();
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return Optional.empty();
        }
    }

    private static Method findGetter() {
        try {
            Method method = AbstractCannonProjectile.class.getDeclaredMethod("getBallisticProperties");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.error("[ElectroRadar] Create Big Cannons ballistic properties are unavailable", exception);
            return null;
        }
    }
}
