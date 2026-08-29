package jbro.minecraft.roundingblock.mesh;

public record ExposureMask(int bits) {
    private static final int ALL_BITS = (1 << CubeFace.values().length) - 1;

    public ExposureMask {
        if ((bits & ~ALL_BITS) != 0) {
            throw new IllegalArgumentException("Unknown exposure bits: " + bits);
        }
    }

    public boolean exposed(CubeFace face) {
        return (bits & (1 << face.ordinal())) != 0;
    }

    public static ExposureMask none() {
        return new ExposureMask(0);
    }

    public static ExposureMask all() {
        return new ExposureMask(ALL_BITS);
    }

    public static ExposureMask of(CubeFace... faces) {
        int bits = 0;
        for (CubeFace face : faces) {
            bits |= 1 << face.ordinal();
        }
        return new ExposureMask(bits);
    }
}
