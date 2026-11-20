package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class RadarNetworkKindsTest {
    @Test void unloadedSourceCannotChangeKindAfterSavingAndRestart() {
        var data=new RadarNetworkKinds();
        var simple=NetworkId.create(); var ordinary=NetworkId.create();
        data.claim(simple,NetworkKind.SIMPLE_RADAR);
        data.claim(ordinary,NetworkKind.RADAR);
        var restored=RadarNetworkKinds.load(data.save(new CompoundTag(),null),null);
        assertEquals(NetworkKind.SIMPLE_RADAR,restored.kind(simple).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->restored.claim(simple,NetworkKind.RADAR));
        assertThrows(IllegalArgumentException.class,()->restored.claim(ordinary,NetworkKind.SIMPLE_RADAR));
        restored.claim(simple,NetworkKind.SIMPLE_RADAR);
        assertEquals(NetworkKind.SIMPLE_RADAR,restored.kind(simple).orElseThrow());
        assertTrue(restored.kind(NetworkId.create()).isEmpty());
    }
}
