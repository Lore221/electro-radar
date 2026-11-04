package com.electroradar.radar.alarm;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import com.electroradar.radar.network.LiveResult;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.TargetRef;

/** Адресный журнал одного Alarm. Ничего не ищет в мире и не меняет игровые результаты. */
final class ShellAlarmDiagnostics {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("electroradar.diagnostics.shell_alarm", "false"));
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DETAILS_PER_TICK = 32;
    private final DiagnosticLogGate<Object> gate = ENABLED ? new DiagnosticLogGate<>(1_024, 20) : null;
    private long tick;
    private BlockPos alarm;
    private Object dimension;
    private NetworkId network;
    private ProtectionZone zone;
    private int details, suppressed;

    void begin(ServerLevel level, BlockPos alarm, NetworkId network, ProtectionZone zone) {
        if (!ENABLED) return;
        tick = level.getGameTime();
        this.alarm = alarm;
        dimension = level.dimension().location();
        this.network = network;
        this.zone = zone;
        details = suppressed = 0;
    }

    void target(TargetRef ref, LiveResult published, Entity entity, Object reason) {
        if (!ENABLED) return;
        var key = new Key(ref, "target");
        String signature = reason + ":" + published.availability() + ":" + published.observing();
        if (!allow(key, signature)) return;
        Object type = entity == null ? published.entityType().map(BuiltInRegistries.ENTITY_TYPE::getKey).orElse(null)
                : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        LOGGER.info("[ElectroRadar/ShellAlarmDebug] event=target tick={} alarm={} dimension={} network={} target={} type={} class={} reason={} availability={} observing={} sampleTick={} samplePosition={} position={} velocity={} age={}",
                tick, alarm, dimension, network, ref, type, entity == null ? "-" : entity.getClass().getName(), reason,
                published.availability(), published.observing(), published.sampledTick(), published.position().orElse(null),
                entity == null ? null : entity.position(), entity == null ? null : entity.getDeltaMovement(),
                entity == null ? -1 : entity.tickCount);
    }

    void prediction(ShellThreatTracker.Decision decision) {
        if (!ENABLED) return;
        var sample = decision.sample();
        var prediction = decision.prediction();
        String signature = decision.action() + ":" + decision.result() + ":" + prediction;
        if (!allow(new Key(sample.id(), "forecast"), signature)) return;
        var shell = sample.shell();
        LOGGER.info("[ElectroRadar/ShellAlarmDebug] event=forecast tick={} alarm={} dimension={} network={} target={} action={} result={} firstDangerTick={} lastDangerTick={} complete={} elapsed={} eta={} position={} velocity={} drag={} gravity={} reach={} p10={} p40={} p80={}",
                tick, alarm, dimension, network, sample.id(), decision.action(), decision.result(),
                prediction == null ? -1 : prediction.firstDangerTick(), prediction == null ? -1 : prediction.lastDangerTick(),
                prediction != null && prediction.complete(), decision.elapsed(),
                prediction == null || prediction.firstDangerTick() < 0 ? -1 : Math.max(0, prediction.firstDangerTick() - decision.elapsed()),
                shell.position(), shell.velocity(), shell.motion().drag(), shell.motion().gravity(), sample.reach(),
                positionAfter(sample, 10), positionAfter(sample, 40), positionAfter(sample, 80));
    }

    void summary(String status, int targets, int available, int resolved, int big, int supported,
                 int current, int dangerous, int unsupported, int incomplete, int newPredictions) {
        if (!ENABLED) return;
        var summary = new Summary(status, zone, network, targets, available, resolved, big, supported,
                current, dangerous, unsupported, incomplete, newPredictions, suppressed);
        if (!gate.due("summary", summary, tick)) return;
        gate.sent("summary", summary, tick);
        LOGGER.info("[ElectroRadar/ShellAlarmDebug] event=scan tick={} alarm={} dimension={} network={} status={} zone={} networkTargets={} available={} resolved={} bigShells={} supported={} current={} dangerous={} unsupported={} incomplete={} newPredictions={} detailsSuppressed={}",
                tick, alarm, dimension, network, status, zone, targets, available, resolved, big, supported,
                current, dangerous, unsupported, incomplete, newPredictions, suppressed);
    }

    private boolean allow(Object key, Object signature) {
        if (!gate.due(key, signature, tick)) return false;
        if (details >= DETAILS_PER_TICK) { suppressed++; return false; }
        details++;
        gate.sent(key, signature, tick);
        return true;
    }

    private static Object positionAfter(ShellThreatTracker.Observation sample, int ticks) {
        try { return sample.shell().positionAfter(ticks); }
        catch (IllegalArgumentException overflow) { return "unrepresentable"; }
    }

    private record Key(Object target, String event) { }
    private record Summary(String status, ProtectionZone zone, NetworkId network, int targets, int available,
                           int resolved, int big, int supported, int current, int dangerous, int unsupported,
                           int incomplete, int newPredictions, int suppressed) { }
}
