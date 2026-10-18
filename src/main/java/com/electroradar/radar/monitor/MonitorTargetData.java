package com.electroradar.radar.monitor;

import java.util.Objects;

import com.electroradar.radar.network.TargetRef;

/**
 * Минимальная, подтверждённая радаром запись цели для экранного снимка.
 *
 * <p>Сервер передаёт координаты и registry ID типа только для обнаружения текущего окна при
 * доступном {@code LiveResult}. Для помнимой или недоступной цели поля координат отсутствуют;
 * клиент может оставить последнюю подтверждённую точку из предыдущего снимка, но не получает
 * движение сущности за пределами нового наблюдения.</p>
 */
public record MonitorTargetData(
        TargetRef target,
        boolean detectedThisWindow,
        boolean hasPosition,
        double x,
        double y,
        double z,
        String entityTypeId
) {
    public MonitorTargetData {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        if (hasPosition && !detectedThisWindow) {
            throw new IllegalArgumentException("Only a current detection may carry a live position");
        }
        if (!hasPosition && !entityTypeId.isEmpty()) {
            throw new IllegalArgumentException("A target without a live position cannot carry an entity type");
        }
    }
}
