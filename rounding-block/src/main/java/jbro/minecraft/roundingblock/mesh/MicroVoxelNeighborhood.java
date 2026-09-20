package jbro.minecraft.roundingblock.mesh;

/** Immutable 6x6x6 half-cell lattice covering the 3x3x3 neighboring blocks. */
public record MicroVoxelNeighborhood(long bits0, long bits1, long bits2, long bits3) {
    private static final long VALID_LAST_BITS = (1L << 24) - 1L;
    public static final MicroVoxelNeighborhood EMPTY = new MicroVoxelNeighborhood(0L, 0L, 0L, 0L);

    public MicroVoxelNeighborhood {
        if ((bits3 & ~VALID_LAST_BITS) != 0L) {
            throw new IllegalArgumentException("Micro voxel neighborhood uses exactly 216 bits");
        }
    }

    public boolean occupied(int x, int y, int z) {
        int index = index(x, y, z);
        long word = switch (index >>> 6) {
            case 0 -> bits0;
            case 1 -> bits1;
            case 2 -> bits2;
            case 3 -> bits3;
            default -> throw new IllegalStateException("Unexpected micro voxel word");
        };
        return (word & (1L << (index & 63))) != 0L;
    }

    public boolean hasOccupiedCenterCell() {
        for (int z = 0; z <= 1; z++) {
            for (int y = 0; y <= 1; y++) {
                for (int x = 0; x <= 1; x++) {
                    if (occupied(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static int index(int x, int y, int z) {
        if (x < -2 || x > 3 || y < -2 || y > 3 || z < -2 || z > 3) {
            throw new IllegalArgumentException(
                "Micro neighborhood coordinate outside -2..3: " + x + "," + y + "," + z
            );
        }
        return (x + 2) + 6 * (y + 2) + 36 * (z + 2);
    }

    public static final class Builder {
        private long bits0;
        private long bits1;
        private long bits2;
        private long bits3;

        public Builder occupy(int x, int y, int z) {
            int index = index(x, y, z);
            occupyIndex(index);
            return this;
        }

        public Builder occupyBlock(int blockX, int blockY, int blockZ, MicroBlockShape shape) {
            if (blockX < -1 || blockX > 1 || blockY < -1 || blockY > 1 || blockZ < -1 || blockZ > 1) {
                throw new IllegalArgumentException("Block coordinate outside -1..1");
            }
            int baseX = 2 * blockX;
            int baseY = 2 * blockY;
            int baseZ = 2 * blockZ;
            int cells = shape.bits();
            while (cells != 0) {
                int cell = Integer.numberOfTrailingZeros(cells);
                int x = cell & 1;
                int y = (cell >>> 1) & 1;
                int z = (cell >>> 2) & 1;
                occupyIndex(
                    (baseX + x + 2) + 6 * (baseY + y + 2) + 36 * (baseZ + z + 2)
                );
                cells &= cells - 1;
            }
            return this;
        }

        private void occupyIndex(int index) {
            long bit = 1L << (index & 63);
            switch (index >>> 6) {
                case 0 -> bits0 |= bit;
                case 1 -> bits1 |= bit;
                case 2 -> bits2 |= bit;
                case 3 -> bits3 |= bit;
                default -> throw new IllegalStateException("Unexpected micro voxel word");
            }
        }

        public MicroVoxelNeighborhood build() {
            return new MicroVoxelNeighborhood(bits0, bits1, bits2, bits3);
        }
    }
}
