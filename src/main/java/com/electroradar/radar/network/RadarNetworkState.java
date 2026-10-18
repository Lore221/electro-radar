package com.electroradar.radar.network;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Runtime-модели, общие для менеджера сети, читательских запросов и диагностики.
 *
 * <p>Типы и поля доступны только внутри пакета: публичный API сети по-прежнему состоит
 * из отдельных неизменяемых DTO и методов {@link RadarNetworkManager}. Изменяемые карты
 * здесь принадлежат одному серверному потоку; эти структуры не должны уходить вызывающему
 * коду и не предназначены для хранения между запусками сервера.</p>
 */
final class RadarNetworkState {
    /** Один порядок нужен и для детерминированных обходов, и для читаемых снимков логов. */
    static final Comparator<EndpointKey> ENDPOINT_ORDER = RadarNetworkState::compareEndpointKeys;

    /** TargetRef сортируется по устойчивым строковым идентификаторам измерения, типа и цели. */
    static final Comparator<TargetRef> TARGET_ORDER = Comparator
            .comparing((TargetRef target) -> target.dimension().location().toString())
            .thenComparing(target -> target.sourceKind().name())
            .thenComparing(target -> target.id().toString());

    private RadarNetworkState() {
    }

    /** Сравнивает варианты статических и подвижных endpoint без зависимости от hash map order. */
    private static int compareEndpointKeys(EndpointKey left, EndpointKey right) {
        int dimensionOrder = left.dimension().location().compareTo(right.dimension().location());
        if (dimensionOrder != 0) {
            return dimensionOrder;
        }
        if (left instanceof EndpointKey.Static leftStatic) {
            if (right instanceof EndpointKey.Moving) {
                return -1;
            }
            return leftStatic.position().compareTo(((EndpointKey.Static) right).position());
        }
        if (right instanceof EndpointKey.Static) {
            return 1;
        }
        EndpointKey.Moving leftMoving = (EndpointKey.Moving) left;
        EndpointKey.Moving rightMoving = (EndpointKey.Moving) right;
        int structureOrder = leftMoving.structureId().compareTo(rightMoving.structureId());
        return structureOrder != 0
                ? structureOrder
                : leftMoving.localPosition().compareTo(rightMoving.localPosition());
    }

    /** Текущая регистрация endpoint; token защищает от действий от имени старой загрузки блока. */
    static final class Registration {
        final NetworkId networkId;
        final EndpointRole role;
        final RegistrationToken token;

        Registration(NetworkId networkId, EndpointRole role, RegistrationToken token) {
            this.networkId = networkId;
            this.role = role;
            this.token = token;
        }
    }

    /** Полное изменяемое состояние сети; все обращения происходят с серверного потока. */
    static final class NetworkRuntime {
        final NetworkId id;
        /** Сортировка задаёт порядок радаров и источников при обработке и публикации логов. */
        final Map<EndpointKey, RadarState> radars = new TreeMap<>(ENDPOINT_ORDER);
        /** Отчёты закрытого epoch скрыты от читателей до общей границы окна. */
        final Map<EndpointKey, CompletedScan> pending = new HashMap<>();
        /** Единственная запись на цель объединяет источники обнаружения и live-данные. */
        final Map<TargetRef, TargetEntry> targets = new HashMap<>();
        NetworkPolicy policy;
        /** Epoch, отчёты которого принимаются сейчас; он может быть новее опубликованного. */
        long currentEpoch;
        /** Последний epoch, который уже разрешено читать потребителям. */
        long publishedEpoch;
        long windowStartTick = -1;
        long nextBoundaryTick = -1;
        long revision;
        TargetRef selectedTarget;

        NetworkRuntime(NetworkId id) {
            this.id = id;
        }
    }

    /** Состояние одной регистрации радара и обратный индекс его обнаруженных целей. */
    static final class RadarState {
        final RegistrationToken token;
        /** Первый полный epoch, за который сеть вправе ждать отчёт от этой регистрации. */
        final long participatesFromEpoch;
        final Map<TargetRef, SourceLink> detections = new HashMap<>();

        RadarState(RegistrationToken token, long participatesFromEpoch) {
            this.token = token;
            this.participatesFromEpoch = participatesFromEpoch;
        }
    }

    /** Цель сети с обратным индексом радаров и live-значением опубликованного прохода. */
    static final class TargetEntry {
        final Map<EndpointKey, SourceLink> sources = new TreeMap<>(ENDPOINT_ORDER);
        LiveResult liveResult;
    }

    /** Связь «радар–цель»; её возраст вычисляется из опубликованного epoch. */
    static final class SourceLink {
        long lastSeenEpoch;
        long lastDetectedTick;
        Optional<ObservationClassification> classification;

        SourceLink(
                long lastSeenEpoch,
                long lastDetectedTick,
                Optional<ObservationClassification> classification
        ) {
            this.lastSeenEpoch = lastSeenEpoch;
            this.lastDetectedTick = lastDetectedTick;
            this.classification = classification;
        }
    }

    /** Неизменяемый полный отчёт, сохранённый до завершения общего окна сети. */
    static final class CompletedScan {
        final long epoch;
        final RegistrationToken token;
        final long submittedTick;
        final Map<TargetRef, RadarDetection> foundTargets;

        CompletedScan(
                long epoch,
                RegistrationToken token,
                long submittedTick,
                Map<TargetRef, RadarDetection> foundTargets
        ) {
            this.epoch = epoch;
            this.token = token;
            this.submittedTick = submittedTick;
            this.foundTargets = foundTargets;
        }
    }

    /** Пара радара и его runtime-состояния для формирования ответа читательского запроса. */
    record AccessibleRadar(EndpointKey endpoint, RadarState state) {
    }
}
