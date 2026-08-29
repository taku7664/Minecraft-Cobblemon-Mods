package jbro.minecraft.roundingblock.mesh;

public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 multiply(double scale) {
        return new Vec3(x * scale, y * scale, z * scale);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        );
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public Vec3 normalize() {
        double length = length();
        if (!(length > 0.0) || !Double.isFinite(length)) {
            throw new IllegalStateException("Cannot normalize vector " + this);
        }
        return multiply(1.0 / length);
    }

    public double component(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IllegalArgumentException("axis must be 0, 1, or 2");
        };
    }

    public Vec3 withComponent(int axis, double value) {
        return switch (axis) {
            case 0 -> new Vec3(value, y, z);
            case 1 -> new Vec3(x, value, z);
            case 2 -> new Vec3(x, y, value);
            default -> throw new IllegalArgumentException("axis must be 0, 1, or 2");
        };
    }
}
