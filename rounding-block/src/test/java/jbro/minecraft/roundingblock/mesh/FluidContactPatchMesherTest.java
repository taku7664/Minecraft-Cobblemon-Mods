package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

final class FluidContactPatchMesherTest {
    private static final double EPSILON = 1.0e-7;

    private final RoundedVoxelMesher solidMesher = new RoundedVoxelMesher(3.0 / 32.0, 5);
    private final FluidContactPatchMesher patchMesher = new FluidContactPatchMesher();

    @Test
    void lowWaterOnlyFillsTheVerticalCornerRecesses() {
        MeshPlan solid = solidMesher.mesh(isolatedCenter());

        MeshPlan patch = patchMesher.mesh(solid, CubeFace.WEST, 0.50, 0.50);

        assertFalse(patch.primitives().isEmpty());
        assertFalse(hasWestInsetNearEdgeCenter(patch));
    }

    @Test
    void highWaterFillsOnlyTheRoundedContactRecess() {
        MeshPlan solid = solidMesher.mesh(isolatedCenter());

        MeshPlan patch = patchMesher.mesh(solid, CubeFace.WEST, 0.95, 0.95);

        assertFalse(patch.primitives().isEmpty());
        assertTrue(hasWestInsetNearEdgeCenter(patch));
        patch.primitives().forEach(primitive -> primitive.vertices().forEach(vertex -> {
            Vec3 position = vertex.position();
            assertTrue(position.x() >= -EPSILON && position.x() <= 0.5 + EPSILON);
            assertTrue(position.z() >= -EPSILON && position.z() <= 1.0 + EPSILON);
            assertTrue(position.x() <= position.z() + EPSILON);
            assertTrue(position.x() <= 1.0 - position.z() + EPSILON);
            assertTrue(Math.abs(position.y() - 0.95) <= EPSILON);
            assertTrue(vertex.normal().subtract(CubeFace.UP.normal()).length() <= EPSILON);
        }));
    }

    @Test
    void allHorizontalOwnersProduceFiniteNonOverlappingSectors() {
        MeshPlan solid = solidMesher.mesh(isolatedCenter());

        for (CubeFace face : EnumSet.of(CubeFace.WEST, CubeFace.EAST, CubeFace.NORTH, CubeFace.SOUTH)) {
            MeshPlan patch = patchMesher.mesh(solid, face, 0.96, 0.96);
            assertFalse(patch.primitives().isEmpty(), face.name());
            patch.primitives().forEach(primitive -> primitive.vertices().forEach(vertex -> {
                assertTrue(Double.isFinite(vertex.position().x()));
                assertTrue(Double.isFinite(vertex.position().y()));
                assertTrue(Double.isFinite(vertex.position().z()));
                assertTrue(ownedBy(face, vertex.position()), face + " does not own " + vertex.position());
            }));
        }
    }

    @Test
    void connectedBlockAboveRemovesOnlyTheTopSideRecess() {
        VoxelNeighborhood column = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(0, 1, 0)
            .build();
        MeshPlan solid = solidMesher.mesh(column);

        MeshPlan patch = patchMesher.mesh(solid, CubeFace.WEST, 0.95, 0.95);

        assertFalse(patch.primitives().isEmpty());
        assertFalse(hasWestInsetNearEdgeCenter(patch));
    }

    @Test
    void slopedWaterInterpolatesBetweenBothSharedEdgeHeights() {
        MeshPlan solid = solidMesher.mesh(isolatedCenter());

        MeshPlan patch = patchMesher.mesh(solid, CubeFace.WEST, 0.93, 0.98);

        assertFalse(patch.primitives().isEmpty());
        patch.primitives().forEach(primitive -> primitive.vertices().forEach(vertex -> {
            double expected = 0.93 + 0.05 * vertex.position().z();
            assertTrue(Math.abs(vertex.position().y() - expected) <= 1.0e-6);
        }));
    }

