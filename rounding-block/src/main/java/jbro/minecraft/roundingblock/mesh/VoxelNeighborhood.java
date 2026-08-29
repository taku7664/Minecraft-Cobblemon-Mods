package jbro.minecraft.roundingblock.mesh;

/** Immutable occupancy snapshot for the 3x3x3 blocks around one rendered block. */
public record VoxelNeighborhood(int bits) {
    private static final int VALID_BITS = (1 << 27) - 1;

    public VoxelNeighborhood {
        if ((bits & ~VALID_BITS) != 0) {
            throw new IllegalArgumentException("Voxel neighborhood uses exactly 27 bits");
        }
    }

    public boolean occupied(int x, int y, int z) {
        return (bits & bit(x, y, z)) != 0;
    }

    public VoxelNeighborhood withOccupied(int x, int y, int z) {
        return new VoxelNeighborhood(bits | bit(x, y, z));
    }

    public static VoxelNeighborhood empty() {
        return new VoxelNeighborhood(0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns true when occupancy changes along only one axis and is uniform
     * across both tangential axes. Such a neighborhood is a plane or slab and
     * needs no bevel geometry in the center block.
     */
    public boolean isAxisAlignedLayered() {
        for (int axis = 0; axis < 3; axis++) {
            boolean layered = true;
            for (int coordinate = -1; coordinate <= 1 && layered; coordinate++) {
                Boolean expected = null;
                for (int first = -1; first <= 1 && layered; first++) {
                    for (int second = -1; second <= 1; second++) {
                        int x = axis == 0 ? coordinate : first;
                        int y = axis == 1 ? coordinate : axis == 0 ? first : second;
                        int z = axis == 2 ? coordinate : second;
                        boolean value = occupied(x, y, z);
                        if (expected == null) {
                            expected = value;
                        } else if (value != expected) {
                            layered = false;
                            break;
                        }
                    }
                }
            }
            if (layered) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when every exposed center-block face is surrounded by a
     * complete 3x3 coplanar layer with empty space across that entire outside
     * layer. Any neighboring block beside the face can own a concave patch,
     * so such a face must stay in the complete custom surface.
     */
    public boolean allExposedFacesArePlanar() {
        if (!occupied(0, 0, 0)) {
            return false;
        }
        boolean exposed = false;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign : new int[]{-1, 1}) {
                if (occupiedAt(axis, sign, 0, 0)) {
                    continue;
                }
                exposed = true;
                if (!isPlanarFace(axis, sign)) {
                    return false;
                }
            }
        }
        return exposed;
    }

    /** Bit set of exposed faces that can be rendered by the original cube model. */
    public int planarFaceBits() {
        int result = 0;
        for (CubeFace face : CubeFace.values()) {
            if (!occupiedAt(face.axis(), face.sign(), 0, 0)
                && isPlanarFace(face.axis(), face.sign())) {
                result |= 1 << face.ordinal();
            }
        }
        return result;
    }

    private boolean isPlanarFace(int axis, int sign) {
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                if (!occupiedAt(axis, 0, first, second)
                    || occupiedAt(axis, sign, first, second)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean occupiedAt(int axis, int coordinate, int first, int second) {
        int x = axis == 0 ? coordinate : first;
        int y = axis == 1 ? coordinate : axis == 0 ? first : second;
        int z = axis == 2 ? coordinate : second;
        return occupied(x, y, z);
    }

    private static int bit(int x, int y, int z) {
        if (x < -1 || x > 1 || y < -1 || y > 1 || z < -1 || z > 1) {
            throw new IllegalArgumentException("Neighborhood coordinate outside -1..1: " + x + "," + y + "," + z);
        }
        return 1 << ((x + 1) + 3 * (y + 1) + 9 * (z + 1));
    }

    public static final class Builder {
        private int bits;

        public Builder occupy(int x, int y, int z) {
            bits |= bit(x, y, z);
            return this;
        }

        public VoxelNeighborhood build() {
            return new VoxelNeighborhood(bits);
        }
    }
}
