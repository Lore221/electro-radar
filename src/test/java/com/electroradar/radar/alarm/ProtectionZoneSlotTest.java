package com.electroradar.radar.alarm;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionZoneSlotTest {
    @BeforeAll static void bootstrap() { net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of()); SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void insertionTransfersOneItemAndExtractionReturnsItOnlyOnce() {
        var slot = new ProtectionZoneSlot();
        var source = new ItemStack(Items.PAPER, 2);
        var zone = new ProtectionZone(ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO, new BlockPos(1, 2, 3));
        assertTrue(slot.insert(source, zone));
        assertEquals(1, source.getCount());
        assertEquals(zone, slot.zone());
        assertFalse(slot.insert(source, zone));
        assertEquals(1, source.getCount());
        assertEquals(1, slot.extract().getCount());
        assertNull(slot.zone());
        assertTrue(slot.extract().isEmpty());
    }
    @Test void savedSelectorRetainsItsZoneAndCustomData() {
        var selector = Items.PAPER;
        var source = new ItemStack(selector);
        var zone = new ProtectionZone(ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO, new BlockPos(4, 5, 6));
        CompoundTag selection = new CompoundTag(); selection.put("Zone", zone.save());
        CustomData.update(DataComponents.CUSTOM_DATA, source, tag -> {
            tag.put("electroradar:protection_zone", selection); tag.putString("marker", "retained");
        });
        var slot = new ProtectionZoneSlot();
        assertTrue(slot.insert(source, zone));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = new CompoundTag(); slot.write(saved, registries);
        var loaded = new ProtectionZoneSlot(); loaded.read(saved, registries);
        assertEquals(zone, loaded.zone());
        var returned = loaded.extract();
        assertEquals(selector, returned.getItem());
        assertEquals("retained", returned.get(DataComponents.CUSTOM_DATA).copyTag().getString("marker"));
        assertTrue(loaded.extract().isEmpty());
    }
}
