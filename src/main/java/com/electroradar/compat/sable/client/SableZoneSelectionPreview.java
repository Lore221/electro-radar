package com.electroradar.compat.sable.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.AllSpecialTextures;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

/** Optional client integration: show the structures intersecting the selected volume. */
public final class SableZoneSelectionPreview {
    private static final Set<OutlineKey> VISIBLE = new HashSet<>();

    private SableZoneSelectionPreview() { }

    public static void update(AABB selection) {
        Outliner outliner = Outliner.getInstance();
        var level = Minecraft.getInstance().level;
        Set<OutlineKey> current = new HashSet<>();
        if (selection != null && level != null) {
            SubLevel owner = Sable.HELPER.getContainingClient(selection.getCenter());
            BoundingBox3d world = new BoundingBox3d(selection);
            if (owner != null) world.transform(owner.logicalPose(), world);
            for (SubLevel structure : Sable.HELPER.getAllIntersecting(level, world)) {
                if (structure == owner || structure.isRemoved()) continue;
                OutlineKey key = new OutlineKey(structure.getUniqueId());
                current.add(key);
                outliner.chaseAABB(key, structure.getPlot().getBoundingBox().toAABB())
                        .colored(0x86A4E3).lineWidth(1 / 32f)
                        .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED);
            }
        }
        for (OutlineKey key : VISIBLE) if (!current.contains(key)) outliner.remove(key);
        VISIBLE.clear();
        VISIBLE.addAll(current);
    }

    private record OutlineKey(UUID structure) { }
}
