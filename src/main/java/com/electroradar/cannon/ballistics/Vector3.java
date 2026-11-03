package com.electroradar.cannon.ballistics;

/**
 * Неизменяемый вектор решателя: положение в блоках, скорость в блоках за тик и т. п.
 * Существует, чтобы ядро не зависело от классов Minecraft; адаптер переводит его в {@code Vec3}.
 */
public record Vector3(double x, double y, double z) {
    public static final Vector3 ZERO = new Vector3(0.0D, 0.0D, 0.0D);

    public Vector3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }

    public Vector3 add(Vector3 other) {
        return new Vector3(x + other.x, y + other.y, z + other.z);
    }

    public Vector3 subtract(Vector3 other) {
        return new Vector3(x - other.x, y - other.y, z - other.z);
    }

    public Vector3 scale(double factor) {
        return new Vector3(x * factor, y * factor, z * factor);
    }

    public double dot(Vector3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
