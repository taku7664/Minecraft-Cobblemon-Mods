package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the horizontal fluid surface that occupies a rounded solid's carved
 * contact recess. The vanilla fluid cell remains untouched; these primitives
 * only cover the portion across the shared block boundary.
 */
public final class FluidContactPatchMesher {
    private static final double EPSILON = 1.0e-8;
    private static final Vec3 UP = CubeFace.UP.normal();
    private static final CubeFace[] HORIZONTAL = {
        CubeFace.WEST, CubeFace.EAST, CubeFace.NORTH, CubeFace.SOUTH
    };

    public MeshPlan mesh(MeshPlan solidSurface, CubeFace contactFace, double firstHeight, double secondHeight) {
        if (solidSurface == null || contactFace == null) {
            throw new IllegalArgumentException("solidSurface and contactFace are required");
        }
        if (contactFace.axis() == 1) {
            throw new IllegalArgumentException("Fluid contact face must be horizontal");
        }
        if (!validHeight(firstHeight) || !validHeight(secondHeight)) {
            throw new IllegalArgumentException("Fluid heights must be finite values inside 0..1");
        }

        List<MeshPrimitive> output = new ArrayList<>();
        for (MeshPrimitive primitive : solidSurface.primitives()) {
            Segment intersection = intersect(primitive, contactFace, firstHeight, secondHeight);
            if (intersection == null || !touchesOwner(contactFace, intersection)) {
                continue;
            }
            List<Vec3> polygon = contactPolygon(intersection, contactFace);
            for (CubeFace other : HORIZONTAL) {
                if (other != contactFace) {
                    polygon = clipToOwner(polygon, contactFace, other);
                }
            }
            emitPolygon(polygon, output);
        }
        return new MeshPlan(output);
    }

    private static Segment intersect(
        MeshPrimitive primitive,
        CubeFace contactFace,
        double firstHeight,
        double secondHeight
    ) {
        List<Vec3> crossings = new ArrayList<>(4);
        List<MeshVertex> vertices = primitive.vertices();
        for (int index = 0; index < vertices.size(); index++) {
            Vec3 first = vertices.get(index).position();
            Vec3 second = vertices.get((index + 1) % vertices.size()).position();
            double firstDistance = planeDistance(first, contactFace, firstHeight, secondHeight);
            double secondDistance = planeDistance(second, contactFace, firstHeight, secondHeight);
            boolean firstOnPlane = Math.abs(firstDistance) <= EPSILON;
            boolean secondOnPlane = Math.abs(secondDistance) <= EPSILON;
            if (firstOnPlane) {
                addUnique(crossings, first);
            }
            if (secondOnPlane) {
                addUnique(crossings, second);
            }
            if (!firstOnPlane && !secondOnPlane && firstDistance * secondDistance < 0.0) {
                double amount = firstDistance / (firstDistance - secondDistance);
                addUnique(crossings, interpolate(first, second, amount));
            }
        }
        if (crossings.size() < 2) {
            return null;
        }
        Vec3 bestFirst = null;
        Vec3 bestSecond = null;
        double bestDistance = 0.0;
        for (int first = 0; first < crossings.size(); first++) {
            for (int second = first + 1; second < crossings.size(); second++) {
                double distance = crossings.get(first).subtract(crossings.get(second)).length();
                if (distance > bestDistance) {
                    bestDistance = distance;
                    bestFirst = crossings.get(first);
                    bestSecond = crossings.get(second);
                }
            }
        }
        return bestDistance <= EPSILON ? null : new Segment(bestFirst, bestSecond);
    }

    private static double planeDistance(
        Vec3 position,
        CubeFace contactFace,
        double firstHeight,
        double secondHeight
    ) {
        double alongEdge = contactFace.axis() == 0 ? position.z() : position.x();
        double height = firstHeight + (secondHeight - firstHeight) * alongEdge;
        return position.y() - height;
    }

    private static List<Vec3> contactPolygon(Segment segment, CubeFace face) {
        Vec3 outerFirst = projectToFace(segment.first(), face);
        Vec3 outerSecond = projectToFace(segment.second(), face);
        if (outerFirst.subtract(segment.first()).length() <= EPSILON
            && outerSecond.subtract(segment.second()).length() <= EPSILON) {
            return List.of();
        }
        return new ArrayList<>(List.of(outerFirst, outerSecond, segment.second(), segment.first()));
    }

