package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagonalContactEndpointMesherTest {
    private static final double EPSILON = 1.0e-6;
    private static final double RADIUS = 3.0 / 32.0;
    private final DiagonalContactEndpointMesher mesher = new DiagonalContactEndpointMesher(RADIUS, 3);

    @Test
    void edgeContactIsLeftToTheInsideBlockPatches() {
        MeshPlan plan = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 1)
            .build());

        assertTrue(plan.primitives().isEmpty(),
            "edge contacts must not add an exposed cap outside the two blocks");
    }

    @Test
    void vertexContactUsesTangentCornerTips() {
        MeshPlan plan = mesher.mesh(VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 1, 1)
            .build());

        assertFalse(plan.primitives().isEmpty());
        Vec3 corner = new Vec3(1.0, 1.0, 1.0);
        Vec3 diagonal = new Vec3(1.0, 1.0, 1.0).normalize();
        List<Vec3> throat = positions(plan).stream()
            .filter(position -> Math.abs(position.subtract(corner).dot(diagonal)) <= EPSILON)
            .toList();
        List<Vec3> embeddedEnds = positions(plan).stream()
            .filter(position -> Math.abs(position.subtract(corner).dot(diagonal)) >= RADIUS * 1.10)
            .toList();

        assertFalse(throat.isEmpty());
        assertTrue(throat.stream().allMatch(position ->
            distanceFromBodyDiagonal(position, corner) <= EPSILON
        ), "the exposed contact must meet at a point rather than form a bead");
        assertFalse(embeddedEnds.isEmpty());
        assertTrue(embeddedEnds.stream().anyMatch(position ->
            distanceFromBodyDiagonal(position, corner) >= RADIUS * 0.80
        ), "each tip must reach the tangent ring of the existing rounded corner");
        assertTrue(positions(plan).stream().allMatch(position ->
            Math.abs(position.subtract(corner).dot(diagonal)) <= RADIUS * 1.20 + EPSILON
        ), "the tip must stop at the rounded-corner tangent ring instead of forming a long pillar");
        assertTrue(positions(plan).stream().allMatch(position ->
            insideCenterBlock(position, corner) || insidePositiveDiagonalBlock(position, corner)
        ), "the connector must remain inside the two diagonally touching blocks");
        assertCurved(plan);
        assertNoDegeneratePrimitives(plan);
    }

    @Test
    void faceConnectedDetourDoesNotReceiveVertexGeometry() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 0)
            .occupy(1, 0, 1)
            .build();

        assertTrue(mesher.mesh(neighborhood).primitives().isEmpty());
    }

    @Test
    void negativeVertexDiagonalIsGeneratedByExactlyOneOwnerBlock() {
        VoxelNeighborhood negative = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(-1, -1, -1)
            .build();
        VoxelNeighborhood positive = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 1, 1)
            .build();

        assertTrue(mesher.mesh(negative).primitives().isEmpty());
        assertFalse(mesher.mesh(positive).primitives().isEmpty());
    }

    private static void assertCurved(MeshPlan plan) {
        assertTrue(plan.primitives().stream().flatMap(primitive -> primitive.vertices().stream()).anyMatch(vertex -> {
            Vec3 normal = vertex.normal();
            int nonZeroAxes = 0;
            nonZeroAxes += Math.abs(normal.x()) > EPSILON ? 1 : 0;
            nonZeroAxes += Math.abs(normal.y()) > EPSILON ? 1 : 0;
            nonZeroAxes += Math.abs(normal.z()) > EPSILON ? 1 : 0;
            return nonZeroAxes >= 2;
        }));
    }

    private static void assertNoDegeneratePrimitives(MeshPlan plan) {
        assertTrue(plan.primitives().stream().allMatch(primitive -> {
            List<MeshVertex> vertices = primitive.vertices();
            Vec3 firstEdge = vertices.get(1).position().subtract(vertices.get(0).position());
            Vec3 secondEdge = vertices.get(2).position().subtract(vertices.get(0).position());
            return firstEdge.cross(secondEdge).length() > EPSILON;
        }), "the point throat must use triangles instead of collapsed quads");
    }

    private static double distanceFromBodyDiagonal(Vec3 position, Vec3 corner) {
        Vec3 offset = position.subtract(corner);
        Vec3 diagonal = new Vec3(1.0, 1.0, 1.0).normalize();
        return offset.subtract(diagonal.multiply(offset.dot(diagonal))).length();
    }

    private static boolean insideCenterBlock(Vec3 position, Vec3 corner) {
        return position.x() <= corner.x() + EPSILON
            && position.y() <= corner.y() + EPSILON
            && position.z() <= corner.z() + EPSILON;
    }

    private static boolean insidePositiveDiagonalBlock(Vec3 position, Vec3 corner) {
        return position.x() >= corner.x() - EPSILON
            && position.y() >= corner.y() - EPSILON
            && position.z() >= corner.z() - EPSILON;
    }

    private static List<Vec3> positions(MeshPlan plan) {
        return plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .map(MeshVertex::position)
            .toList();
    }
}
