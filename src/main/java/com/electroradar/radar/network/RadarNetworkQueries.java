package com.electroradar.radar.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.RadarNetworkState.AccessibleRadar;
import com.electroradar.radar.network.RadarNetworkState.NetworkRuntime;
import com.electroradar.radar.network.RadarNetworkState.RadarState;
import com.electroradar.radar.network.RadarNetworkState.Registration;
import com.electroradar.radar.network.RadarNetworkState.SourceLink;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;

/**
 * Сбор неизменяемых ответов по опубликованному состоянию сети.
 *
 * <p>Менеджер передаёт сюда только уже проверенную регистрацию и выполняет вход с основного
 * серверного потока. В этом классе сосредоточены единое правило доступности радаров и обход
 * их обратных индексов. Результаты существуют только в рамках одного вызова; постоянных
 * списков целей для потребителей здесь нет.</p>
 */
final class RadarNetworkQueries {
    private RadarNetworkQueries() {
    }

    /**
     * Обходит обратные индексы только доступных радаров ровно один раз и объединяет находки
     * по полному {@link TargetRef}. Свежесть сводится через OR только по этим доступным связям:
     * цель не становится свежей из-за недоступного радара или {@link LiveResult#availability()}.
     * Детальные источники остаются ленивыми и создаются только по запросу проекции.
     */
    static <T> TargetSnapshot<T> queryTargets(
            EndpointKey consumer,
            Registration registration,
            TargetProjection<T> projection,
            long serverTick,
            long revisionSequence,
            Map<EndpointKey, Registration> endpointIndex,
            Map<NetworkId, NetworkRuntime> networks
    ) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(projection, "projection");
        NetworkRuntime network = networks.get(registration.networkId);
        if (network == null) {
            logQuery("target_snapshot_query", registration.networkId, consumer, 0, serverTick,
                    "accepted=true reason=no_runtime_network targets=0");
            return TargetSnapshot.empty(0, revisionSequence);
        }

        long epoch = network.publishedEpoch;
        long revision = network.revision;
        List<AccessibleRadar> radars = accessibleRadars(consumer, registration, network, endpointIndex);
        Set<EndpointKey> accessibleEndpoints = accessibleEndpoints(radars);

        // В аккумуляторе хранится только идентичность и один бит свежести. Он не дублирует
        // классификации, возраст и endpoint каждого источника, если проекция их не запросит.
        Map<TargetRef, Boolean> detectedThisWindow = new TreeMap<>(RadarNetworkState.TARGET_ORDER);
        for (AccessibleRadar radar : radars) {
            for (Map.Entry<TargetRef, SourceLink> detection : radar.state().detections.entrySet()) {
                boolean fresh = detection.getValue().lastSeenEpoch == epoch;
                detectedThisWindow.merge(detection.getKey(), fresh, (left, right) -> left || right);
            }
        }

        List<T> projectedTargets = new ArrayList<>(detectedThisWindow.size());
        int liveAvailable = 0;
        for (Map.Entry<TargetRef, Boolean> candidate : detectedThisWindow.entrySet()) {
            TargetEntry targetEntry = network.targets.get(candidate.getKey());
            // Обратный индекс и общая таблица меняются одним серверным переходом. Если это
            // нарушено будущим изменением, не раскрываем цель без канонической записи данных.
            if (targetEntry == null) {
                continue;
            }
            LiveResult liveResult = Objects.requireNonNull(
                    targetEntry.liveResult, "Published target must have live data");
            if (liveResult.availability() == TargetAvailability.AVAILABLE) {
                liveAvailable++;
            }
            TargetView view = new TargetView(
                    candidate.getKey(),
                    candidate.getValue(),
                    liveResult,
                    () -> accessibleSources(targetEntry, accessibleEndpoints, epoch));
            try {
                projectedTargets.add(Objects.requireNonNull(
                        projection.project(view), "TargetProjection must not return null"));
            } finally {
                // Проекция не может сохранить ленивый доступ к изменяемым картам следующего окна.
                view.expire();
            }
        }