    @Test
    void everyFluidPrimitiveIsAQuadForTheFluidVertexBuffer() {
        for (RoundedVoxelMesher mesher : new RoundedVoxelMesher[]{
            solidMesher,
            new RoundedVoxelMesher(0.15, 3)
        }) {
            MeshPlan solid = mesher.mesh(isolatedCenter());
            for (double[] heights : new double[][]{
                {0.50, 0.50}, {0.93, 0.98}, {0.999, 0.999}, {0.75, 0.999}
            }) {
                for (CubeFace face : EnumSet.of(CubeFace.WEST, CubeFace.EAST, CubeFace.NORTH, CubeFace.SOUTH)) {
                    MeshPlan patch = patchMesher.mesh(solid, face, heights[0], heights[1]);
                    assertFalse(patch.primitives().isEmpty(), face.name());
                    patch.primitives().forEach(primitive ->
                        assertEquals(4, primitive.vertices().size(), face + " emitted a non-quad primitive")
                    );
                }
            }
        }
    }

    @Test
    void sourceWaterCoversTheTinyDiagonalCornerRecesses() {
        MeshPlan solid = new RoundedVoxelMesher(0.15, 3).mesh(isolatedCenter());
        List<MeshPrimitive> combined = new ArrayList<>();
        for (CubeFace face : EnumSet.of(CubeFace.WEST, CubeFace.EAST, CubeFace.NORTH, CubeFace.SOUTH)) {
            combined.addAll(patchMesher.mesh(solid, face, 0.999, 0.999).primitives());
        }

        for (double offset : new double[]{0.005, 0.01, 0.02, 0.03, 0.04}) {
            assertTrue(covers(combined, 1.0 - offset, offset), "uncovered north-east corner at " + offset);
            assertTrue(covers(combined, offset, offset), "uncovered north-west corner at " + offset);
            assertTrue(covers(combined, 1.0 - offset, 1.0 - offset), "uncovered south-east corner at " + offset);
            assertTrue(covers(combined, offset, 1.0 - offset), "uncovered south-west corner at " + offset);
        }
    }

    private static VoxelNeighborhood isolatedCenter() {
        return VoxelNeighborhood.builder().occupy(0, 0, 0).build();
    }

    private static boolean hasWestInsetNearEdgeCenter(MeshPlan patch) {
        return patch.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .map(MeshVertex::position)
            .anyMatch(position -> position.x() > 1.0e-4 && position.z() > 0.25 && position.z() < 0.75);
    }

    private static boolean ownedBy(CubeFace face, Vec3 position) {
        double west = position.x();
        double east = 1.0 - position.x();
        double north = position.z();
        double south = 1.0 - position.z();
        double own = switch (face) {
            case WEST -> west;
            case EAST -> east;
            case NORTH -> north;
            case SOUTH -> south;
            default -> throw new IllegalArgumentException("Horizontal face required");
        };
        return own <= west + EPSILON
            && own <= east + EPSILON
            && own <= north + EPSILON
            && own <= south + EPSILON;
    }

    private static boolean covers(List<MeshPrimitive> primitives, double x, double z) {
        return primitives.stream().anyMatch(primitive -> {
            List<MeshVertex> vertices = primitive.vertices();
            return insideTriangle(vertices.get(0).position(), vertices.get(1).position(), vertices.get(2).position(), x, z)
                || insideTriangle(vertices.get(0).position(), vertices.get(2).position(), vertices.get(3).position(), x, z);
        });
    }

    private static boolean insideTriangle(Vec3 a, Vec3 b, Vec3 c, double x, double z) {
        double first = cross(a, b, x, z);
        double second = cross(b, c, x, z);
        double third = cross(c, a, x, z);
        return (first >= -EPSILON && second >= -EPSILON && third >= -EPSILON)
            || (first <= EPSILON && second <= EPSILON && third <= EPSILON);
    }

    private static double cross(Vec3 a, Vec3 b, double x, double z) {
        return (b.x() - a.x()) * (z - a.z()) - (b.z() - a.z()) * (x - a.x());
    }

}
