package com.electroradar.radar.alarm;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Единственный слот: владеет предметом, возвращает его ровно один раз. */
public final class ProtectionZoneSlot {
    private ItemStack item = ItemStack.EMPTY;
    private ProtectionZone zone;

    public ProtectionZone zone() { return zone; }

    public boolean insert(ItemStack source, ProtectionZone selected) {
        if (!item.isEmpty() || source.isEmpty() || selected == null) return false;
        item = source.split(1);
        zone = selected;
        return true;
    }

    public ItemStack extract() {
        ItemStack result = item;
        item = ItemStack.EMPTY;
        zone = null;
        return result;
    }

    public void write(CompoundTag tag, HolderLookup.Provider registries) {
        if (!item.isEmpty()) tag.put("ZoneSelector", item.save(registries));
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        item = tag.contains("ZoneSelector") ? ItemStack.parseOptional(registries, tag.getCompound("ZoneSelector")) : ItemStack.EMPTY;
        zone = item.isEmpty() ? null : ZoneSelectionItem.zone(item).orElse(null);
    }
}
