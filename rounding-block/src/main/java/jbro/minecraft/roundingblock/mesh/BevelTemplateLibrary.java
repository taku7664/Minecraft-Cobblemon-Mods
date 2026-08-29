package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates a compact surface-net template for each lattice-vertex occupancy.
 *
 * <p>The old implementation split every sampling cube into six tetrahedra and
 * then clipped every triangle into eight octants. That multiplied even planar
 * areas into thousands of GPU quads per block. Surface nets emit at most one
 * vertex per active sampling cell and one quad per crossed sampling edge while
 * retaining the same continuous density field and smooth normals.</p>
 */
final class BevelTemplateLibrary {
    private static final double RADIUS = 3.0 / 32.0;
    private static final int SEGMENTS = 3;
    private static final double ISO_LEVEL = 0.5001;
    private static final double EPSILON = 1.0e-9;
    private static final double MINIMUM_EDGE = 1.0e-8;
    private static final int[][] CUBE_EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7},
        {0, 2}, {1, 3}, {4, 6}, {5, 7},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private final Map<Integer, List<MeshPrimitive>> cache = new ConcurrentHashMap<>();
    private final Map<Integer, List<RegionPrimitive>> partitionCache = new ConcurrentHashMap<>();
    private final double cellHeight;

    BevelTemplateLibrary() {
        this(1.0);
    }

    BevelTemplateLibrary(double cellHeight) {
        if (cellHeight <= 2.0 * RADIUS) {
            throw new IllegalArgumentException("Cell height must be wider than the bevel diameter");
        }
        this.cellHeight = cellHeight;
    }

    List<MeshPrimitive> template(int mask) {
        if (mask < 0 || mask > 255) {
            throw new IllegalArgumentException("Invalid bevel template mask=" + mask);
        }
        if (mask == 0 || mask == 255) {
            return List.of();
        }
        return cache.computeIfAbsent(mask, this::generate);
    }

    List<RegionPrimitive> partitionedTemplate(int mask) {
        return partitionCache.computeIfAbsent(mask, ignored -> partition(mask, template(mask)));
    }

    private static List<RegionPrimitive> partition(int mask, List<MeshPrimitive> primitives) {
        List<RegionPrimitive> result = new ArrayList<>();
        for (MeshPrimitive primitive : primitives) {
            for (int octant = 0; octant < 8; octant++) {
                for (MeshPrimitive clipped : clipToOctant(primitive, octant)) {
                    if (canonicalRegion(mask, clipped) == octant) {
                        result.add(new RegionPrimitive(octant, clipped));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    static List<MeshPrimitive> clipToOctant(MeshPrimitive primitive, int octant) {
        List<MeshPrimitive> output = new ArrayList<>();
        List<MeshVertex> polygon = new ArrayList<>(primitive.vertices());
        for (int axis = 0; axis < 3; axis++) {
            boolean positive = ((octant >> axis) & 1) != 0;
            polygon = clip(polygon, axis, 0.0, positive);
        }
        if (polygon.size() < 3) {
            return List.of();
        }
        if (polygon.size() <= 4) {
            addPolygon(polygon, output);
        } else {
            for (int index = 1; index < polygon.size() - 1; index++) {
                addPolygon(List.of(polygon.get(0), polygon.get(index), polygon.get(index + 1)), output);
            }
        }
        return output;
    }

    private static int canonicalRegion(int mask, MeshPrimitive primitive) {
        Vec3 centroid = Vec3.ZERO;
        for (MeshVertex vertex : primitive.vertices()) {
            centroid = centroid.add(vertex.position());
        }
        centroid = centroid.multiply(1.0 / primitive.vertices().size());
        int emptyFallback = -1;
        for (int candidate = 0; candidate < 8; candidate++) {
            boolean compatible = true;
            for (int axis = 0; axis < 3; axis++) {
                double coordinate = centroid.component(axis);
                if (Math.abs(coordinate) <= EPSILON) {
                    continue;
                }
                boolean positive = coordinate > 0.0;
                if ((((candidate >> axis) & 1) != 0) != positive) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) {
                continue;
            }
            if ((mask & (1 << candidate)) != 0) {
                return candidate;
            }
            if (emptyFallback < 0) {
                emptyFallback = candidate;
            }
        }
        if (emptyFallback >= 0) {
            return emptyFallback;
        }
        throw new IllegalStateException("Clipped surface has no compatible region for mask " + mask);
    }

    private List<MeshPrimitive> generate(int mask) {
        double[][] coordinates = {axisCoordinates(0), axisCoordinates(1), axisCoordinates(2)};
        int sampleCount = coordinates[0].length;
        int cellCount = sampleCount - 1;
        Sample[][][] samples = new Sample[sampleCount][sampleCount][sampleCount];
        for (int x = 0; x < sampleCount; x++) {
            for (int y = 0; y < sampleCount; y++) {
                for (int z = 0; z < sampleCount; z++) {
                    samples[x][y][z] = sample(mask, new Vec3(
                        coordinates[0][x], coordinates[1][y], coordinates[2][z]
                    ));
                }
            }
        }

        MeshVertex[][][] cellVertices = new MeshVertex[cellCount][cellCount][cellCount];
        for (int x = 0; x < cellCount; x++) {
            for (int y = 0; y < cellCount; y++) {
                for (int z = 0; z < cellCount; z++) {
                    cellVertices[x][y][z] = cellVertex(mask, samples, x, y, z);
                }
            }
        }

        List<MeshPrimitive> untrimmed = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            emitCrossedEdges(samples, cellVertices, axis, untrimmed);
        }
        List<MeshPrimitive> result = new ArrayList<>();
        for (MeshPrimitive primitive : untrimmed) {
            clipToTemplateCell(primitive, result);
        }
        return List.copyOf(result);
    }

    private double[] axisCoordinates(int axis) {
        double halfExtent = axis == 1 ? cellHeight * 0.5 : 0.5;
        double[] result = new double[2 * SEGMENTS + 5];
        result[0] = -halfExtent - RADIUS;
        result[1] = -halfExtent;
        for (int index = 0; index <= 2 * SEGMENTS; index++) {
            result[index + 2] = -RADIUS + 2.0 * RADIUS * index / (2.0 * SEGMENTS);
        }
        result[result.length - 2] = halfExtent;
        result[result.length - 1] = halfExtent + RADIUS;
        return result;
    }

    private static MeshVertex cellVertex(int mask, Sample[][][] samples, int x, int y, int z) {
        Sample[] corners = new Sample[8];
        boolean inside = false;
        boolean outside = false;
        for (int corner = 0; corner < 8; corner++) {
            Sample value = samples[x + (corner & 1)][y + ((corner >> 1) & 1)][z + ((corner >> 2) & 1)];
            corners[corner] = value;
            if (value.density() > ISO_LEVEL) {
                inside = true;
            } else {
                outside = true;
            }
        }
        if (!inside || !outside) {
            return null;
        }

        Vec3 positionSum = Vec3.ZERO;
        Vec3 crossingNormalSum = Vec3.ZERO;
        Vec3 firstCrossingNormal = null;
        int crossings = 0;
        for (int[] edge : CUBE_EDGES) {
            Sample first = corners[edge[0]];
            Sample second = corners[edge[1]];
            boolean firstInside = first.density() > ISO_LEVEL;
            if (firstInside == (second.density() > ISO_LEVEL)) {
                continue;
            }
            double amount = (ISO_LEVEL - first.density()) / (second.density() - first.density());
            positionSum = positionSum.add(interpolate(first.position(), second.position(), amount));
            Vec3 edgeOutward = second.position().subtract(first.position())
                .multiply(firstInside ? 1.0 : -1.0)
                .normalize();
            crossingNormalSum = crossingNormalSum.add(edgeOutward);
            if (firstCrossingNormal == null) {
                firstCrossingNormal = edgeOutward;
            }
            crossings++;
        }
        if (crossings == 0) {
            return null;
        }
        Vec3 position = positionSum.multiply(1.0 / crossings);
        Vec3 normal = sample(mask, position).outward();
        if (normal.length() <= EPSILON) {
            normal = crossingNormalSum.length() > EPSILON ? crossingNormalSum : firstCrossingNormal;
        }
        normal = normal.normalize();
        position = snapAxisAlignedPosition(position, normal);
        return new MeshVertex(position, normal);
    }

    private static Vec3 snapAxisAlignedPosition(Vec3 position, Vec3 normal) {
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(Math.abs(normal.component(axis)) - 1.0) <= 1.0e-9) {
                return position.withComponent(axis, 0.0);
            }
        }
        return position;
    }

    private static void emitCrossedEdges(
        Sample[][][] samples,
        MeshVertex[][][] cells,
        int axis,
        List<MeshPrimitive> output
    ) {
        int sampleCount = samples.length;
        int cellCount = cells.length;
        int uAxis = (axis + 1) % 3;
        int vAxis = (axis + 2) % 3;
        int[] edge = new int[3];
        for (edge[axis] = 0; edge[axis] < cellCount; edge[axis]++) {
            for (edge[uAxis] = 1; edge[uAxis] < cellCount; edge[uAxis]++) {
                for (edge[vAxis] = 1; edge[vAxis] < cellCount; edge[vAxis]++) {
                    int[] next = edge.clone();
                    next[axis]++;
                    Sample first = samples[edge[0]][edge[1]][edge[2]];
                    Sample second = samples[next[0]][next[1]][next[2]];
                    if ((first.density() > ISO_LEVEL) == (second.density() > ISO_LEVEL)) {
                        continue;
                    }

                    int[][] indices = new int[4][3];
                    for (int index = 0; index < 4; index++) {
                        indices[index] = edge.clone();
                    }
                    indices[0][uAxis]--;
                    indices[0][vAxis]--;
                    indices[1][vAxis]--;
                    indices[3][uAxis]--;

                    List<MeshVertex> vertices = new ArrayList<>(4);
                    boolean complete = true;
                    for (int[] index : indices) {
                        MeshVertex vertex = cells[index[0]][index[1]][index[2]];
                        if (vertex == null) {
                            complete = false;
                            break;
                        }
                        vertices.add(vertex);
                    }
                    if (complete) {
                        addQuad(vertices, output);
                    }
                }
            }
        }
    }

    private static void addQuad(List<MeshVertex> vertices, List<MeshPrimitive> output) {
        Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
            .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
        if (geometricNormal.length() <= EPSILON) {
            return;
        }
        Vec3 averageNormal = vertices.stream().map(MeshVertex::normal).reduce(Vec3.ZERO, Vec3::add).normalize();
        if (geometricNormal.dot(averageNormal) < 0.0) {
            vertices = List.of(vertices.get(0), vertices.get(3), vertices.get(2), vertices.get(1));
        }
        CubeFace material = dominantFace(averageNormal);
        boolean flat = vertices.stream().allMatch(vertex -> vertex.normal().dot(material.normal()) > 1.0 - 1.0e-6);
        output.add(new MeshPrimitive(flat ? PrimitiveKind.FACE : PrimitiveKind.EDGE, material, vertices));
    }

    private void clipToTemplateCell(MeshPrimitive primitive, List<MeshPrimitive> output) {
        List<MeshVertex> polygon = new ArrayList<>(primitive.vertices());
        for (int axis = 0; axis < 3; axis++) {
            double halfExtent = axis == 1 ? cellHeight * 0.5 : 0.5;
            polygon = clip(polygon, axis, -halfExtent, true);
            polygon = clip(polygon, axis, halfExtent, false);
        }
        if (polygon.size() < 3) {
            return;
        }
        if (polygon.size() <= 4) {
            addPolygon(polygon, output);
            return;
        }
        for (int index = 1; index < polygon.size() - 1; index++) {
            addPolygon(List.of(polygon.get(0), polygon.get(index), polygon.get(index + 1)), output);
        }
    }

    private static List<MeshVertex> clip(List<MeshVertex> input, int axis, double boundary, boolean keepGreater) {
        if (input.isEmpty()) {
            return List.of();
        }
        List<MeshVertex> output = new ArrayList<>(input.size() + 1);
        MeshVertex previous = input.getLast();
        boolean previousInside = inside(previous.position().component(axis), boundary, keepGreater);
        for (MeshVertex current : input) {
            boolean currentInside = inside(current.position().component(axis), boundary, keepGreater);
            if (currentInside != previousInside) {
                double first = previous.position().component(axis);
                double amount = (boundary - first) / (current.position().component(axis) - first);
                addUnique(output, new MeshVertex(
                    interpolate(previous.position(), current.position(), amount),
                    interpolate(previous.normal(), current.normal(), amount).normalize()
                ));
            }
            if (currentInside) {
                addUnique(output, current);
            }
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(double coordinate, double boundary, boolean keepGreater) {
        return keepGreater ? coordinate >= boundary - EPSILON : coordinate <= boundary + EPSILON;
    }

    private static void addUnique(List<MeshVertex> vertices, MeshVertex candidate) {
        if (vertices.stream().noneMatch(existing ->
            existing.position().subtract(candidate.position()).length() <= MINIMUM_EDGE
        )) {
            vertices.add(candidate);
        }
    }

    private static void addPolygon(List<MeshVertex> vertices, List<MeshPrimitive> output) {
        if (vertices.size() < 3 || vertices.stream().anyMatch(vertex -> !Double.isFinite(vertex.position().x())
            || !Double.isFinite(vertex.position().y()) || !Double.isFinite(vertex.position().z()))) {
            return;
        }
        Vec3 geometricNormal = vertices.get(1).position().subtract(vertices.get(0).position())
            .cross(vertices.get(2).position().subtract(vertices.get(0).position()));
        if (geometricNormal.length() <= EPSILON) {
            return;
        }
        Vec3 averageNormal = vertices.stream().map(MeshVertex::normal).reduce(Vec3.ZERO, Vec3::add).normalize();
        List<MeshVertex> oriented = vertices;
        if (geometricNormal.dot(averageNormal) < 0.0) {
            oriented = new ArrayList<>(vertices);
            java.util.Collections.reverse(oriented);
        }
        CubeFace material = dominantFace(averageNormal);
        boolean flat = oriented.stream().allMatch(vertex -> vertex.normal().dot(material.normal()) > 1.0 - 1.0e-6);
        output.add(new MeshPrimitive(flat ? PrimitiveKind.FACE : PrimitiveKind.EDGE, material, oriented));
    }

    private static Sample sample(int mask, Vec3 position) {
        return smoothSample(mask, position);
    }

    private static Sample smoothSample(int mask, Vec3 position) {
        AxisWeight x = weight(position.x());
        AxisWeight y = weight(position.y());
        AxisWeight z = weight(position.z());
        double density = 0.0;
        double gradientX = 0.0;
        double gradientY = 0.0;
        double gradientZ = 0.0;
        for (int octant = 0; octant < 8; octant++) {
            if ((mask & (1 << octant)) == 0) {
                continue;
            }
            boolean positiveX = (octant & 1) != 0;
            boolean positiveY = (octant & 2) != 0;
            boolean positiveZ = (octant & 4) != 0;
            double wx = x.value(positiveX);
            double wy = y.value(positiveY);
            double wz = z.value(positiveZ);
            density += wx * wy * wz;
            gradientX += x.derivative(positiveX) * wy * wz;
            gradientY += wx * y.derivative(positiveY) * wz;
            gradientZ += wx * wy * z.derivative(positiveZ);
        }
        return new Sample(position, density, new Vec3(-gradientX, -gradientY, -gradientZ));
    }

    private static AxisWeight weight(double coordinate) {
        if (coordinate <= -RADIUS) {
            return new AxisWeight(0.0, 0.0);
        }
        if (coordinate >= RADIUS) {
            return new AxisWeight(1.0, 0.0);
        }
        double angle = Math.PI * coordinate / (2.0 * RADIUS);
        return new AxisWeight(
            0.5 + 0.5 * Math.sin(angle),
            Math.PI * Math.cos(angle) / (4.0 * RADIUS)
        );
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
        return CubeFace.of(axis, normal.component(axis) >= 0.0 ? 1 : -1);
    }

    private static Vec3 interpolate(Vec3 first, Vec3 second, double amount) {
        return first.add(second.subtract(first).multiply(amount));
    }

    private record AxisWeight(double positive, double positiveDerivative) {
        private double value(boolean positiveSide) {
            return positiveSide ? positive : 1.0 - positive;
        }

        private double derivative(boolean positiveSide) {
            return positiveSide ? positiveDerivative : -positiveDerivative;
        }
    }

    private record Sample(Vec3 position, double density, Vec3 outward) {
    }

    record RegionPrimitive(int octant, MeshPrimitive primitive) {
    }
}
