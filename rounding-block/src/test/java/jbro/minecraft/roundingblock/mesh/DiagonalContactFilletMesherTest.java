package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagonalContactFilletMesherTest {
    private static final double EPSILON = 1.0e-6;
    private final DiagonalContactFilletMesher mesher = new DiagonalContactFilletMesher(3.0 / 32.0, 3);

    @Test
    void edgeDiagonalUsesAPointedRoundedBoundaryInsteadOfAFlatShelf() {
        MeshPlan plan = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 1)
            .build());

        assertFalse(plan.primitives().isEmpty());
        assertTrue(positions(plan).stream().allMatch(position ->
            position.x() >= -EPSILON && position.x() <= 1.0 + EPSILON
                && position.y() >= -EPSILON && position.y() <= 1.0 + EPSILON
                && position.z() >= -EPSILON && position.z() <= 1.0 + EPSILON
        ), "an edge diagonal must not also grow vertex tips at both ends");
        assertTrue(positions(plan).stream().filter(position -> near(position.y(), 0.5)).allMatch(position ->
            position.x() >= 1.0 - 0.51 * (3.0 / 32.0) - EPSILON
                && position.z() >= 1.0 - 0.51 * (3.0 / 32.0) - EPSILON
        ), "the contact must stay a narrow tip around the middle of the existing rounded edge");
        assertTrue(positions(plan).stream().anyMatch(position ->
            near(position.x(), 1.0) && near(position.y(), 0.5) && near(position.z(), 1.0)
        ), "the curved boundary must touch the shared edge");
        assertTrue(plan.primitives().stream().flatMap(primitive -> primitive.vertices().stream()).anyMatch(vertex ->
            Math.abs(vertex.normal().x()) > 0.2 && Math.abs(vertex.normal().z()) > 0.2
        ), "the contact boundary must carry diagonal curved normals");
        assertFalse(plan.primitives().stream().anyMatch(primitive ->
            primitive.vertices().stream().allMatch(vertex ->
                near(vertex.position().x(), 1.0) && near(Math.abs(vertex.normal().x()), 1.0)
            ) && primitive.vertices().stream().map(vertex -> vertex.position().z()).distinct().count() > 1
        ), "the contact must not restore a flat face all the way to the shared edge");
    }

    @Test
    void faceConnectedNeighborDoesNotReceiveAnExtraFillet() {
        MeshPlan plan = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 0)
            .occupy(1, 0, 1)
            .build());

        assertTrue(plan.primitives().isEmpty());
    }

    @Test
    void vertexDiagonalUsesTwoTangentTipsJoinedByATinySharedThroat() {
        MeshPlan positive = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 1, 1)
            .build());
        MeshPlan negative = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(-1, -1, -1)
            .build());

        assertFalse(positive.primitives().isEmpty());
        assertFalse(negative.primitives().isEmpty());
        double radius = 3.0 / 32.0;
        double throat = radius * 0.08;
        assertTrue(positions(positive).stream().anyMatch(position ->
            Math.abs(position.subtract(new Vec3(1.0, 1.0, 1.0)).length() - throat) <= EPSILON
        ));
        assertTrue(positions(negative).stream().anyMatch(position ->
            Math.abs(position.subtract(Vec3.ZERO).length() - throat) <= EPSILON
        ));
        Vec3 diagonal = new Vec3(-1.0, -1.0, -1.0).normalize();
        Vec3 apex = new Vec3(1.0, 1.0, 1.0);
        assertTrue(positions(positive).stream().anyMatch(position -> {
            Vec3 offset = position.subtract(apex);
            double axial = offset.dot(diagonal);
            double radial = offset.subtract(diagonal.multiply(axial)).length();
            return Math.abs(axial) <= EPSILON
                && radial >= throat * 0.99
                && radial <= throat * 1.01;
        }), "the two tips need a finite shared throat so rasterization cannot expose the background");
        assertTrue(positions(positive).stream().allMatch(position ->
            position.x() >= -throat - EPSILON && position.x() <= 1.0 + throat + EPSILON
                && position.y() >= -throat - EPSILON && position.y() <= 1.0 + throat + EPSILON
                && position.z() >= -throat - EPSILON && position.z() <= 1.0 + throat + EPSILON
        ), "only the tiny shared throat may cross the original block boundary");
        assertTrue(positions(positive).stream().anyMatch(position -> {
            Vec3 offset = position.subtract(apex);
            double axial = offset.dot(diagonal);
            double radial = offset.subtract(diagonal.multiply(axial)).length();
            return axial >= radius * 1.14
                && axial <= radius * 1.17
                && radial >= radius * 0.80;
        }), "the tip must widen to the rounded corner's tangent ring instead of leaving a star-shaped hole");
        assertTrue(positions(positive).stream().allMatch(position -> {
            Vec3 offset = position.subtract(apex);
            double axial = offset.dot(diagonal);
            double radial = offset.subtract(diagonal.multiply(axial)).length();
            return radial <= throat + axial / Math.sqrt(2.0) + EPSILON;
        }), "the fill must stay inside the tangent cone and never become a ball or branch");
    }

    @Test
    void disconnectedThreeBlockVertexUsesOnlyItsEdgeFillet() {
        MeshPlan isolatedComponent = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(0, -1, 1)
            .occupy(1, -1, 1)
            .build());

        assertFalse(isolatedComponent.primitives().isEmpty());
        assertTrue(positions(isolatedComponent).stream().allMatch(position ->
            position.x() >= -EPSILON && position.x() <= 1.0 + EPSILON
                && position.y() >= -EPSILON && position.y() <= 1.0 + EPSILON
                && position.z() >= -EPSILON && position.z() <= 1.0 + EPSILON
        ), "a three-block edge contact must not receive a duplicate vertex star");
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
