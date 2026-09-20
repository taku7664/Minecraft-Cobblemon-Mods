package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.List;

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
            if ((primitive.kind() == PrimitiveKind.CONCAVE || primitive.kind() == PrimitiveKind.CONTACT)
                || !occupiesPlanarFaceHalf(primitive, planarFaceBits)) {
                result.add(primitive);
            }
        }
        return result.size() == primitives.size() ? this : new MeshPlan(result);
    }

    /**
     * Joins adjacent axis-aligned flat rectangles while leaving every curved
     * or concave primitive untouched. This preserves the sampled round surface
     * but avoids uploading a grid of redundant quads for the flat center of a
     * face.
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
                double u = vertex.position().component(uAxis);
                double v = vertex.position().component(vAxis);
                int uSide = side(u, uMin, uMax);
                int vSide = side(v, vMin, vMax);
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
            if (near(vMin, other.vMin) && near(vMax, other.vMax)) {
                if (near(uMax, other.uMin) || near(other.uMax, uMin)) {
                    return new FaceRectangle(
                        face, plane, Math.min(uMin, other.uMin), Math.max(uMax, other.uMax), vMin, vMax
                    );
                }
            }
            if (near(uMin, other.uMin) && near(uMax, other.uMax)) {
                if (near(vMax, other.vMin) || near(other.vMax, vMin)) {
                    return new FaceRectangle(
                        face, plane, uMin, uMax, Math.min(vMin, other.vMin), Math.max(vMax, other.vMax)
                    );
                }
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
}