        logQuery("target_snapshot_query", registration.networkId, consumer, epoch, serverTick,
                "accepted=true targets=" + projectedTargets.size()
                        + " available=" + liveAvailable
                        + " unavailable=" + (projectedTargets.size() - liveAvailable)
                        + " accessibleRadars=" + radars.size());
        return new TargetSnapshot<>(epoch, revision, projectedTargets);
    }

    /** Возвращает подробные подтверждения одной заранее известной цели. */
    static TargetSources sourcesForTarget(
            EndpointKey consumer,
            Registration registration,
            TargetRef target,
            long serverTick,
            long revisionSequence,
            Map<EndpointKey, Registration> endpointIndex,
            Map<NetworkId, NetworkRuntime> networks
    ) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(target, "target");
        NetworkRuntime network = networks.get(registration.networkId);
        long epoch = network == null ? 0 : network.publishedEpoch;
        long revision = network == null ? revisionSequence : network.revision;
        if (network == null) {
            logQuery("target_sources_query", registration.networkId, consumer, epoch, serverTick,
                    "accepted=true reason=no_runtime_network target=" + target + " sources=0");
            return new TargetSources(target, epoch, revision, List.of(), Optional.empty());
        }

        TargetEntry entry = network.targets.get(target);
        if (entry == null) {
            logQuery("target_sources_query", registration.networkId, consumer, epoch, serverTick,
                    "accepted=true reason=target_not_published target=" + target + " sources=0");
            return new TargetSources(target, epoch, revision, List.of(), Optional.empty());
        }

        List<AccessibleRadar> radars = accessibleRadars(consumer, registration, network, endpointIndex);
        List<RadarSource> sources = accessibleSources(entry, accessibleEndpoints(radars), epoch);
        Optional<LiveResult> liveResult = sources.isEmpty()
                ? Optional.empty()
                : Optional.of(Objects.requireNonNull(entry.liveResult, "Published target must have live data"));
        logQuery("target_sources_query", registration.networkId, consumer, epoch, serverTick,
                "accepted=true target=" + target + " outcome="
                        + (sources.isEmpty() ? "not_accessible" : "accessible")
                        + " sources=" + sources.size()
                        + " detectedThisWindow=" + sources.stream().anyMatch(RadarSource::detectedThisWindow));
        return new TargetSources(target, epoch, revision, sources, liveResult);
    }

    /** Возвращает сохранённый сетевой выбор независимо от текущего обнаружения этой цели. */
    static Optional<TargetRef> selectedTarget(
            EndpointKey consumer,
            Registration registration,
            long serverTick,
            Map<NetworkId, NetworkRuntime> networks
    ) {
        NetworkRuntime network = networks.get(registration.networkId);
        Optional<TargetRef> selected = network == null
                ? Optional.empty()
                : Optional.ofNullable(network.selectedTarget);
        logQuery("selected_target_query", registration.networkId, consumer,
                network == null ? 0 : network.publishedEpoch, serverTick,
                "accepted=true selected=" + selected.map(Object::toString).orElse("none"));
        return selected;
    }

    /**
     * Единое правило доступности: только зарегистрированные радары той же сети и измерения.
     * Геометрия здесь намеренно отсутствует; полный список, одна цель и выбор используют этот
     * результат, чтобы не расходиться по правам доступа.
     */
    static List<AccessibleRadar> accessibleRadars(
            EndpointKey consumer,
            Registration consumerRegistration,
            NetworkRuntime network,
            Map<EndpointKey, Registration> endpointIndex
    ) {
        List<AccessibleRadar> available = new ArrayList<>();
        for (Map.Entry<EndpointKey, RadarState> entry : network.radars.entrySet()) {
            EndpointKey radar = entry.getKey();
            RadarState state = entry.getValue();
            Registration registration = endpointIndex.get(radar);
            if (registration != null
                    && registration.role == EndpointRole.RADAR
                    && registration.networkId.equals(consumerRegistration.networkId)
                    && registration.token.equals(state.token)
                    && radar.dimension().equals(consumer.dimension())) {
                available.add(new AccessibleRadar(radar, state));
            }
        }
        return available;
    }

    /** Проверяет текущую свежесть только в уже отфильтрованном наборе доступных радаров. */
    static boolean hasFreshAccessibleSource(
            List<AccessibleRadar> radars,
            TargetRef target,
            long publishedEpoch
    ) {
        for (AccessibleRadar radar : radars) {
            SourceLink link = radar.state().detections.get(target);
            if (link != null && link.lastSeenEpoch == publishedEpoch) {
                return true;
            }
        }
        return false;
    }

    /** Преобразует уже вычисленную доступность в индекс для ленивой сборки детализации. */
    private static Set<EndpointKey> accessibleEndpoints(List<AccessibleRadar> radars) {
        Set<EndpointKey> endpoints = new HashSet<>();
        for (AccessibleRadar radar : radars) {
            endpoints.add(radar.endpoint());
        }
        return endpoints;
    }

    /** Создаёт RadarSource только по явному запросу конкретной проекции. */
    private static List<RadarSource> accessibleSources(
            TargetEntry target,
            Set<EndpointKey> accessibleEndpoints,
            long publishedEpoch
    ) {
        List<RadarSource> sources = new ArrayList<>();
        for (Map.Entry<EndpointKey, SourceLink> source : target.sources.entrySet()) {
            if (accessibleEndpoints.contains(source.getKey())) {
                sources.add(toRadarSource(source.getKey(), source.getValue(), publishedEpoch));
            }
        }
        return sources;
    }

    /** Возраст выводится только из последней общей публикации; отдельный счётчик не хранится. */
    private static RadarSource toRadarSource(EndpointKey radar, SourceLink link, long publishedEpoch) {
        return new RadarSource(radar, publishedEpoch - link.lastSeenEpoch,
                link.lastDetectedTick, link.classification);
    }

    /** Частые чтения не пишутся в INFO; детализацию можно включить отдельным флагом. */
    private static void logQuery(
            String event,
            NetworkId networkId,
            EndpointKey consumer,
            long epoch,
            long serverTick,
            String details
    ) {
        if (RadarDiagnostics.queryDiagnosticsEnabled()) {
            RadarDiagnostics.log(event, networkId, consumer, epoch, serverTick, details);
        }
    }
}
