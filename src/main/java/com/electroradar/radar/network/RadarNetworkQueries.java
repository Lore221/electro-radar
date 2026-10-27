package com.electroradar.radar.network;

import java.util.ArrayList;
import java.util.Collection;
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
import com.electroradar.radar.network.RadarNetworkState.SectorConfirmation;
import com.electroradar.radar.network.RadarNetworkState.SourceLink;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;

/** Раздельные запросы опубликованных идентичностей и разрешённых сведений о целях. */
final class RadarNetworkQueries {
    private RadarNetworkQueries() { }

    static TargetSnapshot<TargetRef> queryTargetRefs(EndpointKey consumer, Registration registration,
                                                      long serverTick,
                                                      Map<EndpointKey, Registration> endpointIndex,
                                                      Map<NetworkId, NetworkRuntime> networks) {
        NetworkRuntime network = networks.get(registration.networkId);
        if (network == null) return TargetSnapshot.empty(0, 0);
        List<AccessibleRadar> radars = accessibleRadars(consumer, registration, network, endpointIndex);
        Set<TargetRef> targets = new HashSet<>();
        for (AccessibleRadar radar : radars) targets.addAll(radar.state().detections.keySet());
        List<TargetRef> ordered = targets.stream().filter(target -> target.dimension().equals(consumer.dimension()))
                .sorted(RadarNetworkState.TARGET_ORDER).toList();
        logQuery("target_identity_query", registration.networkId, consumer, network.publishedEpoch, serverTick,
                "targets=" + ordered.size() + " accessibleRadars=" + radars.size());
        return new TargetSnapshot<>(network.publishedEpoch, network.revision,
                network.measurementRevision, ordered);
    }

    /**
     * Рассчитывает доступность радаров один раз на группу. Переданные UUID проверяются заново
     * через текущий обратный индекс; сохранённый потребителем список не даёт постоянных прав.
     */
    static <T> TargetSnapshot<T> queryTargetDetails(EndpointKey consumer, Registration registration,
                                                     Collection<TargetRef> requestedTargets,
                                                     TargetProjection<T> projection, long serverTick,
                                                     Map<EndpointKey, Registration> endpointIndex,
                                                     Map<NetworkId, NetworkRuntime> networks) {
        Objects.requireNonNull(requestedTargets, "requestedTargets");
        Objects.requireNonNull(projection, "projection");
        NetworkRuntime network = networks.get(registration.networkId);
        if (network == null) return TargetSnapshot.empty(0, 0);
        long epoch = network.publishedEpoch;
        List<AccessibleRadar> radars = accessibleRadars(consumer, registration, network, endpointIndex);
        Set<EndpointKey> accessible = accessibleEndpoints(radars);
        List<TargetRef> ordered = requestedTargets.stream().filter(Objects::nonNull)
                .filter(target -> target.dimension().equals(consumer.dimension()))
                .distinct().sorted(RadarNetworkState.TARGET_ORDER).toList();
        List<T> results = new ArrayList<>(ordered.size());
        for (TargetRef ref : ordered) {
            TargetEntry entry = network.targets.get(ref);
            if (entry == null || entry.sources.keySet().stream().noneMatch(accessible::contains)) continue;
            Optional<LiveResult> live = latestAccessibleMeasurement(entry, accessible);
            LiveResult result = live.orElseGet(() -> LiveResult.unavailable(epoch, 0L, -1L, 0L));
            boolean observing = result.observing();
            TargetView view = new TargetView(ref, observing, result,
                    () -> accessibleSources(entry, accessible));
            try {
                results.add(Objects.requireNonNull(projection.project(view),
                        "TargetProjection must not return null"));
            } finally {
                view.expire();
            }
        }
        logQuery("target_details_query", registration.networkId, consumer, epoch, serverTick,
                "requested=" + ordered.size() + " returned=" + results.size()
                        + " accessibleRadars=" + radars.size());
        return new TargetSnapshot<>(epoch, network.revision, network.measurementRevision, results);
    }

    static <T> TargetSnapshot<T> queryTargets(EndpointKey consumer, Registration registration,
                                               TargetProjection<T> projection, long serverTick,
                                               Map<EndpointKey, Registration> endpointIndex,
                                               Map<NetworkId, NetworkRuntime> networks) {
        TargetSnapshot<TargetRef> refs = queryTargetRefs(consumer, registration, serverTick,
                endpointIndex, networks);
        return queryTargetDetails(consumer, registration, refs.targets(), projection, serverTick,
                endpointIndex, networks);
    }

    static TargetSources sourcesForTarget(EndpointKey consumer, Registration registration, TargetRef target,
                                          long serverTick,
                                          Map<EndpointKey, Registration> endpointIndex,
                                          Map<NetworkId, NetworkRuntime> networks) {
        NetworkRuntime network = networks.get(registration.networkId);
        long epoch = network == null ? 0 : network.publishedEpoch;
        long revision = network == null ? 0 : network.revision;
        if (network == null || !target.dimension().equals(consumer.dimension())) {
            return new TargetSources(target, epoch, revision, List.of(), Optional.empty());
        }
        TargetEntry entry = network.targets.get(target);
        if (entry == null) return new TargetSources(target, epoch, revision, List.of(), Optional.empty());
        List<AccessibleRadar> radars = accessibleRadars(consumer, registration, network, endpointIndex);
        Set<EndpointKey> endpoints = accessibleEndpoints(radars);
        List<RadarSource> sources = accessibleSources(entry, endpoints);
        Optional<LiveResult> live = latestAccessibleMeasurement(entry, endpoints);
        logQuery("target_sources_query", registration.networkId, consumer, epoch, serverTick,
                "target=" + target + " sources=" + sources.size() + " observing="
                        + sources.stream().anyMatch(RadarSource::observing));
        return new TargetSources(target, epoch, revision, sources, live);
    }

