package jbro.minecraft.roundingblock.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class RoundedVoxelMesherTest {
    private static final double EPSILON = 1.0e-6;
    private static final double POINT_SCALE = 1_000_000.0;

    @Test
    void allLatticeVertexOccupanciesAssembleWithoutCracksOrDuplicateTriangles() {
        RoundedVoxelMesher mesher = new RoundedVoxelMesher();

        for (int mask = 1; mask < 255; mask++) {
            int testedMask = mask;
            Set<Cell> solids = cellsForVertexMask(testedMask);
            List<WorldTriangle> triangles = assemble(solids, mesher);
            Map<WorldEdge, Integer> edgeUses = new HashMap<>();
            Set<WorldTriangle> unique = new HashSet<>();

            for (WorldTriangle triangle : triangles) {
                if (triangle.a().equals(triangle.b())
                    || triangle.b().equals(triangle.c())
                    || triangle.c().equals(triangle.a())) {
                    continue;
                }
                assertTrue(unique.add(triangle.canonical()), () -> "duplicate triangle for mask " + testedMask + ": " + triangle);
                edgeUses.merge(WorldEdge.of(triangle.a(), triangle.b()), 1, Integer::sum);
                edgeUses.merge(WorldEdge.of(triangle.b(), triangle.c()), 1, Integer::sum);
                edgeUses.merge(WorldEdge.of(triangle.c(), triangle.a()), 1, Integer::sum);
            }

            assertFalse(triangles.isEmpty(), () -> "empty surface for mask " + testedMask);
            assertClosedLineCoverage(testedMask, edgeUses);
        }
    }

    @Test
    void eachBlockEmitsOnlyGeometryInsideItsOwnBounds() {
        RoundedVoxelMesher mesher = new RoundedVoxelMesher();
        Set<Cell> solids = Set.of(
            new Cell(-1, -1, -1),
            new Cell(-1, -1, 0),
            new Cell(-1, 0, -1),
            new Cell(0, -1, -1),
            new Cell(0, 0, -1),
            new Cell(0, -1, 0),
            new Cell(-1, 0, 0)
        );

        for (Cell cell : solids) {
            MeshPlan plan = mesher.mesh(neighborhoodFor(cell, solids));
            assertFalse(plan.primitives().isEmpty());
            assertTrue(plan.primitives().stream().allMatch(primitive -> primitive.vertices().stream().allMatch(vertex -> {
                double minimum = primitive.kind() == PrimitiveKind.CONCAVE ? -0.5 : 0.0;
                double maximum = primitive.kind() == PrimitiveKind.CONCAVE ? 1.5 : 1.0;
                return vertex.position().x() >= minimum - EPSILON && vertex.position().x() <= maximum + EPSILON
                    && vertex.position().y() >= minimum - EPSILON && vertex.position().y() <= maximum + EPSILON
                    && vertex.position().z() >= minimum - EPSILON && vertex.position().z() <= maximum + EPSILON;
            })));
        }
    }

    @Test
    void threeAndSevenSolidJunctionsUseOnlyConnectedSurfacePrimitives() {
        RoundedVoxelMesher mesher = new RoundedVoxelMesher();

        for (int mask : new int[]{0b0000_0111, 0b0111_1111}) {
            Set<Cell> solids = cellsForVertexMask(mask);
            List<MeshPrimitive> primitives = new ArrayList<>();
            for (Cell cell : solids) {
                primitives.addAll(mesher.mesh(neighborhoodFor(cell, solids)).primitives());
            }
            assertFalse(primitives.isEmpty());
            assertTrue(primitives.stream().allMatch(primitive ->
                primitive.kind() == PrimitiveKind.FACE
                    || primitive.kind() == PrimitiveKind.EDGE
                    || primitive.kind() == PrimitiveKind.CONCAVE
            ));
        }
    }

    @Test
    void neighborhoodBitCoordinatesRoundTrip() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.empty()
            .withOccupied(-1, -1, -1)
            .withOccupied(0, 0, 0)
            .withOccupied(1, 1, 1);

        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    boolean expected = x == y && y == z;
                    assertEquals(expected, neighborhood.occupied(x, y, z));
                }
            }
        }
    }

    @Test
    void isolatedBlockKeepsATriangleBudget() {
        MeshPlan plan = new RoundedVoxelMesher().mesh(
            VoxelNeighborhood.empty().withOccupied(0, 0, 0)
        );

        assertFalse(plan.primitives().isEmpty());
        assertTrue(
            plan.primitives().size() <= 640,
            () -> "isolated block triangle budget exceeded: " + plan.primitives().size()
                + " flat=" + plan.primitives().stream().filter(p -> p.kind() == PrimitiveKind.FACE).count()
                + " curved=" + plan.primitives().stream().filter(p -> p.kind() == PrimitiveKind.EDGE).count()
        );
    }

    @Test
    void flatTerrainKeepsACompactPrimitiveBudget() {
        VoxelNeighborhood neighborhood = flatTerrainNeighborhood();

        MeshPlan plan = new RoundedVoxelMesher().mesh(neighborhood);
        assertFalse(plan.primitives().isEmpty());
        assertTrue(
            plan.primitives().size() <= 128,
            () -> "flat terrain primitive budget exceeded: " + plan.primitives().size()
        );
    }

    @Test
    void flatTerrainIsOneExactPlaneWithOneNormal() {
        MeshPlan plan = new RoundedVoxelMesher().mesh(flatTerrainNeighborhood());
        List<MeshVertex> vertices = plan.primitives().stream()
            .flatMap(primitive -> primitive.vertices().stream())
            .toList();

        assertFalse(vertices.isEmpty());
        double planeY = vertices.getFirst().position().y();
        assertEquals(1.0, planeY, EPSILON);
        assertTrue(vertices.stream().allMatch(vertex -> Math.abs(vertex.position().y() - planeY) <= EPSILON));
        assertTrue(vertices.stream().allMatch(vertex -> vertex.normal().subtract(new Vec3(0.0, 1.0, 0.0)).length() <= EPSILON));
    }

    @Test
    void raisedNeighborNeverDistortsTheFlatFloor() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.empty();
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                neighborhood = neighborhood
                    .withOccupied(x, -1, z)
                    .withOccupied(x, 0, z);
            }
        }
        for (int x = -1; x <= 1; x++) {
            neighborhood = neighborhood.withOccupied(x, 1, 1);
        }

        assertFalse(neighborhood.isAxisAlignedLayered(), "raised neighbor must exercise the rounded path");
        assertEquals(0, neighborhood.planarFaceBits(), "a face touched by a concave patch must not be replaced wholesale");
        MeshPlan plan = new RoundedVoxelMesher().mesh(neighborhood);
        List<MeshVertex> untouchedFloor = plan.primitives().stream()
            .filter(primitive -> primitive.kind() != PrimitiveKind.CONCAVE)
            .flatMap(primitive -> primitive.vertices().stream())
            .filter(vertex -> vertex.position().y() > 0.5 && vertex.position().z() < 0.875)
            .toList();
        assertFalse(untouchedFloor.isEmpty());
        assertTrue(untouchedFloor.stream().allMatch(vertex -> Math.abs(vertex.position().y() - 1.0) <= EPSILON));
        assertTrue(plan.primitives().stream()
            .filter(primitive -> primitive.kind() != PrimitiveKind.CONCAVE)
            .flatMap(primitive -> primitive.vertices().stream())
            .allMatch(vertex ->
                vertex.position().x() >= -EPSILON && vertex.position().x() <= 1.0 + EPSILON
                    && vertex.position().y() >= -EPSILON && vertex.position().y() <= 1.0 + EPSILON
                    && vertex.position().z() >= -EPSILON && vertex.position().z() <= 1.0 + EPSILON
        ));
        MeshPlan composed = plan.withoutPlanarFaces(neighborhood.planarFaceBits());
        assertFalse(composed.primitives().isEmpty(), "concave wall-floor bridge must survive planar replacement");
        assertEquals(plan.primitives().size(), composed.primitives().size());
        assertTrue(composed.primitives().stream().anyMatch(primitive -> primitive.kind() == PrimitiveKind.CONCAVE));
    }

    @Test
    void finalPlanarCompositionKeepsRaisedFloorJunctionClosed() {
        Set<Cell> solids = new HashSet<>();
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                solids.add(new Cell(x, -1, z));
                solids.add(new Cell(x, 0, z));
                if (z >= 1) {
                    solids.add(new Cell(x, 1, z));
                }
            }
        }

        List<WorldTriangle> triangles = assembleComposed(solids, new RoundedVoxelMesher());
        Map<WorldEdge, Integer> edgeUses = new HashMap<>();
        Set<WorldTriangle> unique = new HashSet<>();
        for (WorldTriangle triangle : triangles) {
            if (triangle.a().equals(triangle.b())
                || triangle.b().equals(triangle.c())
                || triangle.c().equals(triangle.a())) {
                continue;
            }
            assertTrue(unique.add(triangle.canonical()), () -> "duplicate final-composition triangle: " + triangle);
            edgeUses.merge(WorldEdge.of(triangle.a(), triangle.b()), 1, Integer::sum);
            edgeUses.merge(WorldEdge.of(triangle.b(), triangle.c()), 1, Integer::sum);
            edgeUses.merge(WorldEdge.of(triangle.c(), triangle.a()), 1, Integer::sum);
        }
        assertClosedLineCoverage(-1, edgeUses);
    }

    @Test
    void axisAlignedTerrainCanUseTheOriginalPlanarModel() {
        assertTrue(flatTerrainNeighborhood().isAxisAlignedLayered());
        assertTrue(flatTerrainNeighborhood().allExposedFacesArePlanar());

        VoxelNeighborhood corner = VoxelNeighborhood.empty()
            .withOccupied(0, 0, 0)
            .withOccupied(1, 0, 0)
            .withOccupied(0, 0, 1);
        assertFalse(corner.isAxisAlignedLayered());
        assertFalse(corner.allExposedFacesArePlanar());
    }

    @Test
    void planarFaceIgnoresIrrelevantOccupancyBehindIt() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.empty();
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                neighborhood = neighborhood.withOccupied(x, 0, z);
            }
        }
        neighborhood = neighborhood
            .withOccupied(0, -1, 0)
            .withOccupied(-1, -1, -1);

        assertTrue(neighborhood.allExposedFacesArePlanar());
    }

    @Test
    void planarReplacementRemovesEveryPrimitiveInThatFaceHalf() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.empty();
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                neighborhood = neighborhood.withOccupied(x, 0, z);
            }
        }
        neighborhood = neighborhood.withOccupied(-1, -1, -1);

        int planarFaces = 1 << CubeFace.UP.ordinal();
        assertTrue((neighborhood.planarFaceBits() & planarFaces) != 0);

        MeshPlan complete = new RoundedVoxelMesher().mesh(neighborhood);
        MeshPlan composed = complete.withoutPlanarFaces(planarFaces);
        long composedCurves = composed.primitives().stream().filter(p -> p.kind() == PrimitiveKind.EDGE).count();

        assertTrue(composedCurves > 0, "curves in the opposite half must remain");
        assertTrue(composed.primitives().stream().allMatch(primitive ->
            primitive.kind() == PrimitiveKind.CONCAVE
                || primitive.vertices().stream().mapToDouble(vertex -> vertex.position().y()).average().orElseThrow() < 0.5
        ));
    }

    private static VoxelNeighborhood flatTerrainNeighborhood() {
        VoxelNeighborhood neighborhood = VoxelNeighborhood.empty();
        for (int y = -1; y <= 0; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    neighborhood = neighborhood.withOccupied(x, y, z);
                }
            }
        }
        return neighborhood;
    }

    private static List<WorldTriangle> assemble(Set<Cell> solids, RoundedVoxelMesher mesher) {
        List<WorldTriangle> result = new ArrayList<>();
        for (Cell cell : solids) {
            MeshPlan plan = mesher.mesh(neighborhoodFor(cell, solids));
            for (MeshPrimitive primitive : plan.primitives()) {
                List<MeshVertex> vertices = primitive.vertices();
                result.add(new WorldTriangle(
                    PointKey.of(vertices.get(0).position(), cell),
                    PointKey.of(vertices.get(1).position(), cell),
                    PointKey.of(vertices.get(2).position(), cell)
                ));
                if (vertices.size() == 4) {
                    result.add(new WorldTriangle(
                        PointKey.of(vertices.get(0).position(), cell),
                        PointKey.of(vertices.get(2).position(), cell),
                        PointKey.of(vertices.get(3).position(), cell)
                    ));
                }
            }
        }
        return result;
    }

    private static List<WorldTriangle> assembleComposed(Set<Cell> solids, RoundedVoxelMesher mesher) {
        List<WorldTriangle> result = new ArrayList<>();
        for (Cell cell : solids) {
            VoxelNeighborhood neighborhood = neighborhoodFor(cell, solids);
            List<MeshPrimitive> primitives = new ArrayList<>(
                mesher.mesh(neighborhood).withoutPlanarFaces(neighborhood.planarFaceBits()).primitives()
            );
            for (CubeFace face : CubeFace.values()) {
                if ((neighborhood.planarFaceBits() & (1 << face.ordinal())) != 0) {
                    primitives.add(unitFace(face));
                }
            }
            for (MeshPrimitive primitive : primitives) {
                List<MeshVertex> vertices = primitive.vertices();
                result.add(new WorldTriangle(
                    PointKey.of(vertices.get(0).position(), cell),
                    PointKey.of(vertices.get(1).position(), cell),
                    PointKey.of(vertices.get(2).position(), cell)
                ));
                if (vertices.size() == 4) {
                    result.add(new WorldTriangle(
                        PointKey.of(vertices.get(0).position(), cell),
                        PointKey.of(vertices.get(2).position(), cell),
                        PointKey.of(vertices.get(3).position(), cell)
                    ));
                }
            }
        }
        return result;
    }

    private static MeshPrimitive unitFace(CubeFace face) {
        int uAxis = (face.axis() + 1) % 3;
        int vAxis = (face.axis() + 2) % 3;
        double fixed = face.sign() > 0 ? 1.0 : 0.0;
        Vec3 normal = face.normal();
        List<MeshVertex> vertices = new ArrayList<>();
        for (int[] coordinates : new int[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            Vec3 position = Vec3.ZERO
                .withComponent(face.axis(), fixed)
                .withComponent(uAxis, coordinates[0])
                .withComponent(vAxis, coordinates[1]);
            vertices.add(new MeshVertex(position, normal));
        }
        return new MeshPrimitive(PrimitiveKind.FACE, face, vertices);
    }

    private static VoxelNeighborhood neighborhoodFor(Cell center, Set<Cell> solids) {
        VoxelNeighborhood result = VoxelNeighborhood.empty();
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    if (solids.contains(new Cell(center.x() + x, center.y() + y, center.z() + z))) {
                        result = result.withOccupied(x, y, z);
                    }
                }
            }
        }
        return result;
    }

    private static Set<Cell> cellsForVertexMask(int mask) {
        Set<Cell> result = new HashSet<>();
        for (int bit = 0; bit < 8; bit++) {
            if ((mask & (1 << bit)) == 0) {
                continue;
            }
            result.add(new Cell(
                (bit & 1) == 0 ? -1 : 0,
                (bit & 2) == 0 ? -1 : 0,
                (bit & 4) == 0 ? -1 : 0
            ));
        }
        return result;
    }

    private static void assertClosedLineCoverage(int mask, Map<WorldEdge, Integer> edgeUses) {
        Map<LineKey, TreeMap<Long, Integer>> eventsByLine = new HashMap<>();
        for (Map.Entry<WorldEdge, Integer> entry : edgeUses.entrySet()) {
            WorldEdge edge = entry.getKey();
            LineKey line = LineKey.through(edge.first(), edge.second());
            long first = line.parameter(edge.first());
            long second = line.parameter(edge.second());
            long low = Math.min(first, second);
            long high = Math.max(first, second);
            TreeMap<Long, Integer> events = eventsByLine.computeIfAbsent(line, ignored -> new TreeMap<>());
            events.merge(low, entry.getValue(), Integer::sum);
            events.merge(high, -entry.getValue(), Integer::sum);
        }

        List<String> failures = new ArrayList<>();
        for (Map.Entry<LineKey, TreeMap<Long, Integer>> lineEntry : eventsByLine.entrySet()) {
            int coverage = 0;
            Long previous = null;
            for (Map.Entry<Long, Integer> event : lineEntry.getValue().entrySet()) {
                if (previous != null
                    && event.getKey() > previous
                    && coverage != 0
                    && coverage != 2
                    && lineEntry.getKey().worldLength(previous, event.getKey()) > 2.0e-4) {
                    failures.add(lineEntry.getKey() + " " + previous + ".." + event.getKey() + " uses=" + coverage);
                    if (failures.size() == 20) {
                        break;
                    }
                }
                coverage += event.getValue();
                previous = event.getKey();
            }
            if (failures.size() == 20) {
                break;
            }
        }
        assertTrue(failures.isEmpty(), () -> "open or overlapping surface for mask " + mask + ": " + failures);
    }

    private static long greatestCommonDivisor(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private record Cell(int x, int y, int z) {
    }

    private record PointKey(long x, long y, long z) implements Comparable<PointKey> {
        private static PointKey of(Vec3 position, Cell offset) {
            return new PointKey(
                Math.round((position.x() + offset.x()) * POINT_SCALE),
                Math.round((position.y() + offset.y()) * POINT_SCALE),
                Math.round((position.z() + offset.z()) * POINT_SCALE)
            );
        }

        @Override
        public int compareTo(PointKey other) {
            int xOrder = Long.compare(x, other.x);
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Long.compare(y, other.y);
            return yOrder != 0 ? yOrder : Long.compare(z, other.z);
        }
    }

    private record WorldEdge(PointKey first, PointKey second) {
        private static WorldEdge of(PointKey a, PointKey b) {
            return a.compareTo(b) <= 0 ? new WorldEdge(a, b) : new WorldEdge(b, a);
        }
    }

    private record LineKey(long dx, long dy, long dz, long mx, long my, long mz) {
        private static LineKey through(PointKey a, PointKey b) {
            long dx = b.x() - a.x();
            long dy = b.y() - a.y();
            long dz = b.z() - a.z();
            long divisor = greatestCommonDivisor(greatestCommonDivisor(dx, dy), dz);
            dx /= divisor;
            dy /= divisor;
            dz /= divisor;
            if (dx < 0 || dx == 0 && dy < 0 || dx == 0 && dy == 0 && dz < 0) {
                dx = -dx;
                dy = -dy;
                dz = -dz;
            }
            return new LineKey(
                dx,
                dy,
                dz,
                a.y() * dz - a.z() * dy,
                a.z() * dx - a.x() * dz,
                a.x() * dy - a.y() * dx
            );
        }

        private long parameter(PointKey point) {
            return point.x() * dx + point.y() * dy + point.z() * dz;
        }

        private double worldLength(long firstParameter, long secondParameter) {
            double directionLength = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            return Math.abs(secondParameter - firstParameter) / directionLength / POINT_SCALE;
        }
    }

    private record WorldTriangle(PointKey a, PointKey b, PointKey c) {
        private WorldTriangle canonical() {
            List<PointKey> points = new ArrayList<>(List.of(a, b, c));
            points.sort(PointKey::compareTo);
            return new WorldTriangle(points.get(0), points.get(1), points.get(2));
        }
    }
}
