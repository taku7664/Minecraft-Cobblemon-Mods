package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.List;

public record MeshPlan(List<MeshPrimitive> primitives) {
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
}