    static Optional<TargetRef> selectedTarget(EndpointKey consumer, Registration registration,
                                               long serverTick, Map<NetworkId, NetworkRuntime> networks) {
        NetworkRuntime network = networks.get(registration.networkId);
        TargetRef current = network == null ? null : network.selectedTarget;
        Optional<TargetRef> selected = current != null && current.dimension().equals(consumer.dimension())
                ? Optional.of(current) : Optional.empty();
        logQuery("selected_target_query", registration.networkId, consumer,
                network == null ? 0 : network.publishedEpoch, serverTick,
                "accepted=true selected=" + selected.map(Object::toString).orElse("none"));
        return selected;
    }

    static List<AccessibleRadar> accessibleRadars(EndpointKey consumer, Registration consumerRegistration,
                                                   NetworkRuntime network,
                                                   Map<EndpointKey, Registration> endpointIndex) {
        List<AccessibleRadar> available = new ArrayList<>();
        for (Map.Entry<EndpointKey, RadarState> entry : network.radars.entrySet()) {
            Registration registration = endpointIndex.get(entry.getKey());
            RadarState state = entry.getValue();
            if (registration != null && registration.role == EndpointRole.RADAR
                    && registration.networkId.equals(consumerRegistration.networkId)
                    && registration.token.equals(state.token)
                    && entry.getKey().dimension().equals(consumer.dimension())) {
                available.add(new AccessibleRadar(entry.getKey(), state));
            }
        }
        return available;
    }

    static boolean hasFreshAccessibleSource(List<AccessibleRadar> radars, TargetRef target) {
        for (AccessibleRadar radar : radars) {
            SourceLink link = radar.state().detections.get(target);
            if (link != null && bestMeasurement(link).map(LiveResult::observing).orElse(false)) return true;
        }
        return false;
    }

    private static Set<EndpointKey> accessibleEndpoints(List<AccessibleRadar> radars) {
        Set<EndpointKey> endpoints = new HashSet<>();
        for (AccessibleRadar radar : radars) endpoints.add(radar.endpoint());
        return endpoints;
    }

    private static Optional<LiveResult> latestAccessibleMeasurement(TargetEntry entry, Set<EndpointKey> endpoints) {
        LiveResult selected = null;
        for (Map.Entry<EndpointKey, SourceLink> source : entry.sources.entrySet()) {
            if (!endpoints.contains(source.getKey())) continue;
            LiveResult candidate = bestMeasurement(source.getValue()).orElse(null);
            if (candidate != null && (selected == null
                    || candidate.observing() && !selected.observing()
                    || candidate.observing() == selected.observing()
                    && candidate.sampledTick() > selected.sampledTick())) {
                selected = candidate;
            }
        }
        return Optional.ofNullable(selected);
    }

    private static Optional<LiveResult> bestMeasurement(SourceLink link) {
        LiveResult selected = null;
        for (SectorConfirmation confirmation : link.sectors.values()) {
            LiveResult candidate = confirmation.measurement;
            if (candidate != null && (selected == null
                    || candidate.observing() && !selected.observing()
                    || candidate.observing() == selected.observing()
                    && candidate.sampledTick() > selected.sampledTick())) selected = candidate;
        }
        return Optional.ofNullable(selected);
    }

    private static List<RadarSource> accessibleSources(TargetEntry target, Set<EndpointKey> endpoints) {
        List<RadarSource> sources = new ArrayList<>();
        for (Map.Entry<EndpointKey, SourceLink> source : target.sources.entrySet()) {
            if (endpoints.contains(source.getKey())) {
                bestConfirmation(source.getValue()).ifPresent(value -> sources.add(new RadarSource(
                        source.getKey(), value.getKey(), value.getValue().missedVisits,
                        value.getValue().measurement != null && value.getValue().measurement.observing(),
                        value.getValue().lastSuccessfulVisitTick,
                        value.getValue().classification)));
            }
        }
        return List.copyOf(sources);
    }

    private static List<RadarSource> accessibleSources(TargetEntry target, Set<EndpointKey> endpoints,
                                                       long ignoredPublishedEpoch) {
        return accessibleSources(target, endpoints);
    }

    private static Optional<Map.Entry<Integer, SectorConfirmation>> bestConfirmation(SourceLink link) {
        Map.Entry<Integer, SectorConfirmation> selected = null;
        for (Map.Entry<Integer, SectorConfirmation> entry : link.sectors.entrySet()) {
            LiveResult candidate = entry.getValue().measurement;
            LiveResult current = selected == null ? null : selected.getValue().measurement;
            if (selected == null || candidate != null && (current == null
                    || candidate.observing() && !current.observing()
                    || candidate.observing() == current.observing()
                    && candidate.sampledTick() > current.sampledTick())) selected = entry;
        }
        return Optional.ofNullable(selected);
    }

    private static void logQuery(String event, NetworkId networkId, EndpointKey consumer,
                                 long epoch, long serverTick, String details) {
        if (RadarDiagnostics.queryDiagnosticsEnabled()) {
            RadarDiagnostics.log(event, networkId, consumer, epoch, serverTick, details);
        }
    }
}
