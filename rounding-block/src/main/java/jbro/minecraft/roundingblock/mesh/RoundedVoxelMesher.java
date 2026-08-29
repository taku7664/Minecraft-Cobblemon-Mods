package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one block's share of a topology-aware bevel surface.
 *
 * <p>Every block emits the eight octants that lie inside its own bounds. Each
 * octant is selected from the same world-lattice 8-bit occupancy mask, so an
 * edge or vertex is never independently capped by neighboring blocks.</p>
 */
public final class RoundedVoxelMesher {
    private static final BevelTemplateLibrary TEMPLATES = new BevelTemplateLibrary();

    public MeshPlan mesh(VoxelNeighborhood neighborhood) {
        if (!neighborhood.occupied(0, 0, 0)) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        for (int corner = 0; corner < 8; corner++) {
            int cornerX = corner & 1;
            int cornerY = (corner >> 1) & 1;
            int cornerZ = (corner >> 2) & 1;
            int mask = vertexMask(neighborhood, cornerX, cornerY, cornerZ);
            int currentOctant = (1 - cornerX) | ((1 - cornerY) << 1) | ((1 - cornerZ) << 2);
            Vec3 translation = new Vec3(cornerX, cornerY, cornerZ);
            for (BevelTemplateLibrary.RegionPrimitive region : TEMPLATES.partitionedTemplate(mask)) {
                MeshPrimitive primitive = region.primitive();
                boolean solidRegion = (mask & (1 << region.octant())) != 0;
                int owner = solidRegion
                    ? region.octant()
                    : concaveOwner(mask, region.octant(), primitive.materialFace());
                if (owner == currentOctant) {
                    MeshPrimitive tagged = solidRegion ? primitive : withKind(primitive, PrimitiveKind.CONCAVE);
                    output.add(translate(tagged, translation));
                }
            }
        }
        return new MeshPlan(output);
    }

    private static int concaveOwner(int mask, int surfaceOctant, CubeFace materialFace) {
        int inwardBit = materialFace.sign() > 0 ? 0 : 1;
        int bestOctant = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int candidate = 0; candidate < 8; candidate++) {
            if ((mask & (1 << candidate)) == 0) {
                continue;
            }
            int candidateAxisBit = (candidate >> materialFace.axis()) & 1;
            int axisPenalty = candidateAxisBit == inwardBit ? 0 : 1;
            int distance = Integer.bitCount(candidate ^ surfaceOctant);
            int score = axisPenalty * 100 + distance * 10 + candidate;
            if (score < bestScore) {
                bestScore = score;
                bestOctant = candidate;
            }
        }
        if (bestOctant < 0) {
            throw new IllegalStateException("Concave surface has no solid owner for mask " + mask);
        }
        return bestOctant;
    }

    private static int vertexMask(VoxelNeighborhood neighborhood, int cornerX, int cornerY, int cornerZ) {
        int mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            int octantX = octant & 1;
            int octantY = (octant >> 1) & 1;
            int octantZ = (octant >> 2) & 1;
            int x = cornerX + octantX - 1;
            int y = cornerY + octantY - 1;
            int z = cornerZ + octantZ - 1;
            if (neighborhood.occupied(x, y, z)) {
                mask |= 1 << octant;
            }
        }
        return mask;
    }

    private static MeshPrimitive translate(MeshPrimitive primitive, Vec3 translation) {
        List<MeshVertex> vertices = new ArrayList<>(primitive.vertices().size());
        for (MeshVertex vertex : primitive.vertices()) {
            vertices.add(new MeshVertex(vertex.position().add(translation), vertex.normal()));
        }
        return new MeshPrimitive(primitive.kind(), primitive.materialFace(), vertices);
    }

    private static MeshPrimitive withKind(MeshPrimitive primitive, PrimitiveKind kind) {
        return new MeshPrimitive(kind, primitive.materialFace(), primitive.vertices());
    }
}
