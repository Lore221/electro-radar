package com.electroradar.client.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.network.MonitorSnapshotPayload;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Клиентский кэш последнего снимка и сборщик его сетевых частей.
 *
 * <p>Кэш не хранит историю: каждая новая полная версия заменяет прежнюю, исчезнувшие цели
 * удаляются сразу. Для помнимой цели допускается только последняя подтверждённая клиентом
 * позиция; серверные координаты помнимой сущности никогда не используются. При смене уровня,
 * корня или UUID загрузки данные старого экрана не подходят новому.</p>
 */
public final class MonitorClientState {
    /** Запасная длительность для старой помнимой точки, если профиль радара уже исчез. */
    private static final long FALLBACK_REMEMBERED_FADE_TICKS = 40L;
    private static final Map<MonitorDisplayKey, View> VIEWS = new HashMap<>();
    private static final Map<PartKey, PendingParts> PENDING = new HashMap<>();
    private static final Map<MonitorDisplayKey, Long> PENDING_SEQUENCES = new HashMap<>();
    private static ClientLevel currentLevel;
    /** Локальное время отрисовки не зависит от коррекции игрового времени пакетами сервера. */
    private static long clientTickTime;
    private static double lastRenderTime;

    private MonitorClientState() {
    }

    /** Клиентская запись с последней подтверждённой позицией помнимой метки. */
    public record DisplayTarget(TargetRef target, boolean detectedThisWindow, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                boolean rememberedPoint, boolean previouslyFresh,
                                double rememberedFadeStartClientTick,
                                float rememberedFadeStartOpacity,
                                long rememberedFadeDurationTicks) {
        /**
         * Помнимая точка линейно гаснет от сохранённой яркости в момент перехода до нуля.
         * Повторные снимки, пока цель остаётся помнимой, сохраняют исходную фазу и не запускают
         * затухание заново, поэтому смена окна не вызывает скачок прозрачности.
         */
        public float rememberedOpacity(double clientRenderTime) {
            if (!rememberedPoint) return 1.0F;
            if (rememberedFadeDurationTicks <= 0L) return 0.0F;
            double age = Math.max(0.0D, clientRenderTime - rememberedFadeStartClientTick);
            double fade = Math.clamp(1.0D - age / rememberedFadeDurationTicks, 0.0D, 1.0D);
            return (float) (rememberedFadeStartOpacity * fade);
        }
    }

    /** Снимок для рендереров и GUI с уже безопасно сохранёнными точками помнимых целей. */
    public record View(MonitorDisplayKey key, MonitorSnapshotData snapshot,
                       List<DisplayTarget> targets,
                       List<MonitorSectorAnimationState.ProfileFrame> visualFrames) {
        public View {
            targets = List.copyOf(targets);
            visualFrames = List.copyOf(visualFrames);
        }

        /** Одна формула прозрачности для GUI и экрана блока. */
        public float targetOpacity(DisplayTarget target, double renderTime) {
            if (target.rememberedPoint()) return target.rememberedOpacity(renderTime);
            if (!target.detectedThisWindow() || !target.hasPosition()) return 1.0F;
            return MonitorSectorAnimationState.targetOpacity(key, target.target(),
                    renderTime, target.previouslyFresh());
        }
    }

    /** Собирает payload части и применяет снимок только после получения всей серии. */
    public static void accept(MonitorSnapshotPayload part) {
        ClientLevel level = currentLevel();
        if (level == null || !level.dimension().location().equals(part.dimension())
                || !matchesLoadedRoot(level, part.rootPosition(), part.displayId(), part.loadId())) {
            return;
        }
        MonitorDisplayKey key = new MonitorDisplayKey(part.dimension(), part.rootPosition(),
                part.displayId(), part.loadId());
        // Порядок задаёт только sequence корня (см. MonitorSnapshotData): epoch сети может
        // начаться заново с нуля, и снимок с меньшим epoch бывает новее показанного.
        View current = VIEWS.get(key);
        if (current != null && part.sequence() < current.snapshot().sequence()) {
            return;
        }
        Long pendingSequence = PENDING_SEQUENCES.get(key);
        if (pendingSequence != null && part.sequence() < pendingSequence) return;
        if (pendingSequence == null || part.sequence() > pendingSequence) {
            PENDING.keySet().removeIf(existing -> existing.display.equals(key));
            PENDING_SEQUENCES.put(key, part.sequence());
        }
        PartKey partKey = new PartKey(key, part.sequence(), part.partCount());
        PendingParts pending = PENDING.computeIfAbsent(partKey,
                ignored -> new PendingParts(part.partCount(), clientTickTime));
        if (pending.parts[part.partIndex()] == null) {
            pending.parts[part.partIndex()] = part.targets();
            pending.profileParts[part.partIndex()] = part.visualProfiles();
            pending.received++;
        }
        if (part.partIndex() == 0) {
            pending.visualWindowPeriodTicks = part.visualWindowPeriodTicks();
            pending.visualWindowRemainingTicks = part.visualWindowRemainingTicks();
            pending.hasVisualMetadata = true;
        }
        pending.metadata = part;
        if (pending.received != pending.parts.length) return;

        ArrayList<MonitorTargetData> allTargets = new ArrayList<>();
        for (List<MonitorTargetData> targets : pending.parts) allTargets.addAll(targets);
        ArrayList<RadarVisualProfile> allProfiles = new ArrayList<>();
        for (List<RadarVisualProfile> profiles : pending.profileParts) allProfiles.addAll(profiles);
        MonitorSnapshotData snapshot;
        try {
            if (!pending.hasVisualMetadata) {
                PENDING.remove(partKey);
                return;
            }
            snapshot = pending.metadata.asSnapshot(allTargets, allProfiles,
                    pending.visualWindowPeriodTicks, pending.visualWindowRemainingTicks);
        } catch (IllegalArgumentException malformed) {
            PENDING.remove(partKey);
            return;
        }
        PENDING.remove(partKey);
        apply(key, snapshot);
        PENDING_SEQUENCES.remove(key);
    }

