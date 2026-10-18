package com.electroradar.radar.network;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;

import com.electroradar.radar.RadarDiagnostics;

/**
 * Runtime-связь любого загруженного потребителя с радарной сетью.
 *
 * <p>Класс не зависит от BlockEntity и не сохраняет {@link NetworkId}: ID и настройки
 * остаются собственностью прибора. При открытии менеджер выдаёт токен именно этой загрузке;
 * токен хранится только в объекте связи до закрытия. Каждый запрос передаёт менеджеру всю
 * тройку endpoint/network/token, поэтому старый объект после повторной загрузки на том же
 * месте не сможет воспользоваться новой регистрацией. При смене сети владелец сначала
 * закрывает старую связь, затем создаёт новую с сохранённым им новым ID.
 *
 * <p>Связь работает только на основном серверном потоке, не опрашивает сеть самостоятельно
 * и не хранит списки целей. Ответ каждого запроса является отдельным неизменяемым значением.
 * {@link #close()} идемпотентен: повторный вызов безопасен, а старый токен не может снять
 * регистрацию, уже заменившую его.</p>
 */
public final class ConsumerConnection implements AutoCloseable {
    private final MinecraftServer server;
    private final RadarNetworkManager manager;
    private final EndpointKey endpoint;
    private final NetworkId networkId;
    private RegistrationToken token;

    /**
     * Открывает регистрацию потребителя или монитора на время жизни загруженного прибора.
     * Отсутствие радаров в сети не препятствует открытию: запросы вернут пустой снимок.
     *
     * @param server текущий сервер-владелец runtime-связи
     * @param endpoint идентичность прибора в его текущем положении и измерении
     * @param networkId ID сети, который прибор сам восстановил из своего сохранённого состояния
     * @param role разрешённая роль: {@link EndpointRole#CONSUMER} или {@link EndpointRole#MONITOR}
     * @throws IllegalArgumentException если передана роль радара
     * @throws IllegalStateException если вызов сделан не на серверном потоке или менеджер не активен
     */
    public ConsumerConnection(
            MinecraftServer server,
            EndpointKey endpoint,
            NetworkId networkId,
            EndpointRole role
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.networkId = Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(role, "role");
        if (!role.canQueryNetwork()) {
            throw new IllegalArgumentException("ConsumerConnection requires CONSUMER or MONITOR role");
        }
        this.manager = RadarNetworkManager.forServer(server);
        this.token = manager.register(endpoint, networkId, role);
    }

    /**
     * Возвращает полный набор доступных целей в выбранном потребителем формате.
     * Обход радаров, объединение идентичностей и свежесть выполняются ядром одним запросом.
     *
     * @param projection типизированное преобразование опубликованной цели в запись потребителя
     * @param <T> тип записи результата
     * @return снимок всех доступных целей вместе с опубликованными epoch и revision
     */
    public <T> TargetSnapshot<T> queryTargets(TargetProjection<T> projection) {
        Objects.requireNonNull(projection, "projection");
        checkServerThread();
        RegistrationToken current = currentToken();
        if (current == null) {
            logClosedAccess("query_targets");
            return TargetSnapshot.empty(0, 0);
        }
        return manager.queryTargets(endpoint, networkId, current, projection);
    }

    /**
     * Возвращает источники уже известной цели только среди радаров, доступных этому прибору.
     *
     * @param target полная идентичность проверяемой цели
     * @return опубликованный неизменяемый ответ; при закрытой связи источники отсутствуют
     */
    public TargetSources sourcesForTarget(TargetRef target) {
        Objects.requireNonNull(target, "target");
        checkServerThread();
        RegistrationToken current = currentToken();
        if (current == null) {
            logClosedAccess("sources_for_target");
            return new TargetSources(target, 0, 0, List.of(), Optional.empty());
        }
        return manager.sourcesForTarget(endpoint, networkId, current, target);
    }

    /** Возвращает ручной выбор сети, если регистрация всё ещё принадлежит этой загрузке. */
    public Optional<TargetRef> selectedTarget() {
        checkServerThread();
        RegistrationToken current = currentToken();
        if (current == null) {
            logClosedAccess("selected_target");
            return Optional.empty();
        }
        return manager.selectedTarget(endpoint, networkId, current);
    }

    /**
     * Меняет ручной выбор; менеджер разрешает это только актуальной регистрации MONITOR.
     * Передача {@code null} снимает выбор. Цель можно выбрать, только если доступный радар
     * обнаружил её в последнем опубликованном окне.
     *
     * @param target цель для выбора либо {@code null} для очистки
     * @return true, если операция разрешена и применена
     */
    public boolean selectTarget(TargetRef target) {
        checkServerThread();
        RegistrationToken current = currentToken();
        if (current == null) {
            logClosedAccess("select_target");
            return false;
        }
        return manager.selectTarget(endpoint, networkId, current, target);
    }

    /**
     * Проверяет, что endpoint, NetworkId и токен всё ещё совпадают с текущей регистрацией.
     * Одной локальной ссылки на токен недостаточно: новая загрузка могла заменить её на том
     * же endpoint. Менеджер делает одну read-only проверку endpoint-индекса без смены ревизии
     * и без создания или изменения состояния сети.
     *
     * @return true только для актуальной регистрации этого объекта связи
     */
    public boolean isOpen() {
        checkServerThread();
        RegistrationToken current = token;
        return current != null && manager.isCurrentConsumerRegistration(endpoint, networkId, current);
    }

    /**
     * Снимает регистрацию по её исходному токену и забывает токен независимо от результата.
     * Если endpoint уже зарегистрирован новой загрузкой, менеджер отклонит старую пару и
     * оставит новую регистрацию нетронутой. После остановки сервера состояние уже очищено.
     */
    @Override
    public void close() {
        checkServerThread();
        RegistrationToken previous = token;
        token = null;
        if (previous != null && !manager.isClosed()) {
            manager.unregister(endpoint, networkId, previous);
        }
    }

    private RegistrationToken currentToken() {
        return manager.isClosed() ? null : token;
    }

    /** Закрытая runtime-связь отличается от активной сети без целей в opt-in диагностике. */
    private void logClosedAccess(String operation) {
        if (RadarDiagnostics.queryDiagnosticsEnabled()) {
            RadarDiagnostics.log("consumer_access_rejected", networkId, endpoint, null, null,
                    "operation=" + operation + " reason=connection_closed");
        }
    }

    private void checkServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Consumer connections are server-thread only");
        }
    }
}
