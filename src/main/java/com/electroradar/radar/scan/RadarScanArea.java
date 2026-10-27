package com.electroradar.radar.scan;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Задаёт два шага пространственного поиска.
 *
 * <p>{@link #queryBounds()} должен быть консервативным AABB: он может включать лишних кандидатов,
 * но не должен исключать цель, которая проходит {@link #contains(Entity)}. Сканер передаёт
 * этот AABB миру для первичного поиска, а затем применяет точную проверку. Благодаря этому
 * будущий сектор или конус меняет только реализацию области, не добавляя геометрию в сканер.</p>
 */
public interface RadarScanArea {
    /** Пустая область не требует обращения к пространственному индексу мира. */
    default boolean isEmpty() { return false; }

    /** Возвращает грубую область поиска по уже загруженным сущностям. */
    AABB queryBounds();

    /** Возвращает результат точной проверки кандидата после пространственного запроса. */
    boolean contains(Entity entity);
}
