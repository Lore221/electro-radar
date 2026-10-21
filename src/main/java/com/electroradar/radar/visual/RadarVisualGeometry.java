package com.electroradar.radar.visual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Независимая от монитора плоская геометрия обзора в мировых X/Z.
 *
 * <p>Любой радар передаёт ограниченный набор отрезков и треугольников. Одна часть может
 * содержать несколько треугольников, поэтому возможна произвольная составная форма.
 * Порядок частей задаёт поставщик; клиент лишь подсвечивает их по этому порядку.</p>
 */
public final class RadarVisualGeometry {
    private static final double EPSILON = 1.0E-8D;
    private static final double MAX_ARC_STEP = Math.PI / 48.0D;
    public static final int MAX_LINES = 512;
    public static final int MAX_TRIANGLES = 512;

    private RadarVisualGeometry() {
    }

    /** Поиск по небольшому набору треугольников, одинаковый для любой формы радара. */
    public static int sectorIndex(RadarVisualProfile profile, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return -1;
        for (Sector sector : profile.coverage().sectors()) {
            for (Triangle triangle : sector.triangles()) {
                if (contains(triangle, x, z)) return sector.index();
            }
        }
        return -1;
    }

    public static Coverage build(RadarVisualProfile profile) {
        return profile.coverage();
    }

    /** Граница принадлежит первой части в стабильном порядке; это исключает двойные метки. */
    private static boolean contains(Triangle triangle, double x, double z) {
        if (Math.abs(cross(triangle.a(), triangle.b(), triangle.c().x(), triangle.c().z()))
                <= 1.0E-12D) return false;
        double a = cross(triangle.a(), triangle.b(), x, z);
        double b = cross(triangle.b(), triangle.c(), x, z);
        double c = cross(triangle.c(), triangle.a(), x, z);
        return (a >= -EPSILON && b >= -EPSILON && c >= -EPSILON)
                || (a <= EPSILON && b <= EPSILON && c <= EPSILON);
    }

    private static double cross(Point first, Point second, double x, double z) {
        return (second.x() - first.x()) * (z - first.z())
                - (second.z() - first.z()) * (x - first.x());
    }

    /** Общая геометрия квадрата; контроллеры создают секторную Coverage из своих настроек. */
    public static Coverage rectangle(double centerX, double centerZ,
                                     double halfX, double halfZ, int sectorCount) {
        if (!positive(halfX) || !positive(halfZ)) {
            throw new IllegalArgumentException("Invalid rectangle half-size");
        }
        checkCount(sectorCount);
        Point center = new Point(centerX, centerZ);
        List<Point> corners = List.of(
                new Point(centerX - halfX, centerZ - halfZ),
                new Point(centerX + halfX, centerZ - halfZ),
                new Point(centerX + halfX, centerZ + halfZ),
                new Point(centerX - halfX, centerZ + halfZ));
        ArrayList<Sector> sectors = new ArrayList<>(sectorCount);
        ArrayList<Segment> separators = new ArrayList<>(sectorCount);
        double width = RadarVisualProfile.FULL_TURN / sectorCount;
        for (int i = 0; i < sectorCount; i++) {
            double start = i * width;
            double end = (i + 1) * width;
            ArrayList<CornerAtAngle> included = new ArrayList<>(4);
            for (Point corner : corners) {
                double angle = normalize(Math.atan2(corner.x() - centerX,
                        -(corner.z() - centerZ)));
                if (angle > start + EPSILON && angle < end - EPSILON) {
                    included.add(new CornerAtAngle(angle, corner));
                }
            }
            included.sort(Comparator.comparingDouble(CornerAtAngle::angle));
            ArrayList<Point> polygon = new ArrayList<>();
            polygon.add(center);
            polygon.add(rectangleBoundary(center, halfX, halfZ, start));
            for (CornerAtAngle corner : included) polygon.add(corner.point());
            Point endPoint = rectangleBoundary(center, halfX, halfZ, end);
            if (!samePoint(polygon.get(polygon.size() - 1), endPoint)) polygon.add(endPoint);
            Sector part = sector(i, polygon);
            sectors.add(sectorCount == 1
                    ? new Sector(i, part.triangles(), closedSegments(corners)) : part);
            if (sectorCount > 1) separators.add(new Segment(center,
                    rectangleBoundary(center, halfX, halfZ, start)));
        }
        return new Coverage(closedSegments(corners), separators, sectors);
    }

