package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MicroVoxelMesherTest {
    private static final double EPSILON = 1.0e-6;

    @Test
    void verticalLayerSummaryMatchesEveryMicroShape() {
        for (int bits = 1; bits <= 0xFF; bits++) {
            MicroBlockShape shape = new MicroBlockShape(bits);
            int expected = (bits & 0x33) != 0 ? 1 : 0;
            if ((bits & 0xCC) != 0) {
                expected |= 2;
            }
            assertEquals(expected, shape.verticalLayerBits(), () -> "shape bits=" + shape.bits());
        }
    }

    @Test
    void complexityMatchesHorizontalUniformityForEveryMicroShape() {
        for (int bits = 1; bits <= 0xFF; bits++) {
            MicroBlockShape shape = new MicroBlockShape(bits);
            boolean expected = false;
            for (int y = 0; y <= 1; y++) {
                boolean first = shape.occupied(0, y, 0);
                for (int z = 0; z <= 1; z++) {
                    for (int x = 0; x <= 1; x++) {
                        expected |= shape.occupied(x, y, z) != first;
                    }
                }
            }
            assertEquals(expected, shape.isComplex(), () -> "shape bits=" + shape.bits());
        }
    }

    @Test
    void straightBottomStairUsesFourLowerAndTwoUpperCells() {
        MicroBlockShape stair = MicroBlockShape.builder()
            .occupy(0, 0, 0).occupy(1, 0, 0)
            .occupy(0, 0, 1).occupy(1, 0, 1)
            .occupy(0, 1, 0).occupy(1, 1, 0)
            .build();

        assertEquals(6, stair.occupiedCellCount());
        assertTrue(stair.isPartial());
        assertTrue(stair.isComplex());
        assertFalse(stair.occupied(0, 1, 1));
        assertFalse(stair.occupied(1, 1, 1));
    }

    @Test
    void microNeighborhoodMapsAdjacentBlocksWithoutLosingHalfCoordinates() {
        MicroBlockShape bottomSlab = MicroBlockShape.builder()
            .occupy(0, 0, 0).occupy(1, 0, 0)
            .occupy(0, 0, 1).occupy(1, 0, 1)
            .build();
        MicroVoxelNeighborhood neighborhood = MicroVoxelNeighborhood.builder()
            .occupyBlock(-1, 0, 0, MicroBlockShape.FULL)
            .occupyBlock(0, 0, 0, bottomSlab)
            .occupyBlock(1, 0, 0, MicroBlockShape.FULL)
            .build();

        assertTrue(neighborhood.occupied(-2, 0, 0));
        assertTrue(neighborhood.occupied(-1, 1, 1));
        assertTrue(neighborhood.occupied(0, 0, 0));
        assertFalse(neighborhood.occupied(0, 1, 0));
        assertTrue(neighborhood.occupied(2, 1, 1));
        assertTrue(neighborhood.occupied(3, 0, 0));
    }

    @Test
    void microNeighborhoodBuilderPreservesAllFourBitWords() {
        MicroVoxelNeighborhood.Builder builder = MicroVoxelNeighborhood.builder();
        for (int z = -2; z <= 3; z++) {
            for (int y = -2; y <= 3; y++) {
                for (int x = -2; x <= 3; x++) {
                    builder.occupy(x, y, z);
                }
            }
        }
        MicroVoxelNeighborhood neighborhood = builder.build();
        for (int z = -2; z <= 3; z++) {
            for (int y = -2; y <= 3; y++) {
                for (int x = -2; x <= 3; x++) {
                    assertTrue(neighborhood.occupied(x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void isolatedStairMeshesInsideItsBlockAndKeepsBothStepHeights() {
        MicroBlockShape stair = MicroBlockShape.builder()
            .occupy(0, 0, 0).occupy(1, 0, 0)
            .occupy(0, 0, 1).occupy(1, 0, 1)
            .occupy(0, 1, 0).occupy(1, 1, 0)
            .build();
        MicroVoxelNeighborhood neighborhood = MicroVoxelNeighborhood.builder()
            .occupyBlock(0, 0, 0, stair)
            .build();

        MeshPlan plan = new RoundedVoxelMesher().mesh(neighborhood);

        assertFalse(plan.primitives().isEmpty());
        assertTrue(plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .allMatch(vertex -> vertex.position().x() >= -EPSILON && vertex.position().x() <= 1.0 + EPSILON
                && vertex.position().y() >= -EPSILON && vertex.position().y() <= 1.0 + EPSILON
                && vertex.position().z() >= -EPSILON && vertex.position().z() <= 1.0 + EPSILON));
        assertTrue(plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .anyMatch(vertex -> Math.abs(vertex.position().y() - 0.5) <= EPSILON));
        assertTrue(plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .anyMatch(vertex -> Math.abs(vertex.position().y() - 1.0) <= EPSILON));
    }

    @Test
    void fullBlockAndStairShareOneMicroLatticeAtTheirContact() {
        MicroBlockShape stair = MicroBlockShape.builder()
            .occupy(0, 0, 0).occupy(1, 0, 0)
            .occupy(0, 0, 1).occupy(1, 0, 1)
            .occupy(0, 1, 0).occupy(1, 1, 0)
            .build();
        MicroVoxelNeighborhood neighborhood = MicroVoxelNeighborhood.builder()
            .occupyBlock(0, 0, 0, stair)
            .occupyBlock(0, 0, -1, MicroBlockShape.FULL)
            .build();

        MeshPlan plan = new RoundedVoxelMesher().mesh(neighborhood);

        assertFalse(plan.primitives().isEmpty());
        assertFalse(plan.primitives().stream().anyMatch(primitive ->
            primitive.materialFace() == CubeFace.NORTH
                && primitive.vertices().stream().allMatch(vertex ->
                    Math.abs(vertex.position().z()) <= EPSILON
                        && vertex.position().y() >= 0.5 - EPSILON)
        ));
    }
}
