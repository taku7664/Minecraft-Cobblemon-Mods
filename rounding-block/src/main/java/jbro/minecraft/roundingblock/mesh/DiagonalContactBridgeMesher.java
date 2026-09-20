package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Adds closed, render-only geometry between blocks that meet only diagonally. */
final class DiagonalContactBridgeMesher {
    private final double radius;
    private final int slices;
    private final int stacks;

    DiagonalContactBridgeMesher(double radius, int segments) {
        if (!Double.isFinite(radius) || radius <= 0.0 || segments <= 0) {
            throw new IllegalArgumentException("radius and segments must be positive");
        }
        this.radius = radius;
        this.slices = Math.max(8, segments * 4);
        this.stacks = Math.max(4, segments * 2);
    }

    MeshPlan mesh(VoxelNeighborhood neighborhood) {
        if (!neighborhood.occupied(0, 0, 0)) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            int firstRadialAxis = (axis + 1) % 3;
            int secondRadialAxis = (axis + 2) % 3;
            for (int firstSide = 0; firstSide <= 1; firstSide++) {
                for (int secondSide = 0; secondSide <= 1; secondSide++) {
                    List<Cell> cells = edgeCells(
                        axis, firstRadialAxis, secondRadialAxis, firstSide, secondSide, 0
                    );
                    if (ownsDisconnectedFeature(neighborhood, cells)) {
                        boolean capStart = !hasDisconnectedFeature(neighborhood, edgeCells(
                            axis, firstRadialAxis, secondRadialAxis, firstSide, secondSide, -1
                        ));
                        boolean capEnd = !hasDisconnectedFeature(neighborhood, edgeCells(
                            axis, firstRadialAxis, secondRadialAxis, firstSide, secondSide, 1
                        ));
                        emitEdgeBridge(
                            axis,
                            firstRadialAxis,
                            secondRadialAxis,
                            firstSide,
                            secondSide,
                            capStart,
                            capEnd,
                            output
                        );
                    }
                }
            }
        }
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    if (ownsDisconnectedFeature(neighborhood, cornerCells(x, y, z))) {
                        emitCornerBridge(x, y, z, output);
                    }
                }
            }
        }
        return new MeshPlan(output);
    }

    private void emitEdgeBridge(
        int axis,
        int firstRadialAxis,
        int secondRadialAxis,
        int firstSide,
        int secondSide,
        boolean capStart,
        boolean capEnd,
        List<MeshPrimitive> output
    ) {
        Vec3 center = Vec3.ZERO
            .withComponent(firstRadialAxis, firstSide)
            .withComponent(secondRadialAxis, secondSide);
        Vec3 startCenter = center.withComponent(axis, 0.0);
        Vec3 endCenter = center.withComponent(axis, 1.0);

        for (int slice = 0; slice < slices; slice++) {
            double firstAngle = 2.0 * Math.PI * (slice + 0.5) / slices;
            double secondAngle = 2.0 * Math.PI * (slice + 1.5) / slices;
            Vec3 firstNormal = radialNormal(firstRadialAxis, secondRadialAxis, firstAngle);
            Vec3 secondNormal = radialNormal(firstRadialAxis, secondRadialAxis, secondAngle);
            Vec3 firstStart = startCenter.add(firstNormal.multiply(radius));
            Vec3 secondStart = startCenter.add(secondNormal.multiply(radius));
            Vec3 firstEnd = endCenter.add(firstNormal.multiply(radius));
            Vec3 secondEnd = endCenter.add(secondNormal.multiply(radius));

            addOriented(output, List.of(
                new MeshVertex(firstStart, firstNormal),
                new MeshVertex(secondStart, secondNormal),
                new MeshVertex(secondEnd, secondNormal),
                new MeshVertex(firstEnd, firstNormal)
            ));
            if (capStart) {
                addCap(output, startCenter, firstStart, secondStart, axis, -1);
            }
            if (capEnd) {
                addCap(output, endCenter, firstEnd, secondEnd, axis, 1);
            }
        }
    }

    private void emitCornerBridge(int x, int y, int z, List<MeshPrimitive> output) {
        Vec3 center = new Vec3(x, y, z);
        for (int stack = 0; stack < stacks; stack++) {
            double lowerLatitude = -Math.PI / 2.0 + Math.PI * stack / stacks;
            double upperLatitude = -Math.PI / 2.0 + Math.PI * (stack + 1) / stacks;
            for (int slice = 0; slice < slices; slice++) {
                double firstLongitude = 2.0 * Math.PI * slice / slices;
                double secondLongitude = 2.0 * Math.PI * (slice + 1) / slices;
                if (stack == 0) {
                    Vec3 poleNormal = new Vec3(0.0, -1.0, 0.0);
                    Vec3 firstNormal = sphereNormal(upperLatitude, firstLongitude);
                    Vec3 secondNormal = sphereNormal(upperLatitude, secondLongitude);
                    addOriented(output, List.of(
                        new MeshVertex(center.add(poleNormal.multiply(radius)), poleNormal),
                        new MeshVertex(center.add(secondNormal.multiply(radius)), secondNormal),
                        new MeshVertex(center.add(firstNormal.multiply(radius)), firstNormal)
                    ));
                } else if (stack == stacks - 1) {
                    Vec3 firstNormal = sphereNormal(lowerLatitude, firstLongitude);
                    Vec3 secondNormal = sphereNormal(lowerLatitude, secondLongitude);
                    Vec3 poleNormal = new Vec3(0.0, 1.0, 0.0);
                    addOriented(output, List.of(
                        new MeshVertex(center.add(firstNormal.multiply(radius)), firstNormal),
                        new MeshVertex(center.add(secondNormal.multiply(radius)), secondNormal),
                        new MeshVertex(center.add(poleNormal.multiply(radius)), poleNormal)
                    ));
                } else {
                    Vec3 lowerFirst = sphereNormal(lowerLatitude, firstLongitude);
                    Vec3 lowerSecond = sphereNormal(lowerLatitude, secondLongitude);
                    Vec3 upperSecond = sphereNormal(upperLatitude, secondLongitude);
                    Vec3 upperFirst = sphereNormal(upperLatitude, firstLongitude);
                    addOriented(output, List.of(
                        new MeshVertex(center.add(lowerFirst.multiply(radius)), lowerFirst),
                        new MeshVertex(center.add(lowerSecond.multiply(radius)), lowerSecond),
                        new MeshVertex(center.add(upperSecond.multiply(radius)), upperSecond),
                        new MeshVertex(center.add(upperFirst.multiply(radius)), upperFirst)
                    ));
                }
            }
        }
    }

    private static void addCap(
        List<MeshPrimitive> output,
        Vec3 center,
        Vec3 first,
        Vec3 second,
        int axis,
        int sign
    ) {
        Vec3 normal = CubeFace.of(axis, sign).normal();
        addOriented(output, List.of(
            new MeshVertex(center, normal),
            new MeshVertex(first, normal),
            new MeshVertex(second, normal)
        ));
    }

    private static void addOriented(List<MeshPrimitive> output, List<MeshVertex> source) {
        List<MeshVertex> vertices = new ArrayList<>(source);
        Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
            .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
        Vec3 averageNormal = vertices.stream().map(MeshVertex::normal).reduce(Vec3.ZERO, Vec3::add);
        if (geometricNormal.dot(averageNormal) < 0.0) {
            Collections.reverse(vertices);
        }
        CubeFace materialFace = dominantFace(averageNormal);
        output.add(new MeshPrimitive(PrimitiveKind.CONCAVE, materialFace, vertices));
    }

    private static Vec3 radialNormal(int firstAxis, int secondAxis, double angle) {
        return Vec3.ZERO
            .withComponent(firstAxis, Math.cos(angle))
            .withComponent(secondAxis, Math.sin(angle));
    }

    private static Vec3 sphereNormal(double latitude, double longitude) {
        double horizontal = Math.cos(latitude);
        return new Vec3(
            horizontal * Math.cos(longitude),
            Math.sin(latitude),
            horizontal * Math.sin(longitude)
        );
    }

    private static CubeFace dominantFace(Vec3 normal) {
        int axis = 0;
        double magnitude = Math.abs(normal.x());
        if (Math.abs(normal.y()) > magnitude) {
            axis = 1;
            magnitude = Math.abs(normal.y());
        }
        if (Math.abs(normal.z()) > magnitude) {
            axis = 2;
        }
        int sign = normal.component(axis) < 0.0 ? -1 : 1;
        return CubeFace.of(axis, sign);
    }

    private static List<Cell> edgeCells(
        int axis,
        int firstRadialAxis,
        int secondRadialAxis,
        int firstSide,
        int secondSide,
        int axisCoordinate
    ) {
        List<Cell> result = new ArrayList<>(4);
        for (int first = firstSide - 1; first <= firstSide; first++) {
            for (int second = secondSide - 1; second <= secondSide; second++) {
                int[] coordinates = new int[3];
                coordinates[axis] = axisCoordinate;
                coordinates[firstRadialAxis] = first;
                coordinates[secondRadialAxis] = second;
                result.add(new Cell(coordinates[0], coordinates[1], coordinates[2]));
            }
        }
        return result;
    }

    private static List<Cell> cornerCells(int cornerX, int cornerY, int cornerZ) {
        List<Cell> result = new ArrayList<>(8);
        for (int x = cornerX - 1; x <= cornerX; x++) {
            for (int y = cornerY - 1; y <= cornerY; y++) {
                for (int z = cornerZ - 1; z <= cornerZ; z++) {
                    result.add(new Cell(x, y, z));
                }
            }
        }
        return result;
    }

    private static boolean ownsDisconnectedFeature(VoxelNeighborhood neighborhood, List<Cell> candidates) {
        List<Cell> occupied = candidates.stream()
            .filter(cell -> neighborhood.occupied(cell.x, cell.y, cell.z))
            .toList();
        Cell center = new Cell(0, 0, 0);
        return occupied.stream().min(Cell::compareTo).orElse(center).compareTo(center) == 0
            && disconnected(occupied);
    }

    private static boolean hasDisconnectedFeature(VoxelNeighborhood neighborhood, List<Cell> candidates) {
        return disconnected(candidates.stream()
            .filter(cell -> neighborhood.occupied(cell.x, cell.y, cell.z))
            .toList());
    }

    private static boolean disconnected(List<Cell> occupied) {
        if (occupied.size() < 2) {
            return false;
        }
        List<Cell> connected = new ArrayList<>();
        connected.add(occupied.getFirst());
        for (int index = 0; index < connected.size(); index++) {
            Cell current = connected.get(index);
            for (Cell candidate : occupied) {
                if (!connected.contains(candidate) && current.faceAdjacent(candidate)) {
                    connected.add(candidate);
                }
            }
        }
        return connected.size() != occupied.size();
    }

    private record Cell(int x, int y, int z) implements Comparable<Cell> {
        private boolean faceAdjacent(Cell other) {
            return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z) == 1;
        }

        @Override
        public int compareTo(Cell other) {
            int xOrder = Integer.compare(x, other.x);
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Integer.compare(y, other.y);
            return yOrder != 0 ? yOrder : Integer.compare(z, other.z);
        }
    }
}
