package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagonalContactBridgeMesherTest {
    private static final double EPSILON = 1.0e-6;
    private final DiagonalContactBridgeMesher mesher = new DiagonalContactBridgeMesher(3.0 / 32.0, 3);

    @Test
    void edgeOnlyContactGetsAClosedBridgeAroundTheSharedLine() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 1)
            .build();

        MeshPlan bridge = mesher.mesh(neighborhood);

        assertFalse(bridge.primitives().isEmpty());
        assertClosed(bridge);
        List<Vec3> positions = positions(bridge);
        assertTrue(positions.stream().anyMatch(position -> position.x() < 1.0 && position.z() < 1.0));
        assertTrue(positions.stream().anyMatch(position -> position.x() > 1.0 && position.z() > 1.0));
        assertTrue(positions.stream().anyMatch(position -> position.x() < 1.0 && position.z() > 1.0));
        assertTrue(positions.stream().anyMatch(position -> position.x() > 1.0 && position.z() < 1.0));
    }

    @Test
    void cornerOnlyContactGetsAClosedBridgeAroundTheSharedPoint() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 1, 1)
            .build();

        MeshPlan bridge = mesher.mesh(neighborhood);

        assertFalse(bridge.primitives().isEmpty());
        assertClosed(bridge);
        List<Vec3> positions = positions(bridge);
        for (int axis = 0; axis < 3; axis++) {
            int checkedAxis = axis;
            assertTrue(positions.stream().anyMatch(position -> position.component(checkedAxis) < 1.0));
            assertTrue(positions.stream().anyMatch(position -> position.component(checkedAxis) > 1.0));
        }
    }

    @Test
    void faceConnectedDetourDoesNotReceiveAnExtraBridge() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 0)
            .occupy(1, 0, 1)
            .build();

        assertTrue(mesher.mesh(neighborhood).primitives().isEmpty());
    }

    @Test
    void onlyOneOfTheTwoBlocksOwnsTheBridge() {
        VoxelNeighborhood negativeDiagonal = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(-1, 0, -1)
            .build();

        assertTrue(mesher.mesh(negativeDiagonal).primitives().isEmpty());
    }

    @Test
    void roundedVoxelMesherKeepsTheBridgeDuringPlanarFaceReplacement() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.builder()
            .occupy(0, 0, 0)
            .occupy(1, 0, 1)
            .build();
        MeshPlan bridge = mesher.mesh(neighborhood);

        MeshPlan composed = new RoundedVoxelMesher()
            .mesh(neighborhood)
            .withoutPlanarFaces(neighborhood.planarFaceBits());

        assertTrue(composed.primitives().containsAll(bridge.primitives()));
    }

    private static List<Vec3> positions(MeshPlan plan) {
        return plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .map(MeshVertex::position)
            .toList();
    }

    private static void assertClosed(MeshPlan plan) {
        Map<Edge, Integer> edgeUses = new HashMap<>();
        for (MeshPrimitive primitive : plan.primitives()) {
            List<MeshVertex> vertices = primitive.vertices();
            for (int index = 0; index < vertices.size(); index++) {
                Edge edge = Edge.of(
                    Point.of(vertices.get(index).position()),
                    Point.of(vertices.get((index + 1) % vertices.size()).position())
                );
                edgeUses.merge(edge, 1, Integer::sum);
            }
        }
        assertTrue(edgeUses.values().stream().allMatch(count -> count == 2), () ->
            "bridge has open or duplicated edges: " + edgeUses.entrySet().stream()
                .filter(entry -> entry.getValue() != 2)
                .limit(10)
                .toList()
        );
    }

    private record Point(long x, long y, long z) implements Comparable<Point> {
        private static Point of(Vec3 position) {
            return new Point(
                Math.round(position.x() / EPSILON),
                Math.round(position.y() / EPSILON),
                Math.round(position.z() / EPSILON)
            );
        }

        @Override
        public int compareTo(Point other) {
            int xOrder = Long.compare(x, other.x);
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Long.compare(y, other.y);
            return yOrder != 0 ? yOrder : Long.compare(z, other.z);
        }
    }

    private record Edge(Point first, Point second) {
        private static Edge of(Point first, Point second) {
            return first.compareTo(second) <= 0 ? new Edge(first, second) : new Edge(second, first);
        }
    }
}
