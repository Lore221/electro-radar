package com.electroradar.radar.scan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.PartEntity;

import com.electroradar.radar.network.RadarDetection;
import com.electroradar.radar.network.TargetRef;

/**
 * Stateless-сервис одного полного прохода по загруженным сущностям мира.
 *
 * <p>Область, фильтр и расписание принадлежат вызывающему прибору. Сканер не знает о сети,
 * endpoint или длительности окна и не сохраняет ссылок на Entity после возврата. Поиск
 * выполняется через {@code ServerLevel.getEntities}; он не загружает чанки и не делает
 * дополнительный UUID lookup. Результат содержит только идентичности целей, пригодные
 * для передачи сетевому менеджеру.</p>
 *
 * <p><b>Составные сущности.</b> В NeoForge hitbox такой сущности (например, дракона) состоит из
 * собственного bounding box родителя и отдельных {@link PartEntity}. {@code Level.getEntities}
 * возвращает родителя, только если область пересекает его собственный box, а части добавляет
 * отдельным проходом по {@code getPartEntities()} со своей проверкой пересечения. Поэтому
 * части нельзя отбрасывать: если область задевает только хвост или голову дракона, в выборке
 * будет лишь часть, и без неё родитель потерялся бы. Инварианты прохода:</p>
 * <ul>
 *   <li>геометрия проверяется по тому фрагменту hitbox, который вернул мир (родитель или часть):
 *       цель найдена, если область пересекает хотя бы один её фрагмент;</li>
 *   <li>идентичность, допуск и «живость» всегда берутся у корневого родителя: UUID части
 *       {@code ServerLevel.getEntity(UUID)} не разрешает, и цель с таким UUID навсегда осталась бы
 *       UNAVAILABLE;</li>
 *   <li>дедупликация идёт по {@link TargetRef} родителя, поэтому несколько попавших в область
 *       частей и сам родитель дают ровно одно обнаружение.</li>
 * </ul>
 */
public final class EntityRadarScanner {
    /**
     * Предел глубины цепочки «часть → родитель». В NeoForge части не вкладываются друг в друга,
     * но чужой мод может это сделать; предел защищает от зацикленной цепочки.
     */
    private static final int MAX_PART_DEPTH = 8;

    /**
     * Выполняет полный поиск: грубая выборка мира, фильтр допуска, точная область и дедупликация.
     * Predicate применяется внутри запроса мира к корневой цели вместе с проверкой {@code isAlive};
     * это уменьшает последующую работу, а {@link RadarScanArea#contains(Entity)} остаётся
     * обязательной точной проверкой фрагмента hitbox.
     *
     * @param level серверное измерение, в котором выполняется поиск
     * @param area контракт грубой и точной пространственной проверки
     * @param eligibility дополнительное правило допуска конкретного радара; получает корневую цель, а не часть
     * @return неизменяемый полный результат; пустой список также означает завершённый проход
     */
    public ScanResult scan(
            ServerLevel level,
            RadarScanArea area,
            Predicate<? super Entity> eligibility
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(eligibility, "eligibility");

        // Каждый элемент выборки — фрагмент hitbox: обычная сущность, родитель составной сущности
        // или одна из его частей. Фильтр смотрит на цель, которую фрагмент представляет.
        List<Entity> candidates = level.getEntities((Entity) null, area.queryBounds(), hitbox -> {
            Entity target = rootTarget(hitbox);
            return target != null && target.isAlive() && eligibility.test(target);
        });
        Map<TargetRef, RadarDetection> found = new LinkedHashMap<>();
        int acceptedHitboxes = 0;
        for (Entity hitbox : candidates) {
            // Точная форма проверяется по собственному box фрагмента: именно он пересёкся с грубым AABB.
            if (!area.contains(hitbox)) {
                continue;
            }
            Entity target = rootTarget(hitbox);
            if (target == null) {
                continue;
            }
            acceptedHitboxes++;
            // Полный TargetRef отделяет одинаковые UUID из разных измерений и видов источника.
            // Для части здесь UUID родителя, поэтому putIfAbsent схлопывает все её фрагменты в одну цель.
            TargetRef ref = new TargetRef(level.dimension(), TargetRef.SourceKind.ENTITY, target.getUUID());
            found.putIfAbsent(ref, RadarDetection.of(ref));
        }
        return new ScanResult(candidates.size(), acceptedHitboxes, List.copyOf(found.values()));
    }

    /**
     * Возвращает сущность, которую представляет фрагмент hitbox: себя для обычной сущности или
     * корневого родителя для {@link PartEntity}. Возвращает {@code null}, если цепочка слишком
     * глубокая или родитель оказался в другом мире: такую цель нельзя честно отнести к этому проходу.
     */
    private static Entity rootTarget(Entity hitbox) {
        Entity current = hitbox;
        for (int depth = 0; current instanceof PartEntity<?> part; depth++) {
            if (depth >= MAX_PART_DEPTH) {
                return null;
            }
            current = part.getParent();
            if (current == null) {
                return null;
            }
        }
        return current.level() == hitbox.level() ? current : null;
    }

    /**
     * Полный неизменяемый результат одного прохода.
     * {@code candidateCount} считает фрагменты hitbox (сущности и части), прошедшие грубый
     * AABB-запрос и допуск их цели; {@code acceptedEntityCount} считает фрагменты, прошедшие ещё
     * и точную форму, до дедупликации по цели; {@code detections} содержит уникальные цели
     * для сетевого API. Поэтому у дракона, задетого тремя частями, accepted=3, а обнаружение одно.
     */
    public record ScanResult(int candidateCount, int acceptedEntityCount, List<RadarDetection> detections) {
        public ScanResult {
            if (candidateCount < 0 || acceptedEntityCount < 0 || acceptedEntityCount > candidateCount) {
                throw new IllegalArgumentException("Scan counts are invalid");
            }
            detections = List.copyOf(detections);
        }
    }
}