    /** Построитель направленной панели: дуга разбивается на малые треугольники. */
    public static Coverage circularSector(double centerX, double centerZ, double radius,
                                          double startAngle, double sweepAngle, int sectorCount) {
        if (!Double.isFinite(radius) || radius < 0.0D || !Double.isFinite(startAngle) || !Double.isFinite(sweepAngle)
                || sweepAngle <= 0.0D || sweepAngle > RadarVisualProfile.FULL_TURN) {
            throw new IllegalArgumentException("Invalid circular sector");
        }
        checkCount(sectorCount);
        // Нулевая дальность разрешена конфигом. Пустая Coverage показывает, что радар
        // зарегистрирован, но сейчас не имеет площади обзора; серверный скан также пуст.
        if (radius == 0.0D) {
            ArrayList<Sector> emptySectors = new ArrayList<>(sectorCount);
            for (int i = 0; i < sectorCount; i++) emptySectors.add(new Sector(i, List.of(), List.of()));
            return new Coverage(List.of(), List.of(), emptySectors);
        }
        Point center = new Point(centerX, centerZ);
        /*
         * Сначала строится одна каноническая ломаная дуги. Затем визуальные части режут именно
         * этот многоугольник лучами из центра. Поэтому сумма треугольников всегда совпадает с
         * внешним контуром и не меняется при visual.parts; этот инвариант важен и для серверного
         * SAT-сканера, который использует те же примитивы покрытия.
         */
        int outlineSteps = Math.max(1, (int) Math.ceil(sweepAngle / MAX_ARC_STEP));
        double angleStep = sweepAngle / outlineSteps;
        ArrayList<Point> arcPoints = new ArrayList<>(outlineSteps + 1);
        for (int step = 0; step <= outlineSteps; step++) {
            arcPoints.add(circlePoint(center, radius, startAngle + step * angleStep));
        }

        ArrayList<Sector> sectors = new ArrayList<>(sectorCount);
        ArrayList<Segment> separators = new ArrayList<>();
        double width = sweepAngle / sectorCount;
        for (int i = 0; i < sectorCount; i++) {
            double start = startAngle + i * width;
            double end = i == sectorCount - 1 ? startAngle + sweepAngle
                    : startAngle + (i + 1) * width;
            ArrayList<Point> polygon = new ArrayList<>();
            polygon.add(center);
            Point firstBoundary = arcBoundaryAtAngle(center, arcPoints, startAngle, angleStep, start);
            if (!samePoint(center, firstBoundary)) polygon.add(firstBoundary);
            for (int step = 1; step < outlineSteps; step++) {
                double vertexAngle = startAngle + step * angleStep;
                if (vertexAngle > start && vertexAngle < end) {
                    Point point = arcPoints.get(step);
                    if (!samePoint(polygon.get(polygon.size() - 1), point)) polygon.add(point);
                }
            }
            Point lastBoundary = arcBoundaryAtAngle(center, arcPoints, startAngle, angleStep, end);
            if (!samePoint(polygon.get(polygon.size() - 1), lastBoundary)) polygon.add(lastBoundary);
            sectors.add(sector(i, polygon));
            if (sectorCount > 1 && (i > 0 || isFullTurn(sweepAngle))) {
                separators.add(new Segment(center, firstBoundary));
            }
        }

        ArrayList<Point> outlinePoints = new ArrayList<>(outlineSteps + 2);
        if (!isFullTurn(sweepAngle)) outlinePoints.add(center);
        for (int i = 0; i <= outlineSteps; i++) {
            if (isFullTurn(sweepAngle) && i == outlineSteps) break;
            outlinePoints.add(arcPoints.get(i));
        }
        List<Segment> outline = closedSegments(outlinePoints);
        if (sectorCount == 1 && isFullTurn(sweepAngle)) {
            Sector part = sectors.get(0);
            // Полный круг без внутренних частей имеет только круговой внешний контур, без луча.
            sectors.set(0, new Sector(0, part.triangles(), outline));
        }
        return new Coverage(outline, separators, sectors);
    }

    private static Sector sector(int index, List<Point> polygon) {
        ArrayList<Triangle> triangles = new ArrayList<>(Math.max(0, polygon.size() - 2));
        for (int i = 1; i + 1 < polygon.size(); i++) {
            Point a = polygon.get(0);
            Point b = polygon.get(i);
            Point c = polygon.get(i + 1);
            if (Math.abs(cross(a, b, c.x(), c.z())) > 1.0E-12D) {
                triangles.add(new Triangle(a, b, c));
            }
        }
        return new Sector(index, triangles, closedSegments(polygon));
    }

    private static Point rectangleBoundary(Point center, double halfX, double halfZ, double angle) {
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        double xDistance = Math.abs(sine) < EPSILON ? Double.POSITIVE_INFINITY : halfX / Math.abs(sine);
        double zDistance = Math.abs(cosine) < EPSILON ? Double.POSITIVE_INFINITY : halfZ / Math.abs(cosine);
        double distance = Math.min(xDistance, zDistance);
        return new Point(center.x() + sine * distance, center.z() - cosine * distance);
    }

    private static Point circlePoint(Point center, double radius, double angle) {
        return new Point(center.x() + Math.sin(angle) * radius,
                center.z() - Math.cos(angle) * radius);
    }

