package com.electroradar.client.alarm;

import com.simibubi.create.AllSpecialTextures;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Presentation shared by preview, saved selection and the zone applied to an Alarm. */
final class ZoneSelectionOutlines {
    private final Outliner outliner;
    private final Object selection = new Object();
    private final Object applied = new Object();

    ZoneSelectionOutlines(Outliner outliner) { this.outliner = outliner; }

    void clear() { outliner.remove(selection); outliner.remove(applied); }

    void update(AABB bounds, Direction face, int color, AABB alarmBounds) {
        // chaseAABB preserves previous bounds for Catnip tick and render interpolation.
        if (bounds == null) outliner.remove(selection);
        if (alarmBounds == null) outliner.remove(applied);
        if (bounds != null) outliner.chaseAABB(selection, bounds).colored(color)
                .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                .lineWidth(1 / 16f).highlightFace(face);
        if (alarmBounds != null) outliner.chaseAABB(applied, alarmBounds).colored(0x70D680)
                .lineWidth(1 / 24f);
    }
}
