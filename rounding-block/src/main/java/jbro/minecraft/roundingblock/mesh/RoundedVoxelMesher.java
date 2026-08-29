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
    private static final double HALF_HEIGHT = 0.5;
    private static final BevelTemplateLibrary HALF_HEIGHT_TEMPLATES = new BevelTemplateLibrary(HALF_HEIGHT);

    public MeshPlan mesh(VoxelNeighborhood neighborhood) {
        if (!neighborhood.occupied(0, 0, 0)) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        emitCell(neighborhood::occupied, 0, 1.0, TEMPLATES, output);
        return new MeshPlan(output);
    }

    public MeshPlan mesh(VerticalVoxelNeighborhood neighborhood) {
        if (!neighborhood.hasOccupiedCenterCell()) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        for (int halfY = 0; halfY <= 1; halfY++) {
            if (neighborhood.occupied(0, halfY, 0)) {
                emitCell(neighborhood::occupied, halfY, HALF_HEIGHT, HALF_HEIGHT_TEMPLATES, output);
            }
        }
        return new MeshPlan(output);
    }

    private static void emitCell(
        Occupancy occupancy,
        int cellY,
        double cellHeight,
        BevelTemplateLibrary templates,
        List<MeshPrimitive> output
    ) {
        for (int corner = 0; corner < 8; corner++) {
            int cornerX = corner & 1;
            int cornerY = (corner >> 1) & 1;
            int cornerZ = (corner >> 2) & 1;
            int mask = vertexMask(occupancy, cornerX, cellY + cornerY, cornerZ);
            int currentOctant = (1 - cornerX) | ((1 - cornerY) << 1) | ((1 - cornerZ) << 2);
            Vec3 translation = new Vec3(cornerX, (cellY + cornerY) * cellHeight, cornerZ);
            for (BevelTemplateLibrary.RegionPrimitive region : templates.partitionedTemplate(mask)) {
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

    private static int vertexMask(Occupancy occupancy, int cornerX, int cornerY, int cornerZ) {
        int mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            int octantX = octant & 1;
            int octantY = (octant >> 1) & 1;
            int octantZ = (octant >> 2) & 1;
            int x = cornerX + octantX - 1;
            int y = cornerY + octantY - 1;
            int z = cornerZ + octantZ - 1;
            if (occupancy.occupied(x, y, z)) {
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

    @FunctionalInterface
    private interface Occupancy {
        boolean occupied(int x, int y, int z);
    }
}
