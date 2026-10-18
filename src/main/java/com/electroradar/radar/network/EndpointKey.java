package com.electroradar.radar.network;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Идентичность установленного прибора с учётом его измерения и типа размещения. */
public sealed interface EndpointKey permits EndpointKey.Static, EndpointKey.Moving {
    ResourceKey<Level> dimension();

    /** Ключ неподвижного прибора; позиция копируется в неизменяемое значение. */
    record Static(ResourceKey<Level> dimension, BlockPos position) implements EndpointKey {
        public Static {
            Objects.requireNonNull(dimension, "dimension");
            position = Objects.requireNonNull(position, "position").immutable();
        }
    }

    /** Ключ прибора на движущейся конструкции: UUID конструкции и локальная позиция вместе задают endpoint. */
    record Moving(ResourceKey<Level> dimension, UUID structureId, BlockPos localPosition) implements EndpointKey {
        public Moving {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(structureId, "structureId");
            localPosition = Objects.requireNonNull(localPosition, "localPosition").immutable();
        }
    }
}
