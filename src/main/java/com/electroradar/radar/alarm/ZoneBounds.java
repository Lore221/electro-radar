package com.electroradar.radar.alarm;

import com.electroradar.cannon.ballistics.Vector3;

/** Координаты включённых блоков и операции редактора; независимы от Minecraft. */
public record ZoneBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static final int MAX_SIDE = 2_048;
    private static final int WORLD_LIMIT = 29_999_984;

    public ZoneBounds {
        int x = minX, y = minY, z = minZ;
        minX = Math.min(x, maxX); minY = Math.min(y, maxY); minZ = Math.min(z, maxZ);
        maxX = Math.max(x, maxX); maxY = Math.max(y, maxY); maxZ = Math.max(z, maxZ);
        if ((long) maxX - minX >= MAX_SIDE || (long) maxY - minY >= MAX_SIDE || (long) maxZ - minZ >= MAX_SIDE
                || Math.abs((long) minX) > WORLD_LIMIT || Math.abs((long) maxX) > WORLD_LIMIT
                || Math.abs((long) minZ) > WORLD_LIMIT || Math.abs((long) maxZ) > WORLD_LIMIT
                || minY < -2_048 || maxY > 2_047) throw new IllegalArgumentException("Zone outside limits");
    }

    public int width() { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public ProtectionBox geometry() {
        return new ProtectionBox(new Vector3(minX, minY, minZ), new Vector3(maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D));
    }

    /** axis: X=0/Y=1/Z=2; direction: нижняя грань=-1, верхняя=1; step: расширение=1. */
    public ZoneBounds resize(int axis, int direction, int step) {
        if (axis < 0 || axis > 2 || (direction != -1 && direction != 1) || (step != -1 && step != 1)) {
            throw new IllegalArgumentException("Invalid resize");
        }
        int[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        if (direction < 0) lo[axis] -= step;
        else hi[axis] += step;
        if (lo[axis] > hi[axis]) return this;
        return new ZoneBounds(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }
}
