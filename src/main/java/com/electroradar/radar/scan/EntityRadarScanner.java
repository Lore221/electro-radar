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
 */
public final class EntityRadarScanner {
    /**
     * Выполняет полный поиск: грубая выборка мира, фильтр допуска, точная область и дедупликация.
     * Predicate применяется внутри запроса мира вместе с проверкой {@code isAlive}; это уменьшает
     * последующую работу, а {@link RadarScanArea#contains(Entity)} остаётся обязательной точной проверкой.
     *
     * @param level серверное измерение, в котором выполняется поиск
     * @param area контракт грубой и точной пространственной проверки
     * @param eligibility дополнительное правило допуска конкретного радара
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

        // NeoForge добавляет в выборку части составных сущностей (например, дракона). У части свой
        // UUID, но ServerLevel.getEntity(UUID) её не находит, поэтому целью остаётся только родитель.
        List<Entity> candidates = level.getEntities((Entity) null, area.queryBounds(),
                entity -> !(entity instanceof PartEntity<?>) && entity.isAlive() && eligibility.test(entity));
        Map<TargetRef, RadarDetection> found = new LinkedHashMap<>();
        int acceptedEntities = 0;
        for (Entity entity : candidates) {
            if (!area.contains(entity)) {
                continue;
            }
            acceptedEntities++;
            // Полный TargetRef отделяет одинаковые UUID из разных измерений и видов источника.
            TargetRef ref = new TargetRef(level.dimension(), TargetRef.SourceKind.ENTITY, entity.getUUID());
            found.putIfAbsent(ref, RadarDetection.of(ref));
        }
        return new ScanResult(candidates.size(), acceptedEntities, List.copyOf(found.values()));
    }

    /**
     * Полный неизменяемый результат одного прохода.
     * {@code candidateCount} считает прошедшие грубый AABB-запрос и eligibility сущности;
     * {@code acceptedEntityCount} считает также прошедшие точную форму, но до дедупликации;
     * {@code detections} содержит уникальные цели для сетевого API.
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
