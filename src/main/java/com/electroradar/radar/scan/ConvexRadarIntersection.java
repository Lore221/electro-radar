package com.electroradar.radar.scan;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import com.electroradar.radar.spatial.SpatialTransform;

/** SAT between a transformed box and the convex prisms composing a radar area. */
public final class ConvexRadarIntersection {
    private static final double EPSILON = 1.0E-8D;
    private ConvexRadarIntersection() { }

    public static boolean intersects(RadarScanArea area, List<Vec3> box) {
        if (area.isEmpty()) return false;
        if (area instanceof SectorRadarScanArea sector) {
            for (var triangle : sector.footprint()) {
                List<Vec3> prism = new ArrayList<>(6);
                for (var p : List.of(triangle.a(), triangle.b(), triangle.c())) prism.add(new Vec3(p.x(), sector.minY(), p.z()));
                for (var p : List.of(triangle.a(), triangle.b(), triangle.c())) prism.add(new Vec3(p.x(), sector.maxY(), p.z()));
                Vec3 vertical = new Vec3(0, 1, 0);
                List<Vec3> edges = List.of(prism.get(1).subtract(prism.get(0)),
                        prism.get(2).subtract(prism.get(1)), prism.get(0).subtract(prism.get(2)), vertical);
                if (overlap(box, prism, edges, List.of(vertical,
                        edges.get(0).cross(vertical), edges.get(1).cross(vertical), edges.get(2).cross(vertical)))) return true;
            }
            return false;
        }
        List<Vec3> axes = List.of(new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        return overlap(box, SpatialTransform.IDENTITY.corners(area.queryBounds()), axes, axes);
    }

    private static boolean overlap(List<Vec3> box, List<Vec3> other, List<Vec3> otherEdges, List<Vec3> otherNormals) {
        List<Vec3> edges = List.of(box.get(1).subtract(box.get(0)), box.get(2).subtract(box.get(0)),
                box.get(4).subtract(box.get(0)));
        List<Vec3> axes = new ArrayList<>(otherNormals);
        axes.add(edges.get(0).cross(edges.get(1)));
        axes.add(edges.get(1).cross(edges.get(2)));
        axes.add(edges.get(2).cross(edges.get(0)));
        for (Vec3 edge : edges) for (Vec3 otherEdge : otherEdges) axes.add(edge.cross(otherEdge));
        for (Vec3 axis : axes) {
            if (axis.lengthSqr() < 1.0E-20D) continue;
            Vec3 n = axis.normalize();
            // Relative coordinates avoid cancellation in worlds far from the origin.
            Vec3 origin = box.getFirst();
            double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY;
            double minB = minA, maxB = maxA;
            for (Vec3 p : box) { double d = p.subtract(origin).dot(n); minA = Math.min(minA, d); maxA = Math.max(maxA, d); }
            for (Vec3 p : other) { double d = p.subtract(origin).dot(n); minB = Math.min(minB, d); maxB = Math.max(maxB, d); }
            if (maxA < minB - EPSILON || maxB < minA - EPSILON) return false;
        }
        return true;
    }
}
