package com.electroradar.radar.alarm;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/** Переносимая абсолютная зона. Выбранные блоки включены; регистрация сети в неё не входит. */
public record ProtectionZone(ResourceLocation dimension, ZoneBounds coordinates) {
    public ProtectionZone {
        if (dimension == null || coordinates == null) throw new IllegalArgumentException("Missing zone data");
    }

    public ProtectionZone(ResourceLocation dimension, BlockPos first, BlockPos second) {
        this(dimension, new ZoneBounds(first.getX(), first.getY(), first.getZ(), second.getX(), second.getY(), second.getZ()));
    }

    public BlockPos min() { return new BlockPos(coordinates.minX(), coordinates.minY(), coordinates.minZ()); }
    public BlockPos max() { return new BlockPos(coordinates.maxX(), coordinates.maxY(), coordinates.maxZ()); }
    public int width() { return coordinates.width(); }
    public int height() { return coordinates.height(); }
    public int depth() { return coordinates.depth(); }
    public ProtectionBox geometry() { return coordinates.geometry(); }
    public AABB bounds() { return new AABB(coordinates.minX(), coordinates.minY(), coordinates.minZ(),
            coordinates.maxX() + 1.0D, coordinates.maxY() + 1.0D, coordinates.maxZ() + 1.0D); }

    public ProtectionZone resize(Direction face, int step) {
        int axis = switch (face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
        return new ProtectionZone(dimension, coordinates.resize(axis, face.getAxisDirection().getStep(), step));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putString("Dimension", dimension.toString());
        tag.putLong("Min", min().asLong());
        tag.putLong("Max", max().asLong());
        return tag;
    }

    public static Optional<ProtectionZone> load(CompoundTag tag) {
        if (tag.getInt("Version") != 1 || !tag.contains("Min", Tag.TAG_LONG)
                || !tag.contains("Max", Tag.TAG_LONG)) return Optional.empty();
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) return Optional.empty();
        try {
            return Optional.of(new ProtectionZone(dimension, BlockPos.of(tag.getLong("Min")), BlockPos.of(tag.getLong("Max"))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
