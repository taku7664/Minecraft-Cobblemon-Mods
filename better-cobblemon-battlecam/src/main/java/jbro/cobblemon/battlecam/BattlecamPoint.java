package jbro.cobblemon.battlecam;

public record BattlecamPoint(double x, double y, double z) {
    BattlecamPoint add(BattlecamPoint other) {
        return new BattlecamPoint(x + other.x, y + other.y, z + other.z);
    }

    BattlecamPoint subtract(BattlecamPoint other) {
        return new BattlecamPoint(x - other.x, y - other.y, z - other.z);
    }

    BattlecamPoint multiply(double scale) {
        return new BattlecamPoint(x * scale, y * scale, z * scale);
    }

    double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    BattlecamPoint horizontalUnitOr(BattlecamPoint fallback) {
        double length = horizontalLength();
        return length < 0.0001 ? fallback : new BattlecamPoint(x / length, 0.0, z / length);
    }

    boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
