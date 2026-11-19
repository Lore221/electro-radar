package com.electroradar.radar.bearing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fake world is a system boundary: the production restoration command drives it unchanged. */
class BearingRestorationTest {
    @Test void occupiedDestinationEvenWithIdenticalSectionDefersWithoutChangingWorld() {
        World world = new World();
        world.cells.put(1, "antenna");
        var checkpoint = new HashSet<Integer>();
        assertFalse(BearingRestoration.restore(Map.of(0, "antenna", 1, "antenna"), checkpoint, world));
        assertEquals(Map.of(1, "antenna"), world.cells);
        assertTrue(checkpoint.isEmpty());
    }
    @Test void interruptedRestorationResumesFromItsSavedCheckpointWithoutDuplicatingSections() {
        World world = new World();
        world.refuse = 1;
        var source = new java.util.LinkedHashMap<Integer, String>();
        source.put(0, "antenna"); source.put(1, "antenna"); source.put(2, "antenna");
        var checkpoint = new HashSet<Integer>();
        assertFalse(BearingRestoration.restore(source, checkpoint, world));
        assertEquals(Map.of(0, "antenna"), world.cells);
        var reloadedCheckpoint = new HashSet<>(checkpoint);
        world.refuse = -1;
        assertTrue(BearingRestoration.restore(source, reloadedCheckpoint, world));
        assertEquals(Map.of(0, "antenna", 1, "antenna", 2, "antenna"), world.cells);
        assertEquals(3, world.placements);
    }
    private static class World implements BearingRestoration.World<Integer, String> {
        int refuse = -1;
        int placements;
        final Map<Integer, String> cells = new HashMap<>();
        @Override public boolean loaded(Integer position) { return true; }
        @Override public String state(Integer position) { return cells.get(position); }
        @Override public boolean empty(String state) { return state == null; }
        @Override public boolean place(Integer position, String state) {
            if (position == refuse) return false;
            placements++; cells.put(position, state); return true;
        }
    }
}
