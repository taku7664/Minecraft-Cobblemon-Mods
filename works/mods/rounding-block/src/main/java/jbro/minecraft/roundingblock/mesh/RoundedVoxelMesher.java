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
    private static final double HALF_HEIGHT = 0.5;
    private static final double MICRO_SIZE = 0.5;
    private final TemplateSet templates;

    public RoundedVoxelMesher() {
        this.templates = DefaultTemplates.INSTANCE;
    }

    public RoundedVoxelMesher(double radius, int segments) {
        this.templates = radius == BevelTemplateLibrary.DEFAULT_RADIUS
            && segments == BevelTemplateLibrary.DEFAULT_SEGMENTS
            ? DefaultTemplates.INSTANCE
            : createTemplates(radius, segments);
    }

    public MeshPlan mesh(VoxelNeighborhood neighborhood) {
        if (!neighborhood.occupied(0, 0, 0)) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        emitCell(neighborhood::occupied, 0, 0, 0, 1.0, 1.0, 1.0, templates.full(), output);
        return new MeshPlan(output).compactCoplanarFaces();
    }

    public MeshPlan mesh(VerticalVoxelNeighborhood neighborhood) {
        if (!neighborhood.hasOccupiedCenterCell()) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        for (int halfY = 0; halfY <= 1; halfY++) {
            if (neighborhood.occupied(0, halfY, 0)) {
                emitCell(
                    neighborhood::occupied, 0, halfY, 0,
                    1.0, HALF_HEIGHT, 1.0, templates.halfHeight(), output
                );
            }
        }
        return new MeshPlan(output).compactCoplanarFaces();
    }

    public MeshPlan mesh(MicroVoxelNeighborhood neighborhood) {
        if (!neighborhood.hasOccupiedCenterCell()) {
            return new MeshPlan(List.of());
        }
        List<MeshPrimitive> output = new ArrayList<>();
        for (int z = 0; z <= 1; z++) {
            for (int y = 0; y <= 1; y++) {
                for (int x = 0; x <= 1; x++) {
                    if (neighborhood.occupied(x, y, z)) {
                        emitCell(
                            neighborhood::occupied, x, y, z,
                            MICRO_SIZE, MICRO_SIZE, MICRO_SIZE, templates.micro(), output
                        );
                    }
                }
            }
        }
        return new MeshPlan(output).compactCoplanarFaces();
    }

    private static void emitCell(
        Occupancy occupancy,
        int cellX,
        int cellY,
        int cellZ,
        double cellWidth,
        double cellHeight,
        double cellDepth,
        BevelTemplateLibrary templates,
        List<MeshPrimitive> output
    ) {
        for (int corner = 0; corner < 8; corner++) {
            int cornerX = corner & 1;
            int cornerY = (corner >> 1) & 1;
            int cornerZ = (corner >> 2) & 1;
            int mask = vertexMask(occupancy, cellX + cornerX, cellY + cornerY, cellZ + cornerZ);
            int currentOctant = (1 - cornerX) | ((1 - cornerY) << 1) | ((1 - cornerZ) << 2);
            List<MeshPrimitive> primitives = templates.ownedTemplate(mask, currentOctant);
            if (primitives.isEmpty()) {
                continue;
            }
            double translationX = cellX * cellWidth;
            double translationY = cellY * cellHeight;
            double translationZ = cellZ * cellDepth;
            if (translationX == 0.0 && translationY == 0.0 && translationZ == 0.0) {
                output.addAll(primitives);
                continue;
            }
            Vec3 translation = new Vec3(translationX, translationY, translationZ);
            for (MeshPrimitive primitive : primitives) {
                output.add(translate(primitive, translation, primitive.kind()));
            }
        }
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

    private static MeshPrimitive translate(
        MeshPrimitive primitive,
        Vec3 translation,
        PrimitiveKind kind
    ) {
        List<MeshVertex> vertices = new ArrayList<>(primitive.vertices().size());
        for (MeshVertex vertex : primitive.vertices()) {
            vertices.add(new MeshVertex(vertex.position().add(translation), vertex.normal()));
        }
        return new MeshPrimitive(kind, primitive.materialFace(), vertices);
    }

    private static TemplateSet createTemplates(double radius, int segments) {
        return new TemplateSet(
            new BevelTemplateLibrary(radius, segments, 1.0, 1.0, 1.0),
            new BevelTemplateLibrary(radius, segments, 1.0, HALF_HEIGHT, 1.0),
            new BevelTemplateLibrary(radius, segments, MICRO_SIZE, MICRO_SIZE, MICRO_SIZE)
        );
    }

    private static final class DefaultTemplates {
        private static final TemplateSet INSTANCE = createTemplates(
            BevelTemplateLibrary.DEFAULT_RADIUS,
            BevelTemplateLibrary.DEFAULT_SEGMENTS
        );
    }

    private record TemplateSet(
        BevelTemplateLibrary full,
        BevelTemplateLibrary halfHeight,
        BevelTemplateLibrary micro
    ) {
    }

    @FunctionalInterface
    private interface Occupancy {
        boolean occupied(int x, int y, int z);
    }
}
