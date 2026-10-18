package com.electroradar.client.networkoutline;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkOutlineBinding;
import com.electroradar.radar.network.NetworkOutlinePayload;

/**
 * Клиентское состояние одного текущего ответа сервера и его ключей Create Outliner.
 * Все методы этого класса вызываются из client tick либо из payload-handler после enqueueWork,
 * поэтому карта не синхронизируется отдельной блокировкой и не пересекает сетевой поток.
 */
public final class NetworkOutlineClientState {
    private static final double VIEW_RADIUS_SQUARED = 64.0D * 64.0D;
    private static final int PULSE_PERIOD_TICKS = 16;
    private static final int LOW_COLOR = 0x58BDEB;
    private static final int HIGH_COLOR = 0x8ADFFF;

    /** Локальная mutable-копия полученного списка, не связанная с буфером payload. */
    private static final Map<OutlineKey, NetworkOutlinePayload.Entry> MEMBERS = new HashMap<>();
    private static ClientLevel currentLevel;
    private static ClientPacketListener currentConnection;
    private static NetworkId activeNetwork;
    private static ResourceLocation activeDimension;
    /** Sequence живёт дольше привязки предмета, иначе старый ответ мог бы затереть новый. */
    private static long lastAcceptedSequence = -1L;
    private static long clientTicks;

    private NetworkOutlineClientState() {
    }

    /**
     * Вызывается один раз за клиентский тик. Смена рук обрабатывается до проверки чанков,
     * поэтому уже созданные контуры удаляются в тот же тик, даже если сервер ещё не ответил.
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        synchronizeWorld(level, minecraft.getConnection());
        clientTicks = Math.incrementExact(clientTicks);
        Player player = minecraft.player;
        if (level == null || player == null) {
            clearBinding();
            return;
        }

        NetworkId heldNetwork = NetworkOutlineBinding.activeNetwork(player);
        if (heldNetwork == null) {
            clearBinding();
            return;
        }
        adoptBinding(heldNetwork, level.dimension().location());
        refreshOutlines(level, player);
    }

    /**
     * Применяет только полный ответ для текущих уровня и предмета в руке.
     * Новая версия удаляет ключи отсутствующих endpoint до дальнейшей отрисовки; проверка
     * sequence дополнительно защищает клиента от запоздавшего ответа при быстрых сменах сети.
     */
    public static void accept(NetworkOutlinePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        synchronizeWorld(level, minecraft.getConnection());
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }

        NetworkId heldNetwork = NetworkOutlineBinding.activeNetwork(player);
        if (heldNetwork == null) {
            clearBinding();
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        if (!dimension.equals(payload.dimension()) || !heldNetwork.equals(payload.networkId())) {
            return;
        }
        adoptBinding(heldNetwork, dimension);
        if (payload.sequence() <= lastAcceptedSequence) {
            return;
        }

        Map<OutlineKey, NetworkOutlinePayload.Entry> next = new HashMap<>();
        for (NetworkOutlinePayload.Entry entry : payload.entries()) {
            OutlineKey key = new OutlineKey(dimension, heldNetwork, entry.position());
            next.put(key, entry);
        }
        removeMissingOutlines(next.keySet());
        MEMBERS.clear();
        MEMBERS.putAll(next);
        lastAcceptedSequence = payload.sequence();
    }

    /** При смене мира или соединения гарантированно снимает все ключи и начинает sequence заново. */
    private static void synchronizeWorld(ClientLevel level, ClientPacketListener connection) {
        if (level == currentLevel && connection == currentConnection) {
            return;
        }
        clearAllOutlines();
        MEMBERS.clear();
        activeNetwork = null;
        activeDimension = null;
        lastAcceptedSequence = -1L;
        clientTicks = 0L;
        currentLevel = level;
        currentConnection = connection;
    }

    /** Смена сети/измерения отменяет старые ключи раньше получения нового серверного ответа. */
    private static void adoptBinding(NetworkId network, ResourceLocation dimension) {
        if (network.equals(activeNetwork) && dimension.equals(activeDimension)) {
            return;
        }
        clearAllOutlines();
        MEMBERS.clear();
        activeNetwork = network;
        activeDimension = dimension;
    }

