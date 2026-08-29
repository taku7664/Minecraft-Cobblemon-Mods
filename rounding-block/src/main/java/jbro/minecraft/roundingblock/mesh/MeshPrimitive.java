package jbro.minecraft.roundingblock.mesh;

import java.util.List;

public record MeshPrimitive(PrimitiveKind kind, CubeFace materialFace, List<MeshVertex> vertices) {
    public MeshPrimitive {
        if (kind == null || materialFace == null || vertices == null) {
            throw new IllegalArgumentException("kind, materialFace, and vertices are required");
        }
        if (vertices.size() != 3 && vertices.size() != 4) {
            throw new IllegalArgumentException("A primitive must contain three or four vertices");
        }
        vertices = List.copyOf(vertices);
    }
}