    private static Vec3 projectToFace(Vec3 position, CubeFace face) {
        return position.withComponent(face.axis(), face.sign() < 0 ? 0.0 : 1.0);
    }

    private static List<Vec3> clipToOwner(List<Vec3> input, CubeFace owner, CubeFace other) {
        if (input.isEmpty()) {
            return input;
        }
        List<Vec3> output = new ArrayList<>(input.size() + 1);
        Vec3 previous = input.getLast();
        double previousDistance = ownerDistance(previous, owner) - ownerDistance(previous, other);
        boolean previousInside = previousDistance <= EPSILON;
        for (Vec3 current : input) {
            double currentDistance = ownerDistance(current, owner) - ownerDistance(current, other);
            boolean currentInside = currentDistance <= EPSILON;
            if (currentInside != previousInside) {
                double amount = previousDistance / (previousDistance - currentDistance);
                addUnique(output, interpolate(previous, current, amount));
            }
            if (currentInside) {
                addUnique(output, current);
            }
            previous = current;
            previousDistance = currentDistance;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean ownedBy(CubeFace owner, Vec3 position) {
        double ownDistance = ownerDistance(position, owner);
        for (CubeFace other : HORIZONTAL) {
            if (other != owner && ownDistance > ownerDistance(position, other) + EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static boolean touchesOwner(CubeFace owner, Segment segment) {
        return ownedBy(owner, segment.first())
            || ownedBy(owner, segment.second())
            || ownedBy(owner, midpoint(segment));
    }

    private static double ownerDistance(Vec3 position, CubeFace face) {
        return switch (face) {
            case WEST -> position.x();
            case EAST -> 1.0 - position.x();
            case NORTH -> position.z();
            case SOUTH -> 1.0 - position.z();
            default -> throw new IllegalArgumentException("Horizontal face required");
        };
    }

    private static void emitPolygon(List<Vec3> positions, List<MeshPrimitive> output) {
        positions = removeClosingDuplicate(positions);
        if (positions.size() < 3) {
            return;
        }
        if (positions.size() <= 4) {
            addPrimitive(positions, output);
            return;
        }
        for (int index = 1; index < positions.size() - 1; index++) {
            addPrimitive(List.of(positions.getFirst(), positions.get(index), positions.get(index + 1)), output);
        }
    }

    private static void addPrimitive(List<Vec3> positions, List<MeshPrimitive> output) {
        Vec3 geometricNormal = positions.get(1).subtract(positions.get(0))
            .cross(positions.get(2).subtract(positions.get(0)));
        if (geometricNormal.length() <= EPSILON) {
            return;
        }
        List<Vec3> oriented = positions;
        if (geometricNormal.dot(UP) < 0.0) {
            oriented = new ArrayList<>(positions);
            Collections.reverse(oriented);
        }
        List<MeshVertex> vertices = new ArrayList<>(4);
        oriented.forEach(position -> vertices.add(new MeshVertex(position, UP)));
        if (vertices.size() == 3) {
            // The terrain fluid buffer is QUADS. Repeating the final corner keeps
            // the triangle's area and winding while closing the four-vertex batch.
            vertices.add(vertices.getLast());
        }
        output.add(new MeshPrimitive(PrimitiveKind.FACE, CubeFace.UP, vertices));
    }

    private static List<Vec3> removeClosingDuplicate(List<Vec3> positions) {
        if (positions.size() > 1
            && positions.getFirst().subtract(positions.getLast()).length() <= EPSILON) {
            return new ArrayList<>(positions.subList(0, positions.size() - 1));
        }
        return positions;
    }

    private static Vec3 midpoint(Segment segment) {
        return segment.first().add(segment.second()).multiply(0.5);
    }

    private static Vec3 interpolate(Vec3 first, Vec3 second, double amount) {
        return first.add(second.subtract(first).multiply(amount));
    }

    private static void addUnique(List<Vec3> points, Vec3 candidate) {
        if (points.stream().noneMatch(point -> point.subtract(candidate).length() <= EPSILON)) {
            points.add(candidate);
        }
    }

    private static boolean validHeight(double height) {
        return Double.isFinite(height) && height >= 0.0 && height <= 1.0;
    }

    private record Segment(Vec3 first, Vec3 second) {
    }
}
