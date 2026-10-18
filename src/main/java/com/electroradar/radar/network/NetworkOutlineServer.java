package com.electroradar.radar.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.electroradar.radar.RadarDiagnostics;

/**
 * Серверная выборка зарегистрированных участников и адресная отправка их позиций.
 * Здесь нет вычисления контуров: сервер только отбирает статические загруженные endpoint,
 * чтобы клиент мог безопасно построить геометрию из своего BlockState и данных BlockEntity.
 */
public final class NetworkOutlineServer {
    private static final Map<MinecraftServer, NetworkOutlineServer> SERVICES =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final int REFRESH_PERIOD_TICKS = 10;
    private static final double PLAYER_RADIUS = 64.0D;
    /** Запас нужен корню монитора, когда край его активного прямоугольника ближе самого корня. */
    private static final double ASSEMBLY_MARGIN = 8.0D;
    private static final double SEARCH_RADIUS_SQUARED =
            (PLAYER_RADIUS + ASSEMBLY_MARGIN) * (PLAYER_RADIUS + ASSEMBLY_MARGIN);
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingDouble(Candidate::distanceSquared)
            .thenComparingInt(candidate -> candidate.entry.position().getX())
            .thenComparingInt(candidate -> candidate.entry.position().getY())
            .thenComparingInt(candidate -> candidate.entry.position().getZ());

    private final MinecraftServer server;
    /** Состояние отправки привязано к UUID игрока, но номер не теряется при отпускании предмета. */
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private long tick;

    private NetworkOutlineServer(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    /** Создаёт отдельный кэш ответа для нового жизненного цикла сервера. */
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        NetworkOutlineServer previous;
        synchronized (SERVICES) {
            previous = SERVICES.put(server, new NetworkOutlineServer(server));
        }
        if (previous != null) {
            previous.players.clear();
        }
    }

    /** Проверяет руки каждый тик и пересчитывает список только при необходимости. */
    public static void onServerTick(ServerTickEvent.Post event) {
        NetworkOutlineServer service;
        synchronized (SERVICES) {
            service = SERVICES.get(event.getServer());
        }
        if (service != null) {
            service.tickPlayers();
        }
    }

    /** Удаляет все номера отправки и кэши после выхода сервера. */
    public static void onServerStopped(ServerStoppedEvent event) {
        NetworkOutlineServer service;
        synchronized (SERVICES) {
            service = SERVICES.remove(event.getServer());
        }
        if (service != null) {
            service.players.clear();
        }
    }

