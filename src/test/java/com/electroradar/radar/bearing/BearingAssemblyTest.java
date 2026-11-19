package com.electroradar.radar.bearing;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BearingAssemblyTest {
    @Test void rightClickCapturesOneToFiveSectionsAndSecondClickRestoresThem() {
        World world = new World();
        BearingAssembly bearing = new BearingAssembly();
        assertEquals(BearingAssembly.Result.ASSEMBLED, bearing.toggle(world, 100));
        assertEquals(3, bearing.antennaCount());
        assertTrue(bearing.scanningReady(world));
        assertEquals(BearingAssembly.Result.DISASSEMBLED, bearing.toggle(world, 120));
        assertEquals(3, world.sections);
        assertFalse(bearing.scanningReady(world));
    }
    @Test void reloadRetainsUuidAndPhaseAndWaitsForEntityLoading() {
        World world = new World();
        BearingAssembly bearing = new BearingAssembly();
        bearing.toggle(world, 100);
        var saved = bearing.save();
        BearingAssembly loaded = new BearingAssembly();
        loaded.load(saved);
        UUID original = world.entity;
        world.entity = null;
        assertEquals(BearingAssembly.Result.RESTORE_DEFERRED, loaded.toggle(world, 101));
        assertFalse(loaded.scanningReady(world));
        assertTrue(loaded.owns(original));
        assertFalse(loaded.owns(UUID.randomUUID()));
        assertEquals(120.0, loaded.rotation().angleDegrees(125), 0.0001);
        world.entity = original;
        assertTrue(loaded.scanningReady(world));
        assertEquals(BearingAssembly.Result.DISASSEMBLED, loaded.toggle(world, 126));
    }
    private static class World implements BearingAssembly.Port {
        int sections = 3;
        UUID entity;
        @Override public int discoverSections() { return sections; }
        @Override public UUID capture(int count, com.electroradar.radar.simple.RadarRotation rotation) {
            sections = 0; return entity = UUID.randomUUID();
        }
        @Override public boolean available(UUID id) { return id.equals(entity); }
        @Override public boolean restore(UUID id) {
            if (!available(id)) return false;
            sections = 3; entity = null; return true;
        }
    }
}
