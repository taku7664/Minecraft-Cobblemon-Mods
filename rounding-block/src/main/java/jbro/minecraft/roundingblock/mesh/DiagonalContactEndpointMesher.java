package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Joins vertex-only diagonal contacts with a concave neck embedded in the two occupied blocks. */
final class DiagonalContactEndpointMesher {
    private final double radius;
    private final int segments;

    DiagonalContactEndpointMesher(double radius, int segments) {
        if (!Double.isFinite(radius) || radius <= 0.0 || radius >= 0.5 || segments <= 0) {
            throw new IllegalArgumentException("radius and segments must be positive and radius below half a block");
        }
        this.radius = radius;
        this.segments = segments;
    }

    MeshPlan mesh(VoxelNeighborhood neighborhood) {
        if (!neighborhood.occupied(0, 0, 0)) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        emitVertexNecks(neighborhood, output);
        return new MeshPlan(output);
    }

    private void emitVertexNecks(VoxelNeighborhood neighborhood, List<MeshPrimitive> output) {
        for (int cornerX = 0; cornerX <= 1; cornerX++) {
            for (int cornerY = 0; cornerY <= 1; cornerY++) {
                for (int cornerZ = 0; cornerZ <= 1; cornerZ++) {
                    List<Cell> occupied = occupiedCells(neighborhood, cornerCells(cornerX, cornerY, cornerZ));
                    if (!isOppositeVertexPair(occupied) || !ownedByCenter(occupied)) {
                        continue;
                    }
                    Cell other = occupied.get(0).equals(Cell.CENTER) ? occupied.get(1) : occupied.get(0);
                    emitTangentCornerTips(
                        new Vec3(cornerX, cornerY, cornerZ),
                        new Vec3(other.x(), other.y(), other.z()).normalize(),
                        output
                    );
                }
            }
        }
    }

    private void emitTangentCornerTips(Vec3 center, Vec3 axis, List<MeshPrimitive> output) {
        emitTangentCornerTip(center, axis, output);
        emitTangentCornerTip(center, axis.multiply(-1.0), output);
    }

    private void emitTangentCornerTip(Vec3 apex, Vec3 axis, List<MeshPrimitive> output) {
        Vec3 helper = Math.abs(axis.y()) < 0.9
            ? new Vec3(0.0, 1.0, 0.0)
            : new Vec3(1.0, 0.0, 0.0);
        Vec3 firstRadial = axis.cross(helper).normalize();
        Vec3 secondRadial = axis.cross(firstRadial).normalize();
        double ringDistance = radius * 2.0 / Math.sqrt(3.0);
        double ringRadius = radius * Math.sqrt(2.0 / 3.0);
        double slope = ringRadius / ringDistance;
        Vec3 ringCenter = apex.add(axis.multiply(ringDistance));
        int slices = Math.max(8, segments * 4);
        for (int slice = 0; slice < slices; slice++) {
            double firstAngle = 2.0 * Math.PI * slice / slices;
            double secondAngle = 2.0 * Math.PI * (slice + 1) / slices;
            double middleAngle = (firstAngle + secondAngle) * 0.5;
            Vec3 firstDirection = radialDirection(firstRadial, secondRadial, firstAngle);
            Vec3 secondDirection = radialDirection(firstRadial, secondRadial, secondAngle);
            Vec3 middleDirection = radialDirection(firstRadial, secondRadial, middleAngle);
            addOriented(output, List.of(
                new MeshVertex(apex, middleDirection.subtract(axis.multiply(slope)).normalize()),
                new MeshVertex(
                    ringCenter.add(firstDirection.multiply(ringRadius)),
                    firstDirection.subtract(axis.multiply(slope)).normalize()
                ),
                new MeshVertex(
                    ringCenter.add(secondDirection.multiply(ringRadius)),
                    secondDirection.subtract(axis.multiply(slope)).normalize()
                )
            ));
        }
    }

    private static Vec3 radialDirection(Vec3 firstRadial, Vec3 secondRadial, double angle) {
        return firstRadial.multiply(Math.cos(angle)).add(secondRadial.multiply(Math.sin(angle)));
    }

    private static void addOriented(List<MeshPrimitive> output, List<MeshVertex> source) {
        List<MeshVertex> vertices = new ArrayList<>(source);
        Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
            .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
        Vec3 averageNormal = vertices.stream().map(MeshVertex::normal).reduce(Vec3.ZERO, Vec3::add);
        if (geometricNormal.dot(averageNormal) < 0.0) {
            Collections.reverse(vertices);
        }
        output.add(new MeshPrimitive(PrimitiveKind.CONTACT, dominantFace(averageNormal), vertices));
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
        return CubeFace.of(axis, normal.component(axis) < 0.0 ? -1 : 1);
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

    private static List<Cell> occupiedCells(VoxelNeighborhood neighborhood, List<Cell> cells) {
        return cells.stream().filter(cell -> neighborhood.occupied(cell.x(), cell.y(), cell.z())).toList();
    }

    private static boolean isOppositeVertexPair(List<Cell> occupied) {
        if (occupied.size() != 2) {
            return false;
        }
        Cell first = occupied.get(0);
        Cell second = occupied.get(1);
        return Math.abs(first.x() - second.x()) == 1
            && Math.abs(first.y() - second.y()) == 1
            && Math.abs(first.z() - second.z()) == 1;
    }

    private static boolean ownedByCenter(List<Cell> occupied) {
        return occupied.stream().min(Cell::compareTo).orElseThrow().equals(Cell.CENTER);
    }

    private record Cell(int x, int y, int z) implements Comparable<Cell> {
        private static final Cell CENTER = new Cell(0, 0, 0);

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
