package com.electroradar.radar.spatial;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Suppresses destructive source-side recovery only during an actual block-transfer batch. */
public final class InstrumentTransferScope implements AutoCloseable {
    private static final ThreadLocal<Deque<InstrumentTransferScope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);
    private final Level source;
    private final Set<BlockPos> positions;
    public InstrumentTransferScope(Level source, Collection<BlockPos> positions) {
        this.source = source; this.positions = Set.copyOf(positions); SCOPES.get().push(this);
    }
    public static boolean moving(Level level, BlockPos position) {
        for (var scope : SCOPES.get()) if (scope.source == level && scope.positions.contains(position)) return true;
        return false;
    }
    @Override public void close() {
        if (SCOPES.get().pop() != this) throw new IllegalStateException("Transfer scope ordering");
        if (SCOPES.get().isEmpty()) SCOPES.remove();
    }
}
