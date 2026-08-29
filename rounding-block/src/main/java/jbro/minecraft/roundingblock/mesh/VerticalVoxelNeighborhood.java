package jbro.minecraft.roundingblock.mesh;

/**
 * Immutable 3x3-block occupancy snapshot with two vertical cells per block.
 * Half-height Y coordinates -2..3 cover the blocks directly below, at, and
 * above the rendered block.
 */
public record VerticalVoxelNeighborhood(long bits) {
    private static final long VALID_BITS = (1L << 54) - 1L;

    public VerticalVoxelNeighborhood {
        if ((bits & ~VALID_BITS) != 0L) {
            throw new IllegalArgumentException("Vertical voxel neighborhood uses exactly 54 bits");
        }
    }

    public boolean occupied(int x, int halfY, int z) {
        return (bits & bit(x, halfY, z)) != 0L;
    }

    public VerticalVoxelNeighborhood withOccupied(int x, int halfY, int z) {
        return new VerticalVoxelNeighborhood(bits | bit(x, halfY, z));
    }

    public boolean hasOccupiedCenterCell() {
        return occupied(0, 0, 0) || occupied(0, 1, 0);
    }

    /** Returns true when occupancy changes only between horizontal half-layers. */
    public boolean isHorizontallyLayered() {
        for (int halfY = -2; halfY <= 3; halfY++) {
            boolean expected = occupied(-1, halfY, -1);
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    if (occupied(x, halfY, z) != expected) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static VerticalVoxelNeighborhood empty() {
        return new VerticalVoxelNeighborhood(0L);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static long bit(int x, int halfY, int z) {
        if (x < -1 || x > 1 || halfY < -2 || halfY > 3 || z < -1 || z > 1) {
            throw new IllegalArgumentException(
                "Vertical neighborhood coordinate outside x/z=-1..1, halfY=-2..3: "
                    + x + "," + halfY + "," + z
            );
        }
        return 1L << ((x + 1) + 3 * (halfY + 2) + 18 * (z + 1));
    }

    public static final class Builder {
        private long bits;

        public Builder occupy(int x, int halfY, int z) {
            bits |= bit(x, halfY, z);
            return this;
        }

        public VerticalVoxelNeighborhood build() {
            return new VerticalVoxelNeighborhood(bits);
        }
    }
}