    /** Выход освобождает и кэш ответа, и счётчик соединения этого игрока. */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        if (server == null) {
            return;
        }
        NetworkOutlineServer service;
        synchronized (SERVICES) {
            service = SERVICES.get(server);
        }
        if (service != null) {
            service.players.remove(player.getUUID());
        }
    }

    /** Серверный тик владеет всей картой состояния и не передаёт её между потоками. */
    private void tickPlayers() {
        tick = Math.incrementExact(tick);
        RadarNetworkManager manager = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerState state = players.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
            boolean playerInstanceChanged = state.player != player;
            if (playerInstanceChanged) {
                // После возрождения UUID прежний, но клиентское состояние могло быть очищено.
                // Номер остаётся монотонным, а полный ответ уйдёт заново при первой активной руке.
                state.player = player;
                state.activeNetwork = null;
                state.dimension = null;
                state.lastResponse = null;
            }

            NetworkId heldNetwork = NetworkOutlineBinding.activeNetwork(player);
            if (heldNetwork == null) {
                // Клиент удаляет линии синхронно со сменой руки. Сервер освобождает крупный список,
                // но оставляет sequence, чтобы следующий ответ в этом же соединении был новее.
                state.activeNetwork = null;
                state.dimension = null;
                state.lastResponse = null;
                continue;
            }

            ServerLevel level = player.serverLevel();
            ResourceLocation dimension = level.dimension().location();
            boolean bindingChanged = playerInstanceChanged
                    || !heldNetwork.equals(state.activeNetwork)
                    || !dimension.equals(state.dimension);
            if (!bindingChanged && Math.floorMod(tick + player.getUUID().hashCode(),
                    REFRESH_PERIOD_TICKS) != 0) {
                continue;
            }

            if (manager == null) {
                manager = RadarNetworkManager.forServer(server);
            }
            CandidateSnapshot response = collectCandidates(manager, player, level, heldNetwork);
            state.activeNetwork = heldNetwork;
            state.dimension = dimension;
            sendIfChanged(player, state, response);
        }
    }

    /** Собирает только загруженные статические endpoint нужного измерения и сети. */
    private CandidateSnapshot collectCandidates(RadarNetworkManager manager, ServerPlayer player,
                                                ServerLevel level, NetworkId networkId) {
        // Держим только ближайший лимит: даже сеть с большим числом регистраций не создаст
        // временный список размером со всю сеть при каждом плановом обновлении.
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(
                NetworkOutlinePayload.MAX_ENTRIES, CANDIDATE_ORDER.reversed());
        int candidateCount = 0;
        for (EndpointKey endpoint : manager.registeredEndpoints(networkId)) {
            if (!(endpoint instanceof EndpointKey.Static fixed)
                    || !fixed.dimension().equals(level.dimension())) {
                // Локальную позицию Moving нельзя трактовать как мировую. Ключ остаётся в индексе,
                // но для него потребуется отдельный адаптер положения до включения в этот пакет.
                continue;
            }
            BlockPos position = fixed.position();
            if (!level.hasChunkAt(position)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null || blockEntity.isRemoved()) {
                continue;
            }
            ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            if (typeId == null) {
                continue;
            }
            double distanceSquared = player.distanceToSqr(Vec3.atCenterOf(position));
            if (distanceSquared <= SEARCH_RADIUS_SQUARED) {
                Candidate candidate = new Candidate(
                        new NetworkOutlinePayload.Entry(position, typeId), distanceSquared);
                candidateCount = Math.incrementExact(candidateCount);
                if (nearest.size() < NetworkOutlinePayload.MAX_ENTRIES) {
                    nearest.add(candidate);
                } else if (CANDIDATE_ORDER.compare(candidate, nearest.peek()) < 0) {
                    nearest.poll();
                    nearest.add(candidate);
                }
            }
        }

        ArrayList<Candidate> ordered = new ArrayList<>(nearest);
        ordered.sort(CANDIDATE_ORDER);
        boolean truncated = candidateCount > NetworkOutlinePayload.MAX_ENTRIES;
        ArrayList<NetworkOutlinePayload.Entry> entries = new ArrayList<>(ordered.size());
        for (Candidate candidate : ordered) entries.add(candidate.entry);
        return new CandidateSnapshot(level.dimension().location(), networkId, entries, truncated,
                candidateCount);
    }

    /** Не отправляет один и тот же полный список повторно на плановом refresh. */
    private void sendIfChanged(ServerPlayer player, PlayerState state, CandidateSnapshot response) {
        if (state.lastResponse != null && response.samePayload(state.lastResponse)) {
            return;
        }
        long sequence = Math.incrementExact(state.sequence);
        NetworkOutlinePayload payload = new NetworkOutlinePayload(
                response.dimension, response.networkId, sequence, response.entries, response.truncated);
        PacketDistributor.sendToPlayer(player, payload);
        state.sequence = sequence;
        state.lastResponse = response;

        if (response.truncated) {
            RadarDiagnostics.log("network_outline_truncated", response.networkId, null, null, tick,
                    "player=" + player.getGameProfile().getName() + " candidates=" + response.candidateCount
                            + " sent=" + response.entries.size() + " limit=" + NetworkOutlinePayload.MAX_ENTRIES);
        }
        RadarDiagnostics.log("network_outline_sent", response.networkId, null, null, tick,
                "player=" + player.getGameProfile().getName() + " sequence=" + sequence
                        + " entries=" + response.entries.size() + " truncated=" + response.truncated);
    }

    private record Candidate(NetworkOutlinePayload.Entry entry, double distanceSquared) {
    }

    /** Ровно те поля, от которых зависит payload; не содержит BlockEntity или внутренних регистраций. */
    private record CandidateSnapshot(ResourceLocation dimension, NetworkId networkId,
                                     List<NetworkOutlinePayload.Entry> entries, boolean truncated,
                                     int candidateCount) {
        private CandidateSnapshot {
            entries = List.copyOf(entries);
        }

        /** Количество кандидатов нужно для диагностики, но не входит в компактный wire-ответ. */
        private boolean samePayload(CandidateSnapshot other) {
            return dimension.equals(other.dimension) && networkId.equals(other.networkId)
                    && entries.equals(other.entries) && truncated == other.truncated;
        }
    }

    private static final class PlayerState {
        private ServerPlayer player;
        private NetworkId activeNetwork;
        private ResourceLocation dimension;
        private CandidateSnapshot lastResponse;
        private long sequence;
    }
}
