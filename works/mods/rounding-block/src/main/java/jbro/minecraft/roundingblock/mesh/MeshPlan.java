package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record MeshPlan(List<MeshPrimitive> primitives) {
    private static final double MERGE_EPSILON = 1.0e-8;

    public MeshPlan {
        primitives = List.copyOf(primitives);
    }

    public MeshPlan withoutPlanarFaces(int planarFaceBits) {
        if (planarFaceBits == 0) {
            return this;
        }
        List<MeshPrimitive> result = new ArrayList<>(primitives.size());
        for (MeshPrimitive primitive : primitives) {
            if (primitive.kind() == PrimitiveKind.CONCAVE
                || !occupiesPlanarFaceHalf(primitive, planarFaceBits)) {
                result.add(primitive);
            }
        }
        return result.size() == primitives.size() ? this : new MeshPlan(result);
    }

    /**
     * Coalesces adjacent axis-aligned face rectangles after world-space assembly.
     * Curved template strips use {@link #compactLinearStrips()} during preparation instead.
     */
    public MeshPlan compactCoplanarFaces() {
        List<MeshPrimitive> untouched = new ArrayList<>();
        List<FaceRectangle> rectangles = new ArrayList<>();
        for (MeshPrimitive primitive : primitives) {
            FaceRectangle rectangle = FaceRectangle.from(primitive);
            if (rectangle == null) {
                untouched.add(primitive);
            } else {
                rectangles.add(rectangle);
            }
        }
        boolean changed = false;
        boolean merged;
        do {
            merged = false;
            outer:
            for (int first = 0; first < rectangles.size(); first++) {
                for (int second = first + 1; second < rectangles.size(); second++) {
                    FaceRectangle combined = rectangles.get(first).merge(rectangles.get(second));
                    if (combined == null) {
                        continue;
                    }
                    rectangles.set(first, combined);
                    rectangles.remove(second);
                    changed = true;
                    merged = true;
                    break outer;
                }
            }
        } while (merged);
        if (!changed) {
            return this;
        }
        List<MeshPrimitive> compacted = new ArrayList<>(untouched.size() + rectangles.size());
        compacted.addAll(untouched);
        for (FaceRectangle rectangle : rectangles) {
            compacted.add(rectangle.toPrimitive());
        }
        return new MeshPlan(compacted);
    }

    /** Joins sampled quads only when removing their shared edge preserves position and normal interpolation. */
    MeshPlan compactLinearStrips() {
        boolean changed = false;
        List<MeshPrimitive> compacted = primitives;
        do {
            MergePass pass = mergeLinearQuadPairs(compacted);
            if (!pass.changed()) {
                break;
            }
            compacted = pass.primitives();
            changed = true;
        } while (true);
        return changed ? new MeshPlan(compacted) : this;
    }

    private static MergePass mergeLinearQuadPairs(List<MeshPrimitive> input) {
        Map<EdgeKey, PrimitiveEdge> openEdges = new HashMap<>();
        MeshPrimitive[] replacements = new MeshPrimitive[input.size()];
        boolean[] removed = new boolean[input.size()];
        boolean[] paired = new boolean[input.size()];
        boolean changed = false;
        for (int primitiveIndex = 0; primitiveIndex < input.size(); primitiveIndex++) {
            MeshPrimitive primitive = input.get(primitiveIndex);
            if (primitive.vertices().size() != 4) {
                continue;
            }
            for (int edgeIndex = 0; edgeIndex < 4; edgeIndex++) {
                Vec3 first = primitive.vertices().get(edgeIndex).position();
                Vec3 second = primitive.vertices().get((edgeIndex + 1) & 3).position();
                EdgeKey key = new EdgeKey(first, second);
                PrimitiveEdge candidate = openEdges.get(key);
                if (candidate == null || paired[candidate.primitiveIndex()]) {
                    openEdges.put(key, new PrimitiveEdge(primitiveIndex, edgeIndex));
                    continue;
                }
                MeshPrimitive merged = mergeLinearStrip(
                    input.get(candidate.primitiveIndex()), candidate.edgeIndex(), primitive, edgeIndex
                );
                if (merged == null) {
                    continue;
                }
                replacements[candidate.primitiveIndex()] = merged;
                removed[primitiveIndex] = true;
                paired[candidate.primitiveIndex()] = true;
                paired[primitiveIndex] = true;
                changed = true;
                break;
            }
        }
        if (!changed) {
            return new MergePass(input, false);
        }
        List<MeshPrimitive> output = new ArrayList<>(input.size());
        for (int index = 0; index < input.size(); index++) {
            if (!removed[index]) {
                output.add(replacements[index] == null ? input.get(index) : replacements[index]);
            }
        }
        return new MergePass(output, true);
    }

    private static boolean occupiesPlanarFaceHalf(MeshPrimitive primitive, int planarFaceBits) {
        Vec3 centroid = Vec3.ZERO;
        for (MeshVertex vertex : primitive.vertices()) {
            centroid = centroid.add(vertex.position());
        }
        centroid = centroid.multiply(1.0 / primitive.vertices().size());
        for (CubeFace face : CubeFace.values()) {
            if ((planarFaceBits & (1 << face.ordinal())) == 0) {
                continue;
            }
            double coordinate = centroid.component(face.axis());
            if (face.sign() > 0 ? coordinate >= 0.5 : coordinate <= 0.5) {
                return true;
            }
        }
        return false;
    }

    private static MeshPrimitive mergeLinearStrip(
        MeshPrimitive first,
        int firstEdge,
        MeshPrimitive second,
        int secondEdge
    ) {
        if (first.kind() != second.kind() || first.materialFace() != second.materialFace()) {
            return null;
        }
        List<MeshVertex> a = first.vertices();
        List<MeshVertex> b = second.vertices();
        MeshVertex sharedA0 = a.get(firstEdge);
        MeshVertex sharedA1 = a.get((firstEdge + 1) & 3);
        MeshVertex sharedB0 = b.get(secondEdge);
        MeshVertex sharedB1 = b.get((secondEdge + 1) & 3);
        if (!sameVertex(sharedA0, sharedB1) || !sameVertex(sharedA1, sharedB0)) {
            return null;
        }
        MeshVertex outerA1 = a.get((firstEdge + 2) & 3);
        MeshVertex outerA0 = a.get((firstEdge + 3) & 3);
        MeshVertex outerB0 = b.get((secondEdge + 2) & 3);
        MeshVertex outerB1 = b.get((secondEdge + 3) & 3);
        if (!isLinearSample(outerA0, outerB0, sharedA0)
            || !isLinearSample(outerA1, outerB1, sharedA1)
            || !coplanar(outerA1.position(), outerA0.position(), outerB0.position(), outerB1.position())) {
            return null;
        }
        return new MeshPrimitive(
            first.kind(), first.materialFace(), List.of(outerA1, outerA0, outerB0, outerB1)
        );
    }

    private static boolean sameVertex(MeshVertex first, MeshVertex second) {
        return first.position().equals(second.position()) && first.normal().equals(second.normal());
    }

    private static boolean isLinearSample(MeshVertex first, MeshVertex second, MeshVertex sample) {
        double edgeX = second.position().x() - first.position().x();
        double edgeY = second.position().y() - first.position().y();
        double edgeZ = second.position().z() - first.position().z();
        double lengthSquared = edgeX * edgeX + edgeY * edgeY + edgeZ * edgeZ;
        if (lengthSquared <= MERGE_EPSILON * MERGE_EPSILON) {
            return false;
        }
        double sampleX = sample.position().x() - first.position().x();
        double sampleY = sample.position().y() - first.position().y();
        double sampleZ = sample.position().z() - first.position().z();
        double parameter = (sampleX * edgeX + sampleY * edgeY + sampleZ * edgeZ) / lengthSquared;
        if (parameter <= MERGE_EPSILON || parameter >= 1.0 - MERGE_EPSILON) {
            return false;
        }
        double inverse = 1.0 - parameter;
        return nearInterpolated(first.position().x(), second.position().x(), sample.position().x(), inverse, parameter)
            && nearInterpolated(first.position().y(), second.position().y(), sample.position().y(), inverse, parameter)
            && nearInterpolated(first.position().z(), second.position().z(), sample.position().z(), inverse, parameter)
            && nearInterpolated(first.normal().x(), second.normal().x(), sample.normal().x(), inverse, parameter)
            && nearInterpolated(first.normal().y(), second.normal().y(), sample.normal().y(), inverse, parameter)
            && nearInterpolated(first.normal().z(), second.normal().z(), sample.normal().z(), inverse, parameter);
    }

    private static boolean coplanar(Vec3 first, Vec3 second, Vec3 third, Vec3 fourth) {
        double abX = second.x() - first.x();
        double abY = second.y() - first.y();
        double abZ = second.z() - first.z();
        double acX = third.x() - first.x();
        double acY = third.y() - first.y();
        double acZ = third.z() - first.z();
        double adX = fourth.x() - first.x();
        double adY = fourth.y() - first.y();
        double adZ = fourth.z() - first.z();
        double normalX = abY * acZ - abZ * acY;
        double normalY = abZ * acX - abX * acZ;
        double normalZ = abX * acY - abY * acX;
        return Math.abs(normalX * adX + normalY * adY + normalZ * adZ) <= MERGE_EPSILON;
    }

    private static boolean nearInterpolated(
        double first,
        double second,
        double sample,
        double firstWeight,
        double secondWeight
    ) {
        return Math.abs(first * firstWeight + second * secondWeight - sample) <= MERGE_EPSILON;
    }

    private record FaceRectangle(
        CubeFace face,
        double plane,
        double uMin,
        double uMax,
        double vMin,
        double vMax
    ) {
        private static FaceRectangle from(MeshPrimitive primitive) {
            if (primitive.kind() != PrimitiveKind.FACE || primitive.vertices().size() != 4) {
                return null;
            }
            CubeFace face = primitive.materialFace();
            int uAxis = (face.axis() + 1) % 3;
            int vAxis = (face.axis() + 2) % 3;
            Vec3 expectedNormal = face.normal();
            double plane = primitive.vertices().getFirst().position().component(face.axis());
            double uMin = Double.POSITIVE_INFINITY;
            double uMax = Double.NEGATIVE_INFINITY;
            double vMin = Double.POSITIVE_INFINITY;
            double vMax = Double.NEGATIVE_INFINITY;
            for (MeshVertex vertex : primitive.vertices()) {
                if (vertex.normal().subtract(expectedNormal).length() > MERGE_EPSILON
                    || Math.abs(vertex.position().component(face.axis()) - plane) > MERGE_EPSILON) {
                    return null;
                }
                double u = vertex.position().component(uAxis);
                double v = vertex.position().component(vAxis);
                uMin = Math.min(uMin, u);
                uMax = Math.max(uMax, u);
                vMin = Math.min(vMin, v);
                vMax = Math.max(vMax, v);
            }
            if (uMax - uMin <= MERGE_EPSILON || vMax - vMin <= MERGE_EPSILON) {
                return null;
            }
            int corners = 0;
            for (MeshVertex vertex : primitive.vertices()) {
                int uSide = side(vertex.position().component(uAxis), uMin, uMax);
                int vSide = side(vertex.position().component(vAxis), vMin, vMax);
                if (uSide < 0 || vSide < 0) {
                    return null;
                }
                corners |= 1 << (uSide | (vSide << 1));
            }
            return corners == 0b1111 ? new FaceRectangle(face, plane, uMin, uMax, vMin, vMax) : null;
        }

        private FaceRectangle merge(FaceRectangle other) {
            if (face != other.face || !near(plane, other.plane)) {
                return null;
            }
            if (near(vMin, other.vMin) && near(vMax, other.vMax)
                && (near(uMax, other.uMin) || near(other.uMax, uMin))) {
                return new FaceRectangle(
                    face, plane, Math.min(uMin, other.uMin), Math.max(uMax, other.uMax), vMin, vMax
                );
            }
            if (near(uMin, other.uMin) && near(uMax, other.uMax)
                && (near(vMax, other.vMin) || near(other.vMax, vMin))) {
                return new FaceRectangle(
                    face, plane, uMin, uMax, Math.min(vMin, other.vMin), Math.max(vMax, other.vMax)
                );
            }
            return null;
        }

        private MeshPrimitive toPrimitive() {
            int uAxis = (face.axis() + 1) % 3;
            int vAxis = (face.axis() + 2) % 3;
            Vec3 normal = face.normal();
            List<MeshVertex> vertices = new ArrayList<>(4);
            for (double[] corner : new double[][]{
                {uMin, vMin}, {uMax, vMin}, {uMax, vMax}, {uMin, vMax}
            }) {
                Vec3 position = Vec3.ZERO
                    .withComponent(face.axis(), plane)
                    .withComponent(uAxis, corner[0])
                    .withComponent(vAxis, corner[1]);
                vertices.add(new MeshVertex(position, normal));
            }
            Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
                .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
            if (geometricNormal.dot(normal) < 0.0) {
                java.util.Collections.reverse(vertices);
            }
            return new MeshPrimitive(PrimitiveKind.FACE, face, vertices);
        }

        private static int side(double value, double min, double max) {
            if (near(value, min)) {
                return 0;
            }
            return near(value, max) ? 1 : -1;
        }

        private static boolean near(double first, double second) {
            return Math.abs(first - second) <= MERGE_EPSILON;
        }
    }

    private record MergePass(List<MeshPrimitive> primitives, boolean changed) {
    }

    private record PrimitiveEdge(int primitiveIndex, int edgeIndex) {
    }

    private record EdgeKey(Vec3 first, Vec3 second) {
        @Override
        public int hashCode() {
            return first.hashCode() + second.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EdgeKey edge
                && (first.equals(edge.first) && second.equals(edge.second)
                    || first.equals(edge.second) && second.equals(edge.first));
        }
    }
}
