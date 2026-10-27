package com.electroradar.radar;

import java.util.function.Supplier;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;

/**
 * Общий журнал сканера, сетевого ядра и визуального клиента: события на INFO, ошибки на ERROR.
 *
 * <p>Единая точка логирования удерживает один формат полей для событий, которые происходят
 * в разных слоях: по network, radar, epoch и tick можно восстановить путь одного отчёта.
 * Флаг намеренно глобальный, чтобы диагностика включалась или выключалась сразу для всех
 * сетей, а не только для тестового блока. Дорогие списки целей вызывающая сторона должна
 * собирать только при включённом флаге.</p>
 */
public final class RadarDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Единственный переключатель подробной диагностики для всех серверных сетей.
     * По умолчанию выключен: подробный журнал пишет события каждого окна и каждого снимка.
     * Включается без пересборки JVM-флагом {@code -Delectroradar.diagnostics=true}.
     * Ошибки продолжают записываться независимо от этого флага.
     */
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("electroradar.diagnostics", "false"));

    /**
     * Отдельный opt-in для частых чтений; по умолчанию запросы потребителей не засоряют INFO.
     * Включается флагом {@code -Delectroradar.diagnostics.queries=true}.
     */
    private static final boolean QUERY_DIAGNOSTICS_ENABLED =
            Boolean.parseBoolean(System.getProperty("electroradar.diagnostics.queries", "false"));

    /** События смены активной визуальной части могут повторяться для каждого радара и монитора. */
    private static final boolean VISUAL_DIAGNOSTICS_ENABLED =
            Boolean.parseBoolean(System.getProperty("electroradar.diagnostics.visuals", "false"));

    private RadarDiagnostics() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Показывает, включён ли отдельный подробный журнал запросов потребителей. */
    public static boolean queryDiagnosticsEnabled() {
        return ENABLED && QUERY_DIAGNOSTICS_ENABLED;
    }

    /** Отдельная подробная дорожка для частых клиентских событий смены визуального сектора. */
    public static boolean visualDiagnosticsEnabled() {
        return ENABLED && VISUAL_DIAGNOSTICS_ENABLED;
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
     * Ленивый вариант для частых событий: строка подробностей собирается только при включённом
     * журнале. Используйте его везде, где событие происходит каждое окно, тик или снимок.
     */
    public static void log(
            String event,
            NetworkId network,
            EndpointKey radar,
            Object epoch,
            Object tick,
            Supplier<String> details
    ) {
        if (!ENABLED) {
            return;
        }
        log(event, network, radar, epoch, tick, details == null ? null : details.get());
    }

    /**
     * Записывает контекст ошибки и само исключение на ERROR с полным stack trace.
     *
     * <p>Причину нельзя сводить к имени класса и тексту сообщения: строки стека показывают,
     * в каком именно вызове сломалась регистрация, сканирование или сдача отчёта. Последний
     * аргумент Throwable передаётся логгеру отдельно, поэтому SLF4J печатает stack trace.
     * Дополнительные подробности нужны для параметров операции, например длительности скана.
     * Ошибки нельзя отключать вместе с подробными событиями: вызывающий код перехватывает
     * исключения, и иначе причина остановки радара исчезнет из журнала.</p>
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
        LOGGER.error("[ElectroRadar/RadarDebug] event={} network={} radar={} epoch={} tick={} {} exception={}",
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
