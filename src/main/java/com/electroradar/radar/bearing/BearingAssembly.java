package com.electroradar.radar.bearing;

import java.util.UUID;
import com.electroradar.radar.simple.RadarRotation;

/** Commands at the world/Create boundary. An unresolved UUID remains owned until restored. */
public final class BearingAssembly {
    public enum Result { ASSEMBLED, DISASSEMBLED, INVALID_SECTIONS, CAPTURE_FAILED, RESTORE_DEFERRED }
    public interface Port {
        int discoverSections();
        /** Atomically captures the sections, or restores all captured blocks before returning null. */
        UUID capture(int count, RadarRotation rotation);
        boolean available(UUID entity);
        /** Returns true only after every section is restored and the entity retired. */
        boolean restore(UUID entity);
    }
    private UUID entity;
    private int count;
    private RadarRotation rotation = new RadarRotation(0, 0);

    public Result toggle(Port world, long gameTime) {
        if (entity != null) {
            if (!world.restore(entity)) return Result.RESTORE_DEFERRED;
            entity = null;
            count = 0;
            return Result.DISASSEMBLED;
        }
        int discovered = world.discoverSections();
        if (discovered < 1 || discovered > 5) return Result.INVALID_SECTIONS;
        RadarRotation phase = new RadarRotation(gameTime, 0);
        UUID captured = world.capture(discovered, phase);
        if (captured == null) return Result.CAPTURE_FAILED;
        entity = captured;
        count = discovered;
        rotation = phase;
        return Result.ASSEMBLED;
    }
    public record Saved(UUID entityId, int antennaCount, RadarRotation rotation) {}
    public Saved save() { return new Saved(entity, count, rotation); }
    public void load(Saved saved) {
        entity = saved.entityId();
        count = entity == null ? 0 : Math.clamp(saved.antennaCount(), 1, 5);
        rotation = saved.rotation();
    }
    public boolean scanningReady(Port world) { return entity != null && world.available(entity); }
    public boolean active() { return entity != null; }
    public boolean owns(UUID candidate) { return entity != null && entity.equals(candidate); }
    public UUID entityId() { return entity; }
    public int antennaCount() { return count; }
    public RadarRotation rotation() { return rotation; }
}
