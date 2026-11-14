package com.electroradar.radar.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import com.electroradar.radar.spatial.SpatialTransform;
import com.electroradar.radar.visual.RadarVisualGeometry;

/** SAT between a transformed box and the convex prisms composing a radar area. */
public final class ConvexRadarIntersection {
    private static final double EPSILON = 1.0E-8D;
    private ConvexRadarIntersection() { }

    public static boolean intersects(RadarScanArea area, List<Vec3> box) {
        if (area.isEmpty()) return false;
        if (area instanceof SectorRadarScanArea sector) {
            for (PreparedPrism prism : sector.prisms()) {
                if (overlap(box, prism.vertices, prism.edges, prism.normals)) return true;
            }
            return false;
        }
        List<Vec3> axes = List.of(new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        return overlap(box, SpatialTransform.IDENTITY.corners(area.queryBounds()), axes, axes);
    }

    /**
     * Точка внутри пересечения, а не центр ограничивающего прямоугольника. Усреднение
     * отсечённых рёбер остаётся внутри обоих выпуклых тел, включая полное вложение и касание.
     * Вызывается только для уже найденной структуры; быстрый SAT поиска остаётся прежним.
     */
    public static Optional<Vec3> intersectionPoint(RadarScanArea area, List<Vec3> box) {
        if (area.isEmpty()) return Optional.empty();
        Polyhedron solid = box(box);
        if (area instanceof SectorRadarScanArea sector) {
            for (PreparedPrism prism : sector.prisms()) {
                Optional<Vec3> point = intersectionPoint(solid, prism.clip);
                if (point.isPresent()) return point;
            }
            return Optional.empty();
        }
        return intersectionPoint(solid, box(SpatialTransform.IDENTITY.corners(area.queryBounds())));
    }

    /** Неизменяемые данные призмы принадлежат области обзора, а не каждой проверке блока. */
    static PreparedPrism preparePrism(RadarVisualGeometry.Triangle triangle, double minY, double maxY) {
        List<Vec3> vertices = new ArrayList<>(6);
        for (var p : List.of(triangle.a(), triangle.b(), triangle.c())) vertices.add(new Vec3(p.x(), minY, p.z()));
        for (var p : List.of(triangle.a(), triangle.b(), triangle.c())) vertices.add(new Vec3(p.x(), maxY, p.z()));
        Vec3 vertical = new Vec3(0,1,0);
        List<Vec3> edges = List.of(vertices.get(1).subtract(vertices.get(0)), vertices.get(2).subtract(vertices.get(1)),
                vertices.get(0).subtract(vertices.get(2)), vertical);
        List<Vec3> normals = List.of(vertical, edges.get(0).cross(vertical), edges.get(1).cross(vertical), edges.get(2).cross(vertical));
        Polyhedron clip = new Polyhedron(vertices, new int[][] {{0,1},{1,2},{2,0},{3,4},{4,5},{5,3},{0,3},{1,4},{2,5}}, new int[0][]);
        clip.addPlane(vertices.get(0), vertical.scale(-1));
        clip.addPlane(vertices.get(3), vertical);
        // Нулевая высота тоже допустима: нормаль не зависит от вертикального ребра.
        for (int i=0;i<3;i++) clip.addPlane(vertices.get(i), vertices.get((i+1)%3).subtract(vertices.get(i)).cross(vertical));
        return new PreparedPrism(List.copyOf(vertices), edges, normals, clip);
    }

    static final class PreparedPrism {
        private final List<Vec3> vertices, edges, normals;
        private final Polyhedron clip;
        private PreparedPrism(List<Vec3> vertices, List<Vec3> edges, List<Vec3> normals, Polyhedron clip) {
            this.vertices=vertices; this.edges=edges; this.normals=normals; this.clip=clip;
        }
    }

    private static Polyhedron box(List<Vec3> vertices) {
        return new Polyhedron(vertices,
                new int[][] {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}},
                new int[][] {{0,1,2},{4,5,6},{0,1,4},{2,3,6},{0,2,4},{1,3,5}});
    }

    private static Optional<Vec3> intersectionPoint(Polyhedron first, Polyhedron second) {
        List<Vec3> points = new ArrayList<>();
        clipEdges(first, second, points);
        clipEdges(second, first, points);
        if (points.isEmpty()) return Optional.empty();
        // Усредняем относительно ближайшей точки, чтобы не терять точность далеко от нуля.
        Vec3 origin = points.getFirst();
        Vec3 offset = Vec3.ZERO;
        for (Vec3 point : points) offset = offset.add(point.subtract(origin));
        return Optional.of(origin.add(offset.scale(1.0D / points.size())));
    }

    private static void clipEdges(Polyhedron source, Polyhedron clip, List<Vec3> points) {
        for (int[] edge : source.edges) {
            Vec3 start = source.vertices.get(edge[0]);
            Vec3 end = source.vertices.get(edge[1]);
            double enter = 0, exit = 1;
            boolean outside = false;
            for (Plane plane : clip.planes) {
                double a = plane.distance(start), b = plane.distance(end);
                if (a > EPSILON && b > EPSILON) { outside = true; break; }
                if (a <= 0 && b <= 0 || Math.abs(a - b) < 1.0E-14D) continue;
                double crossing = a / (a - b);
                if (a > b) enter = Math.max(enter, crossing);
                else exit = Math.min(exit, crossing);
            }
            if (!outside && enter <= exit + EPSILON && exit >= 0 && enter <= 1) {
                Vec3 delta = end.subtract(start);
                points.add(start.add(delta.scale(Math.clamp(enter, 0, 1))));
                points.add(start.add(delta.scale(Math.clamp(exit, 0, 1))));
            }
        }
    }

    private record Plane(Vec3 origin, Vec3 normal) {
        double distance(Vec3 point) { return point.subtract(origin).dot(normal); }
    }

    private static final class Polyhedron {
        final List<Vec3> vertices;
        final int[][] edges;
        final List<Plane> planes = new ArrayList<>();
        final Vec3 center;

        Polyhedron(List<Vec3> vertices, int[][] edges, int[][] faces) {
            this.vertices = vertices;
            this.edges = edges;
            Vec3 origin = vertices.getFirst(), offset = Vec3.ZERO;
            for (Vec3 point : vertices) offset = offset.add(point.subtract(origin).scale(1.0D / vertices.size()));
            center = origin.add(offset);
            for (int[] face : faces) {
                Vec3 a = vertices.get(face[0]);
                addPlane(a, vertices.get(face[1]).subtract(a).cross(vertices.get(face[2]).subtract(a)));
            }
        }

        void addPlane(Vec3 origin, Vec3 normal) {
            normal = normal.normalize();
            if (center.subtract(origin).dot(normal) > 0) normal = normal.scale(-1);
            planes.add(new Plane(origin, normal));
        }
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
            for (Vec3 p : box) { double d = projection(p, origin, n); minA = Math.min(minA, d); maxA = Math.max(maxA, d); }
            for (Vec3 p : other) { double d = projection(p, origin, n); minB = Math.min(minB, d); maxB = Math.max(maxB, d); }
            if (maxA < minB - EPSILON || maxB < minA - EPSILON) return false;
        }
        return true;
    }

    private static double projection(Vec3 point, Vec3 origin, Vec3 axis) {
        return (point.x-origin.x)*axis.x + (point.y-origin.y)*axis.y + (point.z-origin.z)*axis.z;
    }
}
