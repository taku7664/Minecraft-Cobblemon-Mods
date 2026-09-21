package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagonalContactPatchMesherTest {
    private static final double EPSILON = 1.0e-6;
    private final DiagonalContactPatchMesher mesher = new DiagonalContactPatchMesher(3.0 / 32.0);

    @Test
    void edgeOnlyContactRestoresFacesToTheSharedLineWithoutLeavingTheBlock() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 1)
            .build();

        MeshPlan patches = mesher.mesh(neighborhood);

        assertEquals(6, patches.primitives().size());
        assertInsideCenterBlock(patches);
        assertTrue(positions(patches).stream().anyMatch(position ->
            near(position.x(), 1.0) && near(position.z(), 1.0)
        ));
        assertTrue(positions(patches).stream().anyMatch(position ->
            near(position.x(), 1.0) && near(position.y(), 0.5) && near(position.z(), 1.0)
        ), "patches must cover the middle of the shared edge");
        double radius = 3.0 / 32.0;
        assertTrue(positions(patches).stream().allMatch(position ->
            position.x() >= 1.0 - radius - EPSILON && position.z() >= 1.0 - radius - EPSILON
        ), "patches may extend along the shared edge, but not sideways into the empty quadrants");
        assertAxisAligned(patches);
    }

    @Test
    void cornerOnlyContactRestoresTheOriginalCornerWithoutAProtrudingSphere() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 1, 1)
            .build();

        MeshPlan patches = mesher.mesh(neighborhood);

        assertEquals(3, patches.primitives().size());
        assertInsideCenterBlock(patches);
        assertTrue(positions(patches).stream().anyMatch(position ->
            near(position.x(), 1.0) && near(position.y(), 1.0) && near(position.z(), 1.0)
        ));
        double radius = 3.0 / 32.0;
        assertTrue(positions(patches).stream().allMatch(position ->
            position.x() >= 1.0 - radius - EPSILON
                && position.y() >= 1.0 - radius - EPSILON
                && position.z() >= 1.0 - radius - EPSILON
        ), "corner-only patches must remain local to the original corner");
        assertAxisAligned(patches);
    }

    @Test
    void faceConnectedDetourDoesNotReceiveAnExtraPatch() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 0)
            .occupy(1, 0, 1)
            .build();

        assertTrue(mesher.mesh(neighborhood).primitives().isEmpty());
    }

    @Test
    void negativeDiagonalRestoresTheCurrentBlocksOwnCorner() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(-1, 0, -1)
            .build();

        MeshPlan patches = mesher.mesh(neighborhood);

        assertFalse(patches.primitives().isEmpty());
        assertInsideCenterBlock(patches);
        assertTrue(positions(patches).stream().anyMatch(position ->
            near(position.x(), 0.0) && near(position.z(), 0.0)
        ));
    }

    @Test
    void roundedVoxelMesherKeepsContactPatchesDuringPlanarFaceReplacement() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 1)
            .build();
        MeshPlan patches = mesher.mesh(neighborhood);

        MeshPlan composed = new RoundedVoxelMesher()
            .mesh(neighborhood)
            .withoutPlanarFaces(neighborhood.planarFaceBits());

        assertTrue(composed.primitives().containsAll(patches.primitives()));
        assertInsideCenterBlock(patches);
    }

    private static void assertInsideCenterBlock(MeshPlan plan) {
        assertTrue(positions(plan).stream().allMatch(position ->
            position.x() >= -EPSILON && position.x() <= 1.0 + EPSILON
                && position.y() >= -EPSILON && position.y() <= 1.0 + EPSILON
                && position.z() >= -EPSILON && position.z() <= 1.0 + EPSILON
        ));
    }

    private static void assertAxisAligned(MeshPlan plan) {
        assertTrue(plan.primitives().stream().flatMap(primitive -> primitive.vertices().stream()).allMatch(vertex -> {
            Vec3 normal = vertex.normal();
            int alignedAxes = 0;
            alignedAxes += near(Math.abs(normal.x()), 1.0) ? 1 : 0;
            alignedAxes += near(Math.abs(normal.y()), 1.0) ? 1 : 0;
            alignedAxes += near(Math.abs(normal.z()), 1.0) ? 1 : 0;
            return alignedAxes == 1;
        }));
    }

    private static List<Vec3> positions(MeshPlan plan) {
        return plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .map(MeshVertex::position)
            .toList();
    }

    private static boolean near(double first, double second) {
        return Math.abs(first - second) <= EPSILON;
    }
}
