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
            if (!world.loaded(cell.getKey())) return false;
            S existing = world.state(cell.getKey());
            if (!world.empty(existing) && !(checkpoint.contains(cell.getKey()) && cell.getValue().equals(existing))) return false;
        }
        for (var cell : source.entrySet()) {
            if (!world.empty(world.state(cell.getKey()))) continue;
            if (!world.place(cell.getKey(), cell.getValue())) return false;
            checkpoint.add(cell.getKey());
        }
        return source.entrySet().stream().allMatch(cell -> cell.getValue().equals(world.state(cell.getKey())));
    }
}
