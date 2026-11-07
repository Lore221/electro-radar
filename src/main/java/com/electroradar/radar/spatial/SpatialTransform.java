package com.electroradar.radar.spatial;

import java.util.List;
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
        return equals(IDENTITY);
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
        List<Vec3> corners = corners(box);
        Vec3 first = corners.getFirst();
        double minX = first.x, minY = first.y, minZ = first.z;
        double maxX = minX, maxY = minY, maxZ = minZ;
        for (Vec3 p : corners) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
