package com.electroradar.radar.network;

import java.util.Iterator;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.RadarNetworkState.CompletedScan;
import com.electroradar.radar.network.RadarNetworkState.NetworkRuntime;
import com.electroradar.radar.network.RadarNetworkState.RadarState;
import com.electroradar.radar.network.RadarNetworkState.SourceLink;
import com.electroradar.radar.network.RadarNetworkState.TargetEntry;

/**
 * Применяет закрытое окно обнаружений и готовит live-данные общей опубликованной версии.
 *
 * <p>Компонент не владеет расписанием и не открывает новое окно: это остаётся обязанностью
 * менеджера-координатора. Менеджер вызывает {@link #publishWindow(NetworkRuntime, long)}
 * синхронно на серверном потоке, а после его возврата продвигает epoch и revision. До возврата
 * запроса потребителя не происходит, поэтому наблюдатели видят переход целиком — уже с
 * согласованными индексами и live-данными, а не промежуточное состояние между радарами.</p>
 *
 * <p>Здесь сосредоточена логика отношений «радар–цель»: один объект {@link SourceLink}
 * одновременно лежит в обратном индексе радара и в списке источников цели. Удаление и забывание
 * всегда снимают обе ссылки; координаты и тип сущности не помещаются в эти связи.</p>
 */
final class RadarNetworkPublisher {
    private final MinecraftServer server;

