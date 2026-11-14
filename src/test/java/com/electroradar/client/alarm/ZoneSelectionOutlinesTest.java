package com.electroradar.client.alarm;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.outliner.AABBOutline;
import net.createmod.catnip.outliner.ChasingAABBOutline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZoneSelectionOutlinesTest {
    @Test void structureSelectionKeepsLocalChasingBoxForSableRenderInterpolation() {
        Outliner outliner = new Outliner();
        ZoneSelectionOutlines selection = new ZoneSelectionOutlines(outliner);
        AABB local = new AABB(1000000, 0, 1000000, 1000003, 2, 1000004);
        selection.update(local, net.minecraft.core.Direction.UP, 0, null);
        assertEquals(1, outliner.getOutlines().size(), "Sable needs one local box, not tick-transformed edges");
        var outline = outliner.getOutlines().values().iterator().next().getOutline();
        assertInstanceOf(ChasingAABBOutline.class, outline);
        assertEquals(local, ((AABBOutline) outline).getBounds());
    }

    @Test void movingSelectionEasesTowardItsNewBoundaryInsteadOfJumping() {
        Outliner outliner = new Outliner();
        ZoneSelectionOutlines selection = new ZoneSelectionOutlines(outliner);
        selection.update(new AABB(0, 0, 0, 1, 1, 1), null, 0, null);
        outliner.tickOutlines();
        selection.update(new AABB(0, 0, 0, 9, 1, 1), null, 0, null);
        outliner.tickOutlines();
        AABBOutline outline = (AABBOutline) outliner.getOutlines().values().iterator().next().getOutline();
        assertEquals(5, outline.getBounds().maxX, 1e-6, "one tick should move halfway, not snap to nine");
    }
    @Test void appliedZoneAlsoEasesAndInactiveVisualsDisappear() {
        Outliner outliner = new Outliner();
        ZoneSelectionOutlines selection = new ZoneSelectionOutlines(outliner);
        selection.update(null, null, 0, new AABB(0, 0, 0, 1, 1, 1));
        outliner.tickOutlines();
        selection.update(null, null, 0, new AABB(0, 0, 0, 9, 1, 1));
        outliner.tickOutlines();
        AABBOutline outline = (AABBOutline) outliner.getOutlines().values().iterator().next().getOutline();
        assertEquals(5, outline.getBounds().maxX, 1e-6);
        selection.update(null, null, 0, null);
        assertTrue(outliner.getOutlines().isEmpty());
    }

    @Test void selectionRendersBetweenTickPositions() {
        Outliner outliner = new Outliner();
        ZoneSelectionOutlines selection = new ZoneSelectionOutlines(outliner);
        selection.update(new AABB(0, 0, 0, 1, 1, 1), null, 0, null);
        Object slot = outliner.getOutlines().keySet().iterator().next();
        CapturedOutline rendered = new CapturedOutline(new AABB(0, 0, 0, 1, 1, 1));
        outliner.showOutline(slot, rendered);
        selection.update(new AABB(0, 0, 0, 9, 1, 1), null, 0, null);
        outliner.tickOutlines();
        assertSame(rendered, outliner.getOutlines().get(slot).getOutline());
        rendered.render(null, null, Vec3.ZERO, 0);
        assertEquals(1, rendered.box.maxX, 1e-6);
        rendered.render(null, null, Vec3.ZERO, .5f);
        assertEquals(3, rendered.box.maxX, 1e-6);
        rendered.render(null, null, Vec3.ZERO, 1);
        assertEquals(5, rendered.box.maxX, 1e-6);
    }

    private static final class CapturedOutline extends ChasingAABBOutline {
        private AABB box;
        CapturedOutline(AABB bounds) { super(bounds); }
        @Override protected void renderBox(PoseStack pose, SuperRenderTypeBuffer buffer,
                Vec3 camera, AABB bounds, Vector4f color, int light, boolean disableNormals) {
            box = bounds;
        }
    }
}
