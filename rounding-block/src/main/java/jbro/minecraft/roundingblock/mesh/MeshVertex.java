package jbro.minecraft.roundingblock.mesh;

public record MeshVertex(Vec3 position, Vec3 normal) {
    public MeshVertex {
        if (position == null || normal == null) {
            throw new IllegalArgumentException("position and normal are required");
        }
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z())) {
            throw new IllegalArgumentException("position must be finite");
        }
        double normalLength = normal.length();
        if (!Double.isFinite(normalLength) || Math.abs(normalLength - 1.0) > 1.0e-6) {
            throw new IllegalArgumentException("normal must be a finite unit vector: " + normal);
        }
    }
}