    RadarNetworkPublisher(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Применяет все полные отчёты закрываемого epoch, забывает просроченные связи, удаляет
     * источники радаров без полного отчёта и получает LiveResult для оставшихся уникальных целей.
     *
     * <p>Порядок принципиален: сначала согласуются обе стороны всех связей, затем каждую цель,
     * оставшуюся в общей таблице, разрешаем в загруженном мире ровно один раз. Только после этого
     * выставляется publishedEpoch и очищается pending-буфер. Читатели не запускают lookup сами.
     * Обнаружение с возрастом ноль и доступность живой сущности остаются независимыми фактами.</p>
     *
     * @param network runtime-состояние сети, принадлежащее менеджеру
     * @param serverTick тик общей границы, используемый для времени LiveResult и диагностики
     */
    void publishWindow(NetworkRuntime network, long serverTick) {
        PublicationContext publication = new PublicationContext(network, network.currentEpoch, serverTick);
        long epoch = publication.epoch();
        for (Map.Entry<EndpointKey, RadarState> radarEntry : network.radars.entrySet()) {
            EndpointKey radar = radarEntry.getKey();
            RadarState state = radarEntry.getValue();
            if (state.participatesFromEpoch > epoch) {
                continue;
            }
            CompletedScan scan = network.pending.get(radar);
            if (scan == null || scan.epoch != epoch || !scan.token.equals(state.token)) {
                RadarDiagnostics.log("radar_missed_complete_report", network.id, radar, epoch, serverTick,
                        "reason=" + (scan == null ? "no_report" : "report_epoch_or_token_mismatch")
                                + " priorLinks=" + state.detections.size());
                removeAllRadarLinks(publication, radar, state, "missing_complete_report");
                continue;
            }
            applyCompletedScan(publication, radar, state, scan);
        }

        for (Map.Entry<TargetRef, TargetEntry> targetEntry : network.targets.entrySet()) {
            targetEntry.getValue().liveResult = resolveLiveResult(targetEntry.getKey(), publication);
        }
        network.publishedEpoch = publication.epoch();
        network.pending.clear();
    }

    /**
     * Немедленно снимает все связи радара при выгрузке или смене регистрации.
     * Удаление синхронно: после возврата ни запрос, ни следующая публикация не могут видеть
     * этот радар как источник. Связи других радаров с теми же целями остаются нетронутыми.
     *
     * @param network сеть, из которой снимается зарегистрированный радар
     * @param radar ключ снимаемого радара
     * @param state runtime-состояние его обратного индекса
     * @param reason причина снятия для диагностики
     * @param serverTick текущий тик менеджера
     */
    void removeRadarLinks(
            NetworkRuntime network,
            EndpointKey radar,
            RadarState state,
            String reason,
            long serverTick
    ) {
        long epoch = network.policy == null ? network.publishedEpoch : network.currentEpoch;
        removeAllRadarLinks(new PublicationContext(network, epoch, serverTick), radar, state, reason);
    }

    /**
     * Сверяет полный отчёт радара с его прежним обратным индексом и применяет забывание.
     * Возраст выводится из общего epoch, а не хранится как изменяемый счётчик: связь с возрастом
     * меньше forgetAfterMisses переживает пропуск, при достижении предела удаляется с обеих сторон.
     *
     * <p>Инвариант опубликованного состояния: для каждой связи
     * {@code 0 <= publishedEpoch - lastSeenEpoch < forgetAfterMisses}. Он держится потому, что
     * (1) связь создаётся или обновляется только здесь с {@code lastSeenEpoch = epoch};
     * (2) каждый участвующий радар на каждой границе либо проходит через этот метод, либо теряет
     * все связи в {@link #removeAllRadarLinks}, так что ни одна связь не «стареет» без проверки;
     * (3) между границами publishedEpoch не меняется. Отсюда при forgetAfterMisses = N цель
     * переживает N - 1 полных проходов без обнаружения и удаляется на N-м.</p>
     */
    private void applyCompletedScan(
            PublicationContext publication,
            EndpointKey radar,
            RadarState state,
            CompletedScan scan
    ) {
        NetworkRuntime network = publication.network();
        long epoch = publication.epoch();
        long serverTick = publication.serverTick();
        Iterator<Map.Entry<TargetRef, SourceLink>> existing = state.detections.entrySet().iterator();
        while (existing.hasNext()) {
            Map.Entry<TargetRef, SourceLink> detection = existing.next();
            if (!scan.foundTargets.containsKey(detection.getKey())) {
                long age = epoch - detection.getValue().lastSeenEpoch;
                if (age >= network.policy.forgetAfterMisses()) {
                    existing.remove();
                    removeTargetSource(publication, radar, detection.getKey(), detection.getValue(),
                            "forget_after_misses",
                            "age=" + age + " forgetAfterMisses=" + network.policy.forgetAfterMisses());
                } else {
                    RadarDiagnostics.log("link_remembered", network.id, radar, epoch, serverTick,
                            "target=" + detection.getKey() + " age=" + age
                                    + " lastSeenEpoch=" + detection.getValue().lastSeenEpoch);
                }
            }
        }

        for (RadarDetection detection : scan.foundTargets.values()) {
            TargetRef targetRef = detection.target();
            SourceLink link = state.detections.get(targetRef);
            if (link == null) {
                link = new SourceLink(epoch, scan.submittedTick, detection.classification());
                state.detections.put(targetRef, link);
                // Обе карты индексируют одну связь. Это не две копии обнаружения: удаление или
                // обновление источника всегда работает с тем же объектом SourceLink.
                network.targets.computeIfAbsent(targetRef, ignored -> new TargetEntry())
                        .sources.put(radar, link);
                RadarDiagnostics.log("link_added", network.id, radar, epoch, serverTick,
                        "target=" + targetRef + " detectedTick=" + scan.submittedTick
                                + " classification=" + detection.classification());
            } else {
                long previousLastSeenEpoch = link.lastSeenEpoch;
                long previousDetectedTick = link.lastDetectedTick;
                link.lastSeenEpoch = epoch;
                link.lastDetectedTick = scan.submittedTick;
                link.classification = detection.classification();
                RadarDiagnostics.log("link_updated", network.id, radar, epoch, serverTick,
                        "target=" + targetRef + " previousLastSeenEpoch=" + previousLastSeenEpoch
                                + " previousDetectedTick=" + previousDetectedTick
                                + " detectedTick=" + scan.submittedTick
                                + " classification=" + detection.classification());
            }
        }
    }

    /** Снимает обратный индекс радара и соответствующие связи из общей таблицы целей. */
    private void removeAllRadarLinks(
            PublicationContext publication,
            EndpointKey radar,
            RadarState state,
            String reason
    ) {
        Iterator<Map.Entry<TargetRef, SourceLink>> detections = state.detections.entrySet().iterator();
        while (detections.hasNext()) {
            Map.Entry<TargetRef, SourceLink> detection = detections.next();
            detections.remove();
            removeTargetSource(publication, radar, detection.getKey(), detection.getValue(), reason, "");
        }
    }

    /** Удаляет одну связь и освобождает TargetEntry только после ухода последнего радара. */
    private void removeTargetSource(
            PublicationContext publication,
            EndpointKey radar,
            TargetRef target,
            SourceLink link,
            String reason,
            String extraDetails
    ) {
        NetworkRuntime network = publication.network();
        TargetEntry entry = network.targets.get(target);
        int remainingSources = -1;
        boolean targetRemoved = false;
        if (entry != null) {
            entry.sources.remove(radar, link);
            remainingSources = entry.sources.size();
            if (entry.sources.isEmpty()) {
                targetRemoved = network.targets.remove(target, entry);
            }
        }
        RadarDiagnostics.log("link_removed", network.id, radar, publication.epoch(), publication.serverTick(),
                "target=" + target + " reason=" + reason + " remainingSources=" + remainingSources
                        + " targetRemoved=" + targetRemoved + (extraDetails.isBlank() ? "" : " " + extraDetails));
    }

    /**
     * Получает опубликованные живые данные без загрузки чанков и не сохраняет ссылку на Entity.
     * Отсутствующее измерение, выгруженная или удалённая сущность и неподдержанный источник
     * дают UNAVAILABLE, не удаляя обнаружение: его срок жизни задают SourceLink и политика сети.
     */
    private LiveResult resolveLiveResult(TargetRef target, PublicationContext publication) {
        long epoch = publication.epoch();
        long sampledTick = publication.serverTick();
        if (target.sourceKind() != TargetRef.SourceKind.ENTITY) {
            return LiveResult.unavailable(epoch, sampledTick);
        }
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null) {
            return LiveResult.unavailable(epoch, sampledTick);
        }
        Entity entity = level.getEntity(target.id());
        if (entity == null || !entity.isAlive()) {
            return LiveResult.unavailable(epoch, sampledTick);
        }
        return LiveResult.available(epoch, sampledTick, entity.position(), entity.getType());
    }

    /**
     * Фиксирует неизменный контекст одной публикации для вложенных операций изменения связей.
     * Это не копия сетевого состояния: один NetworkRuntime и одна пара epoch/tick позволяют
     * всем helper-методам подписывать изменения одной и той же закрываемой версии.
     */
    private record PublicationContext(NetworkRuntime network, long epoch, long serverTick) {
    }
}
