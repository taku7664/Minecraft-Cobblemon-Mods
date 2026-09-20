package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class VoxelNeighborhoodTest {
    @Test
    void layeredFastPathMatchesCoordinateReference() {
        Random random = new Random(0x524F554E444544L);
        for (int sample = 0; sample < 10_000; sample++) {
            VoxelNeighborhood neighborhood = new VoxelNeighborhood(random.nextInt(1 << 27));
            assertEquals(
                coordinateReference(neighborhood),
                neighborhood.isAxisAlignedLayered(),
                () -> "bits=" + neighborhood.bits()
            );
        }
    }

    private static boolean coordinateReference(VoxelNeighborhood neighborhood) {
        for (int axis = 0; axis < 3; axis++) {
            boolean layered = true;
            for (int coordinate = -1; coordinate <= 1 && layered; coordinate++) {
                Boolean expected = null;
                for (int first = -1; first <= 1 && layered; first++) {
                    for (int second = -1; second <= 1; second++) {
                        int x = axis == 0 ? coordinate : first;
                        int y = axis == 1 ? coordinate : axis == 0 ? first : second;
                        int z = axis == 2 ? coordinate : second;
                        boolean value = neighborhood.occupied(x, y, z);
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
}
