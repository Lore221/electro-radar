package com.electroradar.client.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.electroradar.config.RadarClientConfig;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.spatial.Silhouette;

class MonitorTargetPaletteTest {
    @BeforeAll static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }
    @Test void structureWithoutEntityTypeUsesOtherColor() {
        assertEquals(RadarClientConfig.palette().targets().other(),
                MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.MOVING_STRUCTURE, "")));
    }
    @Test void unknownEntityTypeDoesNotBecomePassiveMob() {
        assertEquals(RadarClientConfig.palette().targets().other(),
                MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.ENTITY, "missing:structure")));
    }
    @Test void knownEntityCategoriesRetainTheirColors() {
        var colors = RadarClientConfig.palette().targets();
        assertEquals(colors.player(), MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.ENTITY, "minecraft:player")));
        assertEquals(colors.hostile(), MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.ENTITY, "minecraft:zombie")));
        assertEquals(colors.passive(), MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.ENTITY, "minecraft:pig")));
        assertEquals(colors.other(), MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.ENTITY, "minecraft:item")));
    }
    @Test void structureIsNotClassifiedAsAnEntityEvenWithAnEntityTypeString() {
        assertEquals(RadarClientConfig.palette().targets().other(),
                MonitorTargetPalette.entityRgb(target(TargetRef.SourceKind.MOVING_STRUCTURE, "minecraft:pig")));
    }
    private static MonitorClientState.DisplayTarget target(TargetRef.SourceKind kind, String type) {
        var world = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        return new MonitorClientState.DisplayTarget(new TargetRef(world, kind, new UUID(0, 1)), true, true,
                0, 64, 0, type, false, -1, 0, -1, -1, Silhouette.EMPTY);
    }
}
