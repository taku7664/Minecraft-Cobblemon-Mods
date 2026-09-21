package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Extends a rounded block to a diagonal edge contact with a curved pointed boundary. */
final class DiagonalContactFilletMesher {
    private static final double EPSILON = 1.0e-9;
    private static final double CONTACT_HALF_ANGLE = Math.PI / 12.0;

    private final double radius;
    private final int curveSegments;

    DiagonalContactFilletMesher(double radius, int segments) {
        if (!Double.isFinite(radius) || radius <= 0.0 || radius >= 0.5 || segments <= 0) {
            throw new IllegalArgumentException("radius and segments must be positive and radius below half a block");
        }
        this.radius = radius;
        this.curveSegments = Math.max(6, segments * 2);
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
                    List<Cell> edge = edgeCells(
                        axis, firstRadialAxis, secondRadialAxis, firstSide, secondSide, 0
                    );
                    List<Cell> occupied = occupiedCells(neighborhood, edge);
                    if (!isSimpleDiagonalPair(occupied)) {
                        continue;
                    }
                    Vec3 edgeCenter = Vec3.ZERO
                        .withComponent(firstRadialAxis, firstSide)
                        .withComponent(secondRadialAxis, secondSide);
                    int firstInward = firstSide == 0 ? 1 : -1;
                    int secondInward = secondSide == 0 ? 1 : -1;
                    boolean capStart = !isSimpleDiagonalPair(occupiedCells(neighborhood, edgeCells(
                        axis, firstRadialAxis, secondRadialAxis, firstSide, secondSide, -1
                    )));
                    boolean capEnd = !isSimpleDiagonalPair(occupiedCells(neighborhood, edgeCells(
                        axis, firstRadialAxis, secondRadialAxis, firstSide, secondSide, 1
                    )));
                    emitFillet(
                        edgeCenter, axis, firstRadialAxis, secondRadialAxis,
                        firstInward, secondInward, capStart, capEnd, output
                    );
                }
            }
        }
        emitVertexTips(neighborhood, output);
        return new MeshPlan(output);
    }

    private void emitVertexTips(VoxelNeighborhood neighborhood, List<MeshPrimitive> output) {
        for (int cornerX = 0; cornerX <= 1; cornerX++) {
            for (int cornerY = 0; cornerY <= 1; cornerY++) {
                for (int cornerZ = 0; cornerZ <= 1; cornerZ++) {
                    List<Cell> occupied = occupiedCells(
                        neighborhood, cornerCells(cornerX, cornerY, cornerZ)
                    );
                    if (!isPureVertexPair(occupied)) {
                        continue;
                    }
                    Vec3 apex = new Vec3(cornerX, cornerY, cornerZ);
                    Vec3 inward = new Vec3(
                        cornerX == 0 ? 1.0 : -1.0,
                        cornerY == 0 ? 1.0 : -1.0,
                        cornerZ == 0 ? 1.0 : -1.0
                    ).normalize();
                    emitVertexTip(apex, inward, output);
                }
            }
        }
    }

    private void emitVertexTip(Vec3 apex, Vec3 inwardAxis, List<MeshPrimitive> output) {
        Vec3 helper = Math.abs(inwardAxis.y()) < 0.9
            ? new Vec3(0.0, 1.0, 0.0)
            : new Vec3(1.0, 0.0, 0.0);
        Vec3 firstRadial = inwardAxis.cross(helper).normalize();
        Vec3 secondRadial = inwardAxis.cross(firstRadial).normalize();
        double length = radius * 2.0 / Math.sqrt(3.0);
        double width = radius * Math.sqrt(2.0 / 3.0);
        double throat = radius * 0.08;
        int slices = Math.max(8, curveSegments * 2);
        Vec3 ringCenter = apex.add(inwardAxis.multiply(length));
        for (int slice = 0; slice < slices; slice++) {
            double firstAngle = 2.0 * Math.PI * slice / slices;
            double secondAngle = 2.0 * Math.PI * (slice + 1) / slices;
            Vec3 firstDirection = radialDirection(firstRadial, secondRadial, firstAngle);
            Vec3 secondDirection = radialDirection(firstRadial, secondRadial, secondAngle);
            addOriented(output, PrimitiveKind.CONTACT, List.of(
                new MeshVertex(
                    apex.add(firstDirection.multiply(throat)),
                    coneNormal(inwardAxis, firstDirection, length, width - throat)
                ),
                new MeshVertex(
                    apex.add(secondDirection.multiply(throat)),
                    coneNormal(inwardAxis, secondDirection, length, width - throat)
                ),
                new MeshVertex(
                    ringCenter.add(secondDirection.multiply(width)),
                    coneNormal(inwardAxis, secondDirection, length, width - throat)
                ),
                new MeshVertex(
                    ringCenter.add(firstDirection.multiply(width)),
                    coneNormal(inwardAxis, firstDirection, length, width - throat)
                )
            ));
        }
    }

    private static Vec3 radialDirection(Vec3 firstRadial, Vec3 secondRadial, double angle) {
        return firstRadial.multiply(Math.cos(angle)).add(secondRadial.multiply(Math.sin(angle)));
    }

    private static Vec3 coneNormal(Vec3 axis, Vec3 radial, double length, double width) {
        return radial.multiply(length).subtract(axis.multiply(width)).normalize();
    }

    private void emitFillet(
        Vec3 edgeCenter,
        int axis,
        int firstAxis,
        int secondAxis,
        int firstInward,
        int secondInward,
        boolean capStart,
        boolean capEnd,
        List<MeshPrimitive> output
    ) {
        double[] axisPositions = {0.0, radius, 0.5, 1.0 - radius, 1.0};
        for (int axisSegment = 0; axisSegment < axisPositions.length - 1; axisSegment++) {
            double firstAxisPosition = axisPositions[axisSegment];
            double secondAxisPosition = axisPositions[axisSegment + 1];
            for (int segment = 0; segment < curveSegments; segment++) {
                double firstAmount = (double) segment / curveSegments;
                double secondAmount = (double) (segment + 1) / curveSegments;
                addOriented(output, PrimitiveKind.CONTACT, List.of(
                    filletVertex(
                        edgeCenter, axis, firstAxis, secondAxis, firstInward, secondInward,
                        capStart, capEnd, firstAxisPosition, firstAmount
                    ),
                    filletVertex(
                        edgeCenter, axis, firstAxis, secondAxis, firstInward, secondInward,
                        capStart, capEnd, firstAxisPosition, secondAmount
                    ),
                    filletVertex(
                        edgeCenter, axis, firstAxis, secondAxis, firstInward, secondInward,
                        capStart, capEnd, secondAxisPosition, secondAmount
                    ),
                    filletVertex(
                        edgeCenter, axis, firstAxis, secondAxis, firstInward, secondInward,
                        capStart, capEnd, secondAxisPosition, firstAmount
                    )
                ));
            }
        }
    }

    private MeshVertex filletVertex(
        Vec3 edgeCenter,
        int axis,
        int firstAxis,
        int secondAxis,
        int firstInward,
        int secondInward,
        boolean taperStart,
        boolean taperEnd,
        double axisPosition,
        double curveAmount
    ) {
        Strength strength = contactStrength(axisPosition, taperStart, taperEnd);
        Vec3 base = baseCurve(edgeCenter, firstAxis, secondAxis, firstInward, secondInward, curveAmount);
        Vec3 baseTangent = baseCurveTangent(firstAxis, secondAxis, firstInward, secondInward, curveAmount);
        CurvePoint pointed = pointedCurve(
            edgeCenter, firstAxis, secondAxis, firstInward, secondInward, curveAmount
        );
        Vec3 radialPosition = base.multiply(1.0 - strength.value())
            .add(pointed.position().multiply(strength.value()));
        Vec3 curveTangent = baseTangent.multiply(1.0 - strength.value())
            .add(pointed.tangent().multiply(2.0 * strength.value()));
        Vec3 axisTangent = Vec3.ZERO.withComponent(axis, 1.0)
            .add(pointed.position().subtract(base).multiply(strength.derivative()));
        Vec3 normal = axisTangent.cross(curveTangent).normalize();
        Vec3 outward = Vec3.ZERO
            .withComponent(firstAxis, -firstInward)
            .withComponent(secondAxis, -secondInward);
        if (normal.dot(outward) < 0.0) {
            normal = normal.multiply(-1.0);
        }
        return new MeshVertex(radialPosition.withComponent(axis, axisPosition), normal);
    }

    private Strength contactStrength(double axisPosition, boolean taperStart, boolean taperEnd) {
        Strength start = taperStart
            ? smoothStrength(axisPosition / radius, 1.0 / radius)
            : new Strength(1.0, 0.0);
        Strength end = taperEnd
            ? smoothStrength((1.0 - axisPosition) / radius, -1.0 / radius)
            : new Strength(1.0, 0.0);
        return start.value() <= end.value() ? start : end;
    }

    private static Strength smoothStrength(double amount, double amountDerivative) {
        if (amount <= 0.0) {
            return new Strength(0.0, 0.0);
        }
        if (amount >= 1.0) {
            return new Strength(1.0, 0.0);
        }
        return new Strength(
            amount * amount * (3.0 - 2.0 * amount),
            6.0 * amount * (1.0 - amount) * amountDerivative
        );
    }

    private CurvePoint pointedCurve(
        Vec3 center,
        int firstAxis,
        int secondAxis,
        int firstInward,
        int secondInward,
        double amount
    ) {
        Vec3 firstTangent = baseCurve(
            center, firstAxis, secondAxis, firstInward, secondInward, 0.0
        );
        Vec3 secondTangent = baseCurve(
            center, firstAxis, secondAxis, firstInward, secondInward, 1.0
        );
        double diagonalControl = radius * 0.08;
        Vec3 inwardDiagonal = center
            .withComponent(firstAxis, center.component(firstAxis) + firstInward * diagonalControl)
            .withComponent(secondAxis, center.component(secondAxis) + secondInward * diagonalControl);
        if (amount <= 0.5) {
            double local = amount * 2.0;
            Vec3 faceControl = firstTangent.multiply(0.55).add(center.multiply(0.45));
            return cubic(firstTangent, faceControl, inwardDiagonal, center, local);
        }
        double local = (amount - 0.5) * 2.0;
        Vec3 faceControl = secondTangent.multiply(0.55).add(center.multiply(0.45));
        return cubic(center, inwardDiagonal, faceControl, secondTangent, local);
    }

    private Vec3 baseCurve(
        Vec3 center,
        int firstAxis,
        int secondAxis,
        int firstInward,
        int secondInward,
        double amount
    ) {
        double angle = Math.PI * 0.25 + (2.0 * amount - 1.0) * CONTACT_HALF_ANGLE;
        return center
            .withComponent(
                firstAxis,
                center.component(firstAxis) + firstInward * radius * (1.0 - Math.cos(angle))
            )
            .withComponent(
                secondAxis,
                center.component(secondAxis) + secondInward * radius * (1.0 - Math.sin(angle))
            );
    }

    private Vec3 baseCurveTangent(
        int firstAxis,
        int secondAxis,
        int firstInward,
        int secondInward,
        double amount
    ) {
        double angle = Math.PI * 0.25 + (2.0 * amount - 1.0) * CONTACT_HALF_ANGLE;
        double angleDerivative = 2.0 * CONTACT_HALF_ANGLE;
        return Vec3.ZERO
            .withComponent(firstAxis, firstInward * radius * Math.sin(angle) * angleDerivative)
            .withComponent(secondAxis, -secondInward * radius * Math.cos(angle) * angleDerivative);
    }

    private static CurvePoint cubic(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double amount) {
        double inverse = 1.0 - amount;
        Vec3 position = p0.multiply(inverse * inverse * inverse)
            .add(p1.multiply(3.0 * inverse * inverse * amount))
            .add(p2.multiply(3.0 * inverse * amount * amount))
            .add(p3.multiply(amount * amount * amount));
        Vec3 tangent = p1.subtract(p0).multiply(3.0 * inverse * inverse)
            .add(p2.subtract(p1).multiply(6.0 * inverse * amount))
            .add(p3.subtract(p2).multiply(3.0 * amount * amount));
        return new CurvePoint(position, tangent);
    }

    private static void addOriented(
        List<MeshPrimitive> output,
        PrimitiveKind kind,
        List<MeshVertex> source
    ) {
        List<MeshVertex> vertices = new ArrayList<>(source);
        Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
            .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
        if (geometricNormal.length() <= EPSILON) {
            return;
        }
        Vec3 averageNormal = vertices.stream().map(MeshVertex::normal).reduce(Vec3.ZERO, Vec3::add);
        if (geometricNormal.dot(averageNormal) < 0.0) {
            Collections.reverse(vertices);
        }
        output.add(new MeshPrimitive(kind, dominantFace(averageNormal), vertices));
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

    private static List<Cell> occupiedCells(VoxelNeighborhood neighborhood, List<Cell> cells) {
        return cells.stream().filter(cell -> neighborhood.occupied(cell.x(), cell.y(), cell.z())).toList();
    }

    private static boolean isSimpleDiagonalPair(List<Cell> occupied) {
        if (occupied.size() != 2 || !occupied.contains(Cell.CENTER)) {
            return false;
        }
        Cell first = occupied.get(0);
        Cell second = occupied.get(1);
        return Math.abs(first.x() - second.x())
            + Math.abs(first.y() - second.y())
            + Math.abs(first.z() - second.z()) == 2;
    }

    private static boolean isPureVertexPair(List<Cell> occupied) {
        if (occupied.size() != 2 || !occupied.contains(Cell.CENTER)) {
            return false;
        }
        Cell other = occupied.get(0).equals(Cell.CENTER) ? occupied.get(1) : occupied.get(0);
        return Math.abs(other.x()) + Math.abs(other.y()) + Math.abs(other.z()) == 3;
    }

    private record CurvePoint(Vec3 position, Vec3 tangent) {
    }

    private record Strength(double value, double derivative) {
    }

    private record Cell(int x, int y, int z) {
        private static final Cell CENTER = new Cell(0, 0, 0);
    }
}