    /** Потеря подходящего предмета снимает отрисовку, сохраняя sequence до конца соединения. */
    private static void clearBinding() {
        if (activeNetwork != null || !MEMBERS.isEmpty()) {
            clearAllOutlines();
            MEMBERS.clear();
        }
        activeNetwork = null;
        activeDimension = null;
    }

    /** Пересоздаёт или обновляет короткоживущие AABB только для локально проверенных участников. */
    private static void refreshOutlines(ClientLevel level, Player player) {
        Outliner outliner = Outliner.getInstance();
        int color = pulseColor(clientTicks);
        for (Map.Entry<OutlineKey, NetworkOutlinePayload.Entry> member : MEMBERS.entrySet()) {
            OutlineKey key = member.getKey();
            NetworkOutlinePayload.Entry entry = member.getValue();
            BlockPos position = entry.position();
            if (!level.hasChunkAt(position)) {
                // hasChunkAt не запрашивает загрузку. Контур временно снимается, но запись ждёт
                // планового ответа сервера, который удалит уже выгруженный endpoint из списка.
                outliner.remove(key);
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null || blockEntity.isRemoved()
                    || !entry.blockEntityTypeId().equals(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()))) {
                outliner.remove(key);
                continue;
            }
            AABB bounds = NetworkOutlineGeometry.bounds(level, position, blockEntity);
            if (bounds == null || distanceSquaredTo(bounds, player.getX(), player.getY(), player.getZ())
                    > VIEW_RADIUS_SQUARED) {
                outliner.remove(key);
                continue;
            }

            outliner.showAABB(key, bounds, 2)
                    .lineWidth(0.03125F)
                    .disableLineNormals()
                    .colored(color);
        }
    }

    /** Удаляет ровно те ключи, которых больше нет в новом серверном составе. */
    private static void removeMissingOutlines(Set<OutlineKey> nextKeys) {
        Outliner outliner = Outliner.getInstance();
        for (OutlineKey previous : MEMBERS.keySet()) {
            if (!nextKeys.contains(previous)) {
                outliner.remove(previous);
            }
        }
    }

    /** Outliner хранит геометрию до TTL, поэтому очищение должно вызвать remove явно. */
    private static void clearAllOutlines() {
        Outliner outliner = Outliner.getInstance();
        for (OutlineKey key : MEMBERS.keySet()) {
            outliner.remove(key);
        }
    }

    /** Расстояние от позиции игрока до ближайшей точки AABB, а не до корня сборки. */
    private static double distanceSquaredTo(AABB bounds, double x, double y, double z) {
        double dx = x < bounds.minX ? bounds.minX - x : Math.max(0.0D, x - bounds.maxX);
        double dy = y < bounds.minY ? bounds.minY - y : Math.max(0.0D, y - bounds.maxY);
        double dz = z < bounds.minZ ? bounds.minZ - z : Math.max(0.0D, z - bounds.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Треугольная 16-тиковая интерполяция между заданными голубыми цветами спецификации. */
    private static int pulseColor(long ticks) {
        int phase = (int) Math.floorMod(ticks, PULSE_PERIOD_TICKS);
        float amount = phase <= PULSE_PERIOD_TICKS / 2
                ? phase / (PULSE_PERIOD_TICKS / 2.0F)
                : (PULSE_PERIOD_TICKS - phase) / (PULSE_PERIOD_TICKS / 2.0F);
        int red = interpolate((LOW_COLOR >> 16) & 0xff, (HIGH_COLOR >> 16) & 0xff, amount);
        int green = interpolate((LOW_COLOR >> 8) & 0xff, (HIGH_COLOR >> 8) & 0xff, amount);
        int blue = interpolate(LOW_COLOR & 0xff, HIGH_COLOR & 0xff, amount);
        return (red << 16) | (green << 8) | blue;
    }

    private static int interpolate(int from, int to, float amount) {
        return Math.round(from + (to - from) * amount);
    }

    /** Стабильная идентичность Outliner для двух сетей и нескольких приборов на одной позиции. */
    private record OutlineKey(ResourceLocation dimension, NetworkId networkId, BlockPos position) {
        private OutlineKey {
            position = position.immutable();
        }
    }
}
