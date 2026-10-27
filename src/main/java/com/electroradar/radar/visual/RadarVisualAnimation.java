package com.electroradar.radar.visual;

/**
 * Визуальный способ показать уже опубликованное наблюдение радара.
 *
 * <p>Значения предназначены одновременно для профиля и ограниченного сетевого кодека.
 * Числовые идентификаторы задаются явно и не должны совпадать с ordinal enum: перестановка
 * констант в исходном коде не меняет смысл уже закодированного пакета.</p>
 */
public enum RadarVisualAnimation {
    /** Загорание равных частей направленной области в стабильной перестановке профиля. */
    SECTOR_SWEEP(1),
    /** Один общий короткий импульс по всей фактической области радара. */
    FULL_FLASH(2);

    private final int wireId;

    RadarVisualAnimation(int wireId) {
        this.wireId = wireId;
    }

    /** Стабильный ID для {@code MonitorSnapshotPayload}; ordinal использовать нельзя. */
    public int wireId() {
        return wireId;
    }

    /**
     * Разбирает только известные режимы и отклоняет повреждённый или новый неподдерживаемый ID.
     * Клиент не должен молча угадывать режим: это могло бы показать геометрию не тем способом.
     */
    public static RadarVisualAnimation fromWireId(int wireId) {
        return switch (wireId) {
            case 1 -> SECTOR_SWEEP;
            case 2 -> FULL_FLASH;
            default -> throw new IllegalArgumentException("Unknown radar visual animation id: " + wireId);
        };
    }
}
