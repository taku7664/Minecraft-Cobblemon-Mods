package jbro.minecraft.roundingblock.mesh;

/** Occupancy of the eight half-sized cells inside one Minecraft block. */
public record MicroBlockShape(int bits) {
    private static final int VALID_BITS = 0xFF;

    public static final MicroBlockShape FULL = new MicroBlockShape(VALID_BITS);
    public static final MicroBlockShape BOTTOM_HALF = new MicroBlockShape(0x33);
    public static final MicroBlockShape TOP_HALF = new MicroBlockShape(0xCC);

    public MicroBlockShape {
        if ((bits & ~VALID_BITS) != 0 || bits == 0) {
            throw new IllegalArgumentException("Micro block shape must occupy 1..8 cells: " + bits);
        }
    }

    public boolean occupied(int x, int y, int z) {
        return (bits & bit(x, y, z)) != 0;
    }

    public int occupiedCellCount() {
        return Integer.bitCount(bits);
    }

    /** Bit 0/1 indicate whether the lower/upper half contains any solid cell. */
    public int verticalLayerBits() {
        int layers = (bits & 0x33) == 0 ? 0 : 1;
        return (bits & 0xCC) == 0 ? layers : layers | 2;
    }

    public boolean isPartial() {
        return bits != VALID_BITS;
    }

    /** True when occupancy varies horizontally inside either vertical layer. */
    public boolean isComplex() {
        int lower = bits & 0x33;
        int upper = bits & 0xCC;
        return lower != 0 && lower != 0x33 || upper != 0 && upper != 0xCC;
    }

    public VerticalBlockShape verticalProfile() {
        if (this.equals(FULL)) {
            return VerticalBlockShape.FULL;
        }
        if (this.equals(BOTTOM_HALF)) {
            return VerticalBlockShape.BOTTOM_HALF;
        }
        if (this.equals(TOP_HALF)) {
            return VerticalBlockShape.TOP_HALF;
        }
        throw new IllegalStateException("Complex shape has no vertical-only profile: " + bits);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static int bit(int x, int y, int z) {
        if (x < 0 || x > 1 || y < 0 || y > 1 || z < 0 || z > 1) {
            throw new IllegalArgumentException("Micro cell coordinate must be 0 or 1: " + x + "," + y + "," + z);
        }
        return 1 << (x + 2 * y + 4 * z);
    }

    public static final class Builder {
        private int bits;

        public Builder occupy(int x, int y, int z) {
            bits |= bit(x, y, z);
            return this;
        }

        public MicroBlockShape build() {
            return new MicroBlockShape(bits);
        }
    }
}