    /** Точка пересечения луча разделителя с отрезком канонической дуги. */
    private static Point arcBoundaryAtAngle(Point center, List<Point> arcPoints,
                                            double startAngle, double angleStep, double angle) {
        double alongArc = Math.clamp((angle - startAngle) / angleStep, 0.0D,
                arcPoints.size() - 1.0D);
        int firstIndex = Math.min((int) Math.floor(alongArc), arcPoints.size() - 2);
        double fraction = alongArc - firstIndex;
        if (fraction <= 1.0E-10D) return arcPoints.get(firstIndex);
        if (fraction >= 1.0D - 1.0E-10D) return arcPoints.get(firstIndex + 1);

        Point first = arcPoints.get(firstIndex);
        Point second = arcPoints.get(firstIndex + 1);
        double rayX = Math.sin(angle);
        double rayZ = -Math.cos(angle);
        double edgeX = second.x() - first.x();
        double edgeZ = second.z() - first.z();
        double fromCenterX = first.x() - center.x();
        double fromCenterZ = first.z() - center.z();
        double denominator = edgeX * rayZ - edgeZ * rayX;
        if (Math.abs(denominator) < 1.0E-14D) {
            // Почти параллельный случай появляется лишь на вершине из-за округления угла.
            return new Point(first.x() + edgeX * fraction, first.z() + edgeZ * fraction);
        }
        double edgeFraction = -(fromCenterX * rayZ - fromCenterZ * rayX) / denominator;
        edgeFraction = Math.clamp(edgeFraction, 0.0D, 1.0D);
        return new Point(first.x() + edgeX * edgeFraction, first.z() + edgeZ * edgeFraction);
    }

    private static List<Segment> closedSegments(List<Point> points) {
        ArrayList<Segment> result = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Point first = points.get(i);
            Point second = points.get((i + 1) % points.size());
            if (!samePoint(first, second)) result.add(new Segment(first, second));
        }
        return List.copyOf(result);
    }

    private static boolean samePoint(Point a, Point b) {
        return Math.abs(a.x() - b.x()) < EPSILON && Math.abs(a.z() - b.z()) < EPSILON;
    }

    private static boolean isFullTurn(double angle) {
        return Math.abs(angle - RadarVisualProfile.FULL_TURN) < EPSILON;
    }

    private static double normalize(double angle) {
        double normalized = angle % RadarVisualProfile.FULL_TURN;
        return normalized < 0.0D ? normalized + RadarVisualProfile.FULL_TURN : normalized;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0D;
    }

    private static void checkCount(int count) {
        if (count < RadarVisualProfile.MIN_SECTOR_COUNT
                || count > RadarVisualProfile.MAX_SECTOR_COUNT) {
            throw new IllegalArgumentException("Invalid visual sector count");
        }
    }

    public record Point(double x, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Non-finite visual point");
            }
        }
    }

    public record Segment(Point start, Point end) {
        public Segment {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    public record Triangle(Point a, Point b, Point c) {
        public Triangle {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            Objects.requireNonNull(c, "c");
        }
    }

    /** Границы части передаются отдельно, чтобы внутренние рёбра треугольников не светились. */
    public record Sector(int index, List<Triangle> triangles, List<Segment> boundary) {
        public Sector {
            triangles = List.copyOf(triangles);
            boundary = List.copyOf(boundary);
            if (index < 0 || index >= RadarVisualProfile.MAX_SECTOR_COUNT
                    || (triangles.isEmpty() && !boundary.isEmpty()) || triangles.size() > MAX_TRIANGLES
                    || boundary.size() > MAX_LINES) {
                throw new IllegalArgumentException("Invalid visual sector");
            }
        }
    }

    public record Coverage(List<Segment> outline, List<Segment> separators, List<Sector> sectors) {
        public Coverage {
            outline = List.copyOf(outline);
            separators = List.copyOf(separators);
            sectors = List.copyOf(sectors);
            int triangleCount = 0;
            int boundaryCount = 0;
            for (int i = 0; i < sectors.size(); i++) {
                Sector sector = Objects.requireNonNull(sectors.get(i), "sector");
                if (sector.index() != i) throw new IllegalArgumentException("Unordered visual sectors");
                triangleCount += sector.triangles().size();
                boundaryCount += sector.boundary().size();
            }
            /*
             * Нулевая дальность — единый валидный случай, когда пусты и контур, и разделители,
             * и все части. Во всех остальных покрытиях каждая часть должна иметь площадь.
             * Outline при этом остаётся необязательным для общего контракта визуальных профилей.
             */
            boolean emptyCoverage = outline.isEmpty() && separators.isEmpty()
                    && sectors.stream().allMatch(sector -> sector.triangles().isEmpty()
                    && sector.boundary().isEmpty());
            if (!emptyCoverage && sectors.stream().anyMatch(sector -> sector.triangles().isEmpty())) {
                throw new IllegalArgumentException("Inconsistent visual coverage sectors");
            }
            if (outline.size() + separators.size() + boundaryCount > MAX_LINES
                    || triangleCount > MAX_TRIANGLES
                    || sectors.size() < RadarVisualProfile.MIN_SECTOR_COUNT
                    || sectors.size() > RadarVisualProfile.MAX_SECTOR_COUNT) {
                throw new IllegalArgumentException("Visual coverage exceeds bounds");
            }
        }
    }

    private record CornerAtAngle(double angle, Point point) {
    }
}
