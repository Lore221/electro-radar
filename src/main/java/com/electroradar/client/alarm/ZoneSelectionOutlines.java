package com.electroradar.client.alarm;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllSpecialTextures;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.spatial.SpatialTransform;

/**
 * Presentation shared by preview, saved selection and the zone applied to an Alarm.
 *
 * <p>Зона в мире рисуется плавным контуром Catnip. Зона на движущейся структуре лежит в её
 * координатах и повёрнута её позой, поэтому рисуется двенадцатью рёбрами в мире; рёбра
 * выбранной грани выделяются.</p>
 */
final class ZoneSelectionOutlines {
    private static final int APPLIED_COLOR = 0x70D680;
    private static final int FACE_COLOR = 0xFFFFFF;
    private final Outliner outliner;
    private final Object selection = new Object();
    private final Object applied = new Object();
    private final List<Object> selectionEdges = edges(selection);
    private final List<Object> appliedEdges = edges(applied);

    ZoneSelectionOutlines(Outliner outliner) { this.outliner = outliner; }

    void clear() {
        outliner.remove(selection);
        outliner.remove(applied);
        selectionEdges.forEach(outliner::remove);
        appliedEdges.forEach(outliner::remove);
    }

    void update(AABB bounds, Direction face, int color, AABB alarmBounds) {
        update(bounds, SpatialTransform.IDENTITY, face, color, alarmBounds, SpatialTransform.IDENTITY);
    }

    void update(AABB bounds, SpatialTransform space, Direction face, int color,
                AABB alarmBounds, SpatialTransform alarmSpace) {
        boolean rotated = bounds != null && !space.isIdentity();
        boolean alarmRotated = alarmBounds != null && !alarmSpace.isIdentity();
        if (bounds == null || rotated) outliner.remove(selection);
        if (alarmBounds == null || alarmRotated) outliner.remove(applied);
        if (!rotated) selectionEdges.forEach(outliner::remove);
        if (!alarmRotated) appliedEdges.forEach(outliner::remove);
        // chaseAABB preserves previous bounds for Catnip tick and render interpolation.
        if (bounds != null && !rotated) outliner.chaseAABB(selection, bounds).colored(color)
                .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                .lineWidth(1 / 16f).highlightFace(face);
        if (rotated) drawEdges(selectionEdges, bounds, space, face, color, 1 / 16f);
        if (alarmBounds != null && !alarmRotated) outliner.chaseAABB(applied, alarmBounds).colored(APPLIED_COLOR)
                .lineWidth(1 / 24f);
        if (alarmRotated) drawEdges(appliedEdges, alarmBounds, alarmSpace, null, APPLIED_COLOR, 1 / 24f);
    }

    private void drawEdges(List<Object> slots, AABB box, SpatialTransform space, Direction face, int color, float width) {
        int slot = 0;
        for (int a = 0; a < 8; a++) {
            for (int axis = 0; axis < 3; axis++) {
                int b = a | (1 << axis);
                if (b == a) continue;
                boolean onFace = face != null && onFace(a, face) && onFace(b, face);
                outliner.showLine(slots.get(slot++), space.toWorld(corner(box, a)), space.toWorld(corner(box, b)))
                        .colored(onFace ? FACE_COLOR : color).lineWidth(onFace ? width * 1.5f : width);
            }
        }
    }

    /** Угол с битами 1/2/4 — максимум по X/Y/Z. */
    private static Vec3 corner(AABB box, int bits) {
        return new Vec3((bits & 1) == 0 ? box.minX : box.maxX, (bits & 2) == 0 ? box.minY : box.maxY,
                (bits & 4) == 0 ? box.minZ : box.maxZ);
    }

    private static boolean onFace(int corner, Direction face) {
        int bit = switch (face.getAxis()) {
            case X -> 1;
            case Y -> 2;
            case Z -> 4;
        };
        return ((corner & bit) != 0) == (face.getAxisDirection() == Direction.AxisDirection.POSITIVE);
    }

    private static List<Object> edges(Object owner) {
        List<Object> slots = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) slots.add(new Edge(owner, i));
        return List.copyOf(slots);
    }

    private record Edge(Object owner, int index) { }
}
