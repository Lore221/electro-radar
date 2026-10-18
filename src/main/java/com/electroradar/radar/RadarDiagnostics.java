package com.electroradar.radar;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;

/**
 * Общий INFO-журнал сканера и сетевого ядра.
 *
 * <p>Единая точка логирования удерживает один формат полей для событий, которые происходят
 * в разных слоях: по network, radar, epoch и tick можно восстановить путь одного отчёта.
 * Флаг намеренно глобальный, чтобы диагностика включалась или выключалась сразу для всех
 * сетей, а не только для тестового блока. Дорогие списки целей вызывающая сторона должна
 * собирать только при включённом флаге.</p>
 */
public final class RadarDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Единственный переключатель подробной диагностики для всех серверных сетей. */
    private static final boolean ENABLED = true;

    /** Отдельный opt-in для частых чтений; по умолчанию запросы потребителей не засоряют INFO. */
    private static final boolean QUERY_DIAGNOSTICS_ENABLED = false;

    private RadarDiagnostics() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Показывает, включён ли отдельный подробный журнал запросов потребителей. */
    public static boolean queryDiagnosticsEnabled() {
        return ENABLED && QUERY_DIAGNOSTICS_ENABLED;
    }

    public static void log(
            String event,
            NetworkId network,
            EndpointKey radar,
            Object epoch,
            Object tick,
            String details
    ) {
        if (!ENABLED) {
            return;
        }
        LOGGER.info("[ElectroRadar/RadarDebug] event={} network={} radar={} epoch={} tick={} {}",
                event,
                network == null ? "-" : network.value(),
                radar == null ? "-" : radar,
                epoch == null ? "-" : epoch,
                tick == null ? "-" : tick,
                details == null ? "" : details);
    }

    /**
     * Записывает контекст события и само исключение на INFO с полным stack trace.
     *
     * <p>Причину нельзя сводить к имени класса и тексту сообщения: строки стека показывают,
     * в каком именно вызове сломалась регистрация, сканирование или сдача отчёта. Последний
     * аргумент Throwable передаётся логгеру отдельно, поэтому SLF4J печатает stack trace.
     * Дополнительные подробности нужны для параметров операции, например длительности скана.</p>
     */
    public static void logException(
            String event,
            NetworkId network,
            EndpointKey radar,
            Object epoch,
            Object tick,
            String details,
            Throwable exception
    ) {
        if (!ENABLED) {
            return;
        }
        LOGGER.info("[ElectroRadar/RadarDebug] event={} network={} radar={} epoch={} tick={} {} exception={}",
                event,
                network == null ? "-" : network.value(),
                radar == null ? "-" : radar,
                epoch == null ? "-" : epoch,
                tick == null ? "-" : tick,
                details == null ? "" : details,
                exception == null ? "-" : exception.toString(),
                exception);
    }

}
