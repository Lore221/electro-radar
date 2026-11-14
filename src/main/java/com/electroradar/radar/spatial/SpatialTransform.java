package com.electroradar.radar.spatial;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable orthogonal frame; no reference to a mutable foreign pose escapes the adapter. */
public record SpatialTransform(Vec3 localOrigin, Vec3 worldOrigin, Vec3 x, Vec3 y, Vec3 z) {
    public static final SpatialTransform IDENTITY = new SpatialTransform(Vec3.ZERO, Vec3.ZERO,
            new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));

    public SpatialTransform compose(SpatialTransform child) {
        return new SpatialTransform(child.localOrigin(), toWorld(child.worldOrigin()),
                direction(child.x()), direction(child.y()), direction(child.z()));
    }

    public Vec3 toWorld(Vec3 point) {
        Vec3 offset = point.subtract(localOrigin);
        return worldOrigin.add(direction(offset));
    }

    public Vec3 direction(Vec3 direction) {
        return x.scale(direction.x).add(y.scale(direction.y)).add(z.scale(direction.z));
    }

    /** Обратное к {@link #direction}: оси ортогональны, поэтому это проекции на них. */
    public Vec3 localDirection(Vec3 world) {
        return new Vec3(world.dot(x) / x.lengthSqr(), world.dot(y) / y.lengthSqr(), world.dot(z) / z.lengthSqr());
    }

    public boolean isIdentity() {
        return samePose(IDENTITY);
    }

    /** Точное сравнение для частых проверок кэша: без округления позы и record MethodHandle. */
    public boolean samePose(SpatialTransform other) {
        return this == other || other != null && sameVector(localOrigin,other.localOrigin)
                && sameVector(worldOrigin,other.worldOrigin) && sameVector(x,other.x)
                && sameVector(y,other.y) && sameVector(z,other.z);
    }

    private static boolean sameVector(Vec3 first, Vec3 second) {
        return first == second || first != null && second != null
                && Double.compare(first.x,second.x) == 0 && Double.compare(first.y,second.y) == 0
                && Double.compare(first.z,second.z) == 0;
    }

    public Vec3 toLocal(Vec3 point) {
        Vec3 offset = point.subtract(worldOrigin);
        return localOrigin.add(offset.dot(x) / x.lengthSqr(), offset.dot(y) / y.lengthSqr(),
                offset.dot(z) / z.lengthSqr());
    }

    public List<Vec3> corners(AABB box) {
        return List.of(toWorld(new Vec3(box.minX, box.minY, box.minZ)),
                toWorld(new Vec3(box.maxX, box.minY, box.minZ)),
                toWorld(new Vec3(box.minX, box.maxY, box.minZ)),
                toWorld(new Vec3(box.maxX, box.maxY, box.minZ)),
                toWorld(new Vec3(box.minX, box.minY, box.maxZ)),
                toWorld(new Vec3(box.maxX, box.minY, box.maxZ)),
                toWorld(new Vec3(box.minX, box.maxY, box.maxZ)),
                toWorld(new Vec3(box.maxX, box.maxY, box.maxZ)));
    }

    public AABB bounds(AABB box) {
        // Экстремум линейной координаты достигается на выбранных по знакам концах интервалов.
        // Порядок сумм совпадает с direction/toWorld; восемь временных вершин не нужны.
        return new AABB(
                minimum(box,x.x,y.x,z.x,worldOrigin.x), minimum(box,x.y,y.y,z.y,worldOrigin.y),
                minimum(box,x.z,y.z,z.z,worldOrigin.z), maximum(box,x.x,y.x,z.x,worldOrigin.x),
                maximum(box,x.y,y.y,z.y,worldOrigin.y), maximum(box,x.z,y.z,z.z,worldOrigin.z));
    }

    /** Точные границы объединения, без промежуточного AABB на каждый занятый блок. */
    public Optional<AABB> bounds(List<AABB> boxes) {
        if (boxes.isEmpty()) return Optional.empty();
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for (AABB box : boxes) {
            minX=Math.min(minX,minimum(box,x.x,y.x,z.x,worldOrigin.x));
            minY=Math.min(minY,minimum(box,x.y,y.y,z.y,worldOrigin.y));
            minZ=Math.min(minZ,minimum(box,x.z,y.z,z.z,worldOrigin.z));
            maxX=Math.max(maxX,maximum(box,x.x,y.x,z.x,worldOrigin.x));
            maxY=Math.max(maxY,maximum(box,x.y,y.y,z.y,worldOrigin.y));
            maxZ=Math.max(maxZ,maximum(box,x.z,y.z,z.z,worldOrigin.z));
        }
        return Optional.of(new AABB(minX,minY,minZ,maxX,maxY,maxZ));
    }

    private double minimum(AABB box,double a,double b,double c,double origin) {
        return origin + (lower(box.minX-localOrigin.x,box.maxX-localOrigin.x,a)
                + lower(box.minY-localOrigin.y,box.maxY-localOrigin.y,b)
                + lower(box.minZ-localOrigin.z,box.maxZ-localOrigin.z,c));
    }

    private double maximum(AABB box,double a,double b,double c,double origin) {
        return origin + (upper(box.minX-localOrigin.x,box.maxX-localOrigin.x,a)
                + upper(box.minY-localOrigin.y,box.maxY-localOrigin.y,b)
                + upper(box.minZ-localOrigin.z,box.maxZ-localOrigin.z,c));
    }

    private static double lower(double min, double max, double axis) { return (axis < 0 ? max : min) * axis; }
    private static double upper(double min, double max, double axis) { return (axis < 0 ? min : max) * axis; }
}
