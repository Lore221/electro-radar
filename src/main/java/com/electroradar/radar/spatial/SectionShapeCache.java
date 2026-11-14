package com.electroradar.radar.spatial;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;

/** Кэш загруженных секций: точечная инвалидация и полная проверка на границе периода. */
public final class SectionShapeCache {
    public record Section(long id, Object identity, Supplier<List<AABB>> read) {
        public Section { Objects.requireNonNull(identity); Objects.requireNonNull(read); }
    }
    private record Entry(WeakReference<Object> identity, List<AABB> boxes) { }
    private final int maxBoxes;
    private final Set<Long> dirty = new HashSet<>();
    private Map<Long,Entry> sections = Map.of();
    private long verifiedPeriod = Long.MIN_VALUE;
    private StructureShape shape;

    public SectionShapeCache(int maxBoxes) {
        if (maxBoxes <= 0) throw new IllegalArgumentException("Invalid block budget");
        this.maxBoxes=maxBoxes;
    }

    public void invalidate(long section) { dirty.add(section); }

    public StructureShape refresh(long period, Supplier<List<Section>> loadedSections) {
        boolean full = shape == null || verifiedPeriod != period;
        if (!full && dirty.isEmpty()) return shape;
        Map<Long,Entry> next = new HashMap<>();
        List<AABB> boxes = new ArrayList<>();
        for (Section section : loadedSections.get()) {
            Entry previous = sections.get(section.id());
            Entry entry = previous;
            if (full || previous == null || previous.identity.get() != section.identity() || dirty.contains(section.id())) {
                List<AABB> read = List.copyOf(section.read().get());
                if (previous != null && previous.identity.get() == section.identity() && previous.boxes.equals(read)) {
                    entry = previous;
                } else entry = new Entry(new WeakReference<>(section.identity()),read);
            }
            if (next.put(section.id(),entry) != null) throw new IllegalArgumentException("Duplicate loaded section");
            if ((long)boxes.size()+entry.boxes.size() > maxBoxes) {
                throw new IllegalStateException("Sable structure exceeds radar block budget");
            }
            boxes.addAll(entry.boxes);
        }
        StructureShape updated = shape != null && shape.boxes().equals(boxes) ? shape : new StructureShape(boxes);
        // Ошибка чтения или лимит выше оставляют прежний кэш и dirty для следующей попытки.
        shape=updated; sections=next; verifiedPeriod=period; dirty.clear();
        return shape;
    }
}
