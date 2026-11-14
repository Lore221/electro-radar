package com.electroradar.radar.spatial;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Проекции бокса на столбцы сетки XZ. Оси, нормализация и допуск совпадают с полным SAT. */
final class ProjectedBlockRasterizer {
    private static final double EPSILON = 1.0E-8D;
    private static final List<Vec3> WORLD_AXES = List.of(new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1));

    private ProjectedBlockRasterizer() { }

    static Set<Long> rasterize(StructureBody.Part child, Vec3 origin, int budget) {
        Set<Long> result = new HashSet<>();
        Columns columns = new Columns();
        for (AABB box : child.boxes()) {
            AABB bounds = child.transform().bounds(box);
            columns.prepare(child.transform().corners(box), bounds);
            for (int z=(int)Math.floor(bounds.minZ-origin.z);z<Math.ceil(bounds.maxZ-origin.z);z++) {
                for (int x=(int)Math.floor(bounds.minX-origin.x);x<Math.ceil(bounds.maxX-origin.x);x++) {
                    if (columns.intersects(x+origin.x,z+origin.z)) result.add(GridSilhouette.key(x,z));
                    if (result.size() > budget) throw new SilhouetteBudgetExceeded("Radar silhouette grid budget exceeded");
                }
            }
        }
        return result;
    }

    /** Буфер переиспользуется между блоками; на клетку не создаются вершины, оси и списки. */
    private static final class Columns {
        private final double[] x = new double[15], z = new double[15];
        private final double[] lowY = new double[15], highY = new double[15];
        private final double[] lowBox = new double[15], highBox = new double[15];
        private Vec3 origin;
        private int count;

        void prepare(List<Vec3> box, AABB bounds) {
            origin = box.getFirst();
            count = 0;
            Vec3 a=box.get(1).subtract(origin), b=box.get(2).subtract(origin), c=box.get(4).subtract(origin);
            for (Vec3 axis : WORLD_AXES) axis(axis, box, bounds);
            axis(a.cross(b),box,bounds); axis(b.cross(c),box,bounds); axis(c.cross(a),box,bounds);
            for (Vec3 edge : List.of(a,b,c)) for (Vec3 axis : WORLD_AXES) axis(edge.cross(axis),box,bounds);
        }

        private void axis(Vec3 axis, List<Vec3> box, AABB bounds) {
            if (axis.lengthSqr() < 1.0E-20D) return;
            Vec3 normal = axis.normalize();
            double min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
            for (Vec3 point : box) {
                double value=(point.x-origin.x)*normal.x+(point.y-origin.y)*normal.y+(point.z-origin.z)*normal.z;
                min=Math.min(min,value); max=Math.max(max,value);
            }
            x[count]=normal.x; z[count]=normal.z;
            lowBox[count]=min; highBox[count]=max;
            lowY[count]=((normal.y < 0 ? bounds.maxY : bounds.minY)-origin.y)*normal.y;
            highY[count]=((normal.y < 0 ? bounds.minY : bounds.maxY)-origin.y)*normal.y;
            count++;
        }

        boolean intersects(double minX, double minZ) {
            double ax=minX-origin.x, bx=minX+1-origin.x, az=minZ-origin.z, bz=minZ+1-origin.z;
            for (int i=0;i<count;i++) {
                // Высота столбца охватывает бокс целиком и уже учтена в lowY/highY.
                // Для новой клетки меняются только X/Z; порядок сумм сохранён как в SAT.
                double low=(x[i] < 0 ? bx : ax)*x[i]+lowY[i]+(z[i] < 0 ? bz : az)*z[i];
                double high=(x[i] < 0 ? ax : bx)*x[i]+highY[i]+(z[i] < 0 ? az : bz)*z[i];
                if (highBox[i] < low-EPSILON || high < lowBox[i]-EPSILON) return false;
            }
            return true;
        }
    }
}
