package com.electroradar.radar.bearing;

import java.util.Map;
import java.util.Set;

/** Lossless restoration command. World is the Minecraft system boundary; state stays with Create. */
public final class BearingRestoration {
    public interface World<P, S> {
        boolean loaded(P position);
        S state(P position);
        boolean empty(S state);
        boolean place(P position, S state);
    }
    private BearingRestoration() {}
    public static <P, S> boolean restore(Map<P, S> source, Set<P> checkpoint, World<P, S> world) {
        for (var cell : source.entrySet()) {
            // Checkpoint means ownership has passed to the world. A player may have collected
            // that block since the partial restore; recreating it would duplicate the section.
            if (checkpoint.contains(cell.getKey())) continue;
            if (!world.loaded(cell.getKey()) || !world.empty(world.state(cell.getKey()))) return false;
        }
        for (var cell : source.entrySet()) {
            if (checkpoint.contains(cell.getKey())) continue;
            if (!world.place(cell.getKey(), cell.getValue())) return false;
            checkpoint.add(cell.getKey());
        }
        return checkpoint.containsAll(source.keySet());
    }
}
