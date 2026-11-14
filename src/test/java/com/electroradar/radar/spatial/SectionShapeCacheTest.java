package com.electroradar.radar.spatial;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class SectionShapeCacheTest {
    @Test void dirtySectionIsReadWithoutRescanningTheOtherLoadedSections() {
        var cache=new SectionShapeCache(100);
        Section first=new Section(1,0),second=new Section(2,16);
        var loaded=List.of(first,second);
        var initial=cache.refresh(0,()->loaded.stream().map(Section::input).toList());
        assertSame(initial,cache.refresh(0,()->{fail("Unchanged shape must not enumerate chunks");return List.of();}));
        first.boxes=List.of(new AABB(1,0,0,2,1,1));
        cache.invalidate(first.id);
        var changed=cache.refresh(0,()->loaded.stream().map(Section::input).toList());
        assertEquals(2,first.reads.get()); assertEquals(1,second.reads.get());
        assertEquals(List.of(first.boxes.getFirst(),second.boxes.getFirst()),changed.boxes());
    }

    @Test void periodicVerificationReadsAllSectionsButKeepsUnchangedShapeIdentity() {
        var cache=new SectionShapeCache(100); var section=new Section(1,0);
        var initial=cache.refresh(0,()->List.of(section.input()));
        assertSame(initial,cache.refresh(1,()->List.of(section.input())));
        assertEquals(2,section.reads.get());
    }

    @Test void unloadedAndReplacedSectionsCannotLeaveOldBoxesInTheShape() {
        var cache=new SectionShapeCache(100); Section first=new Section(1,0),second=new Section(2,16);
        cache.refresh(0,()->List.of(first.input(),second.input()));
        var replacement=new Section(2,32);
        cache.invalidate(1);
        var next=cache.refresh(0,()->List.of(replacement.input()));
        assertEquals(replacement.boxes,next.boxes());
        assertEquals(1,replacement.reads.get());
    }

    @Test void failedRebuildDoesNotLosePendingInvalidationOrPublishAnOversizedShape() {
        var cache=new SectionShapeCache(1); var section=new Section(1,0);
        cache.refresh(0,()->List.of(section.input()));
        section.boxes=List.of(new AABB(1,0,0,2,1,1),new AABB(2,0,0,3,1,1));
        cache.invalidate(1);
        assertThrows(IllegalStateException.class,()->cache.refresh(0,()->List.of(section.input())));
        section.boxes=List.of(new AABB(2,0,0,3,1,1));
        assertEquals(section.boxes,cache.refresh(0,()->List.of(section.input())).boxes());
    }

    private static final class Section {
        final long id; final Object identity=new Object(); final AtomicInteger reads=new AtomicInteger();
        List<AABB> boxes;
        Section(long id,int x) { this.id=id; boxes=List.of(new AABB(x,0,0,x+1,1,1)); }
        SectionShapeCache.Section input() {
            return new SectionShapeCache.Section(id,identity,()->{reads.incrementAndGet();return boxes;});
        }
    }
}