    /** Возвращает актуальный клиентский вид, если корень всё ещё загружен и совпадает по идентичности. */
    public static View get(MonitorDisplayKey key) {
        return get(key, 0.0F);
    }

    /** Возвращает вид с фазой того же клиентского визуального прохода для GUI и BER. */
    public static View get(MonitorDisplayKey key, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = currentLevel();
        if (level == null || !level.dimension().location().equals(key.dimension())
                || !matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId())) {
            VIEWS.remove(key);
            MonitorSectorAnimationState.remove(key);
            if (minecraft.screen instanceof RadarMonitorScreen screen && screen.isFor(key)) {
                minecraft.setScreen(null);
            }
            return null;
        }
        View stored = VIEWS.get(key);
        if (stored == null) return null;
        return new View(stored.key(), stored.snapshot(), stored.targets(),
                MonitorSectorAnimationState.activeVisuals(key, partialTick));
    }

    /** Очищает клиентское состояние при смене мира или физической сборки. */
    public static void clear() {
        VIEWS.clear();
        PENDING.clear();
        PENDING_SEQUENCES.clear();
        MonitorSectorAnimationState.clear();
        currentLevel = null;
        clientTickTime = 0L;
        lastRenderTime = 0.0D;
    }

    /** Удаляет состояния, чей корневой блок оказался загружен как другой блок или другая загрузка. */
    public static void pruneLoadedRoots() {
        ClientLevel level = currentLevel();
        if (level == null) return;
        List<MonitorDisplayKey> invalidViews = VIEWS.keySet().stream()
                .filter(key -> level.hasChunkAt(key.rootPosition())
                        && !matchesLoadedRoot(level, key.rootPosition(), key.displayId(), key.loadId()))
                .toList();
        for (MonitorDisplayKey key : invalidViews) {
            VIEWS.remove(key);
            MonitorSectorAnimationState.remove(key);
        }
        PENDING.entrySet().removeIf(entry -> clientTickTime - entry.getValue().createdAtClientTick > 200L
                || level.hasChunkAt(entry.getKey().display.rootPosition())
                && !matchesLoadedRoot(level, entry.getKey().display.rootPosition(),
                entry.getKey().display.displayId(), entry.getKey().display.loadId()));
        PENDING_SEQUENCES.keySet().removeIf(key -> PENDING.keySet().stream()
                .noneMatch(part -> part.display.equals(key)));
    }

    private static void apply(MonitorDisplayKey key, MonitorSnapshotData snapshot) {
        View previous = VIEWS.get(key);
        Map<TargetRef, MonitorTargetData> previousTargets = new HashMap<>();
        Map<TargetRef, DisplayTarget> previousDisplayTargets = new HashMap<>();
        if (previous != null) {
            for (DisplayTarget target : previous.targets()) {
                previousDisplayTargets.put(target.target(), target);
                if (target.hasPosition()) {
                    previousTargets.put(target.target(), new MonitorTargetData(target.target(), true, true,
                            target.x(), target.y(), target.z(), target.entityTypeId()));
                }
            }
        }
        ArrayList<DisplayTarget> displayTargets = new ArrayList<>(snapshot.targets().size());
        long receivedAt = clientTickTime;
        for (MonitorTargetData target : snapshot.targets()) {
            if (target.hasPosition()) {
                DisplayTarget oldDisplay = previousDisplayTargets.get(target.target());
                boolean previouslyFresh = oldDisplay != null && oldDisplay.hasPosition()
                        && oldDisplay.detectedThisWindow();
                displayTargets.add(new DisplayTarget(target.target(), target.detectedThisWindow(), true,
                        target.x(), target.y(), target.z(), target.entityTypeId(), false,
                        previouslyFresh, 0.0D, 1.0F, 0L));
                continue;
            }
            MonitorTargetData old = previousTargets.get(target.target());
            if (old != null && old.hasPosition()) {
                DisplayTarget oldDisplay = previousDisplayTargets.get(target.target());
                double fadeStart;
                float fadeStartOpacity;
                long fadeDuration = snapshot.visualWindowPeriodTicks();
                if (oldDisplay != null && oldDisplay.rememberedPoint()) {
                    // Переход уже произошёл: не переносим ни начало, ни яркость на новый снимок.
                    fadeStart = oldDisplay.rememberedFadeStartClientTick();
                    fadeStartOpacity = oldDisplay.rememberedFadeStartOpacity();
                    fadeDuration = oldDisplay.rememberedFadeDurationTicks();
                } else {
                    // Сохраняем именно ту прозрачность, которую видели перед сменой свежести.
                    // Это одинаково работает после FULL_FLASH, SECTOR_SWEEP и вне профилей.
                    fadeStart = receivedAt;
                    fadeStartOpacity = previous == null || oldDisplay == null
                            ? 1.0F : previous.targetOpacity(oldDisplay, receivedAt);
                }
                if (fadeDuration <= 0L && previous != null) {
                    fadeDuration = previous.snapshot().visualWindowPeriodTicks();
                }
                if (fadeDuration <= 0L) fadeDuration = FALLBACK_REMEMBERED_FADE_TICKS;
                displayTargets.add(new DisplayTarget(target.target(), false, true,
                        old.x(), old.y(), old.z(), old.entityTypeId(), true, false,
                        fadeStart, fadeStartOpacity, fadeDuration));
            } else {
                displayTargets.add(new DisplayTarget(target.target(), target.detectedThisWindow(), false,
                        0, 0, 0, "", false, false, 0.0D, 1.0F, 0L));
            }
        }
        long receivedAtClientTick = receivedAt;
        MonitorSectorAnimationState.accept(key, previous == null ? null : previous.snapshot(),
                snapshot, receivedAtClientTick);
        VIEWS.put(key, new View(key, snapshot, displayTargets, List.of()));
        if (previous == null || previous.snapshot().publishedEpoch() != snapshot.publishedEpoch()) {
            long newTargets = displayTargets.stream().filter(target -> target.detectedThisWindow()
                    && target.hasPosition() && !target.previouslyFresh()).count();
            RadarDiagnostics.log("monitor_visual_targets_received", null, null,
                    snapshot.publishedEpoch(), receivedAtClientTick,
                    "clock=client displayId=" + snapshot.displayId()
                            + " fresh=" + displayTargets.stream()
                            .filter(DisplayTarget::detectedThisWindow).count()
                            + " newlyPositioned=" + newTargets
                            + " remembered=" + displayTargets.stream()
                            .filter(DisplayTarget::rememberedPoint).count()
                            + " profiles=" + snapshot.visualProfiles().size());
        }
    }

    private static ClientLevel currentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != currentLevel) {
            VIEWS.clear();
            PENDING.clear();
            PENDING_SEQUENCES.clear();
            MonitorSectorAnimationState.clear();
            currentLevel = level;
            clientTickTime = 0L;
            lastRenderTime = 0.0D;
        }
        return level;
    }

    /** Вызывается один раз после клиентского тика; часы не откатываются при синхронизации мира. */
    static void advanceClientClock() {
        ClientLevel level = currentLevel();
        if (level != null && !Minecraft.getInstance().isPaused()) clientTickTime++;
    }

    /** Клиентский тик для редких диагностических записей очистки общего состояния. */
    static long clientTickTimeForDiagnostics() {
        return clientTickTime;
    }

    /** Общая фаза GUI и BER; даже непоследовательные partialTick не возвращают сектор назад. */
    public static double renderTime(float partialTick) {
        currentLevel();
        lastRenderTime = Math.max(lastRenderTime,
                clientTickTime + Math.clamp(partialTick, 0.0F, 1.0F));
        return lastRenderTime;
    }

    static boolean matchesLoadedRoot(ClientLevel level, net.minecraft.core.BlockPos position,
                                     UUID displayId, UUID loadId) {
        if (!level.hasChunkAt(position)) return false;
        BlockEntity blockEntity = level.getBlockEntity(position);
        return blockEntity instanceof RadarDisplayBlockEntity root
                && root.isRoot()
                && displayId.equals(root.displayId())
                && loadId.equals(root.loadId());
    }

    private record PartKey(MonitorDisplayKey display, long sequence, int partCount) {
    }

    private static final class PendingParts {
        private final List<MonitorTargetData>[] parts;
        private final List<RadarVisualProfile>[] profileParts;
        private final long createdAtClientTick;
        private int received;
        private MonitorSnapshotPayload metadata;
        private long visualWindowPeriodTicks;
        private long visualWindowRemainingTicks;
        private boolean hasVisualMetadata;

        @SuppressWarnings("unchecked")
        private PendingParts(int count, long createdAtClientTick) {
            if (count < 1 || count > 65_536) throw new IllegalArgumentException("Invalid part count");
            parts = (List<MonitorTargetData>[]) new List<?>[count];
            profileParts = (List<RadarVisualProfile>[]) new List<?>[count];
            this.createdAtClientTick = createdAtClientTick;
        }
    }
}
