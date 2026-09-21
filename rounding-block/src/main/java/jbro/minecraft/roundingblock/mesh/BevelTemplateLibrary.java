package jbro.minecraft.roundingblock.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a compact surface-net template for each lattice-vertex occupancy.
 *
 * <p>The old implementation split every sampling cube into six tetrahedra and
 * then clipped every triangle into eight octants. That multiplied even planar
 * areas into thousands of GPU quads per block. Surface nets emit one quad per
 * crossed sampling edge while retaining the same continuous density field and
 * smooth normals. Ambiguous cells keep separate vertices for separate surface
 * sheets so diagonal contacts cannot collapse into a non-manifold star.</p>
 */
final class BevelTemplateLibrary {
    static final double DEFAULT_RADIUS = 3.0 / 32.0;
    static final int DEFAULT_SEGMENTS = 3;
    private static final double ISO_LEVEL = 0.5001;
    private static final double EDGE_COUPLING = 1.0;
    private static final double VERTEX_MARGIN = 0.05;
    private static final double EPSILON = 1.0e-9;
    private static final double MINIMUM_EDGE = 1.0e-8;
    private static final int[][] CUBE_EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7},
        {0, 2}, {1, 3}, {4, 6}, {5, 7},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    private static final int[][] CUBE_FACES = {
        {0, 1, 3, 2}, {4, 5, 7, 6},
        {0, 1, 5, 4}, {2, 3, 7, 6},
        {0, 2, 6, 4}, {1, 3, 7, 5}
    };

    private final List<MeshPrimitive>[] templates;
    private final List<RegionPrimitive>[] partitionedTemplates;
    private final double[] cellSizes;
    private final double radius;
    private final int segments;

    BevelTemplateLibrary() {
        this(DEFAULT_RADIUS, DEFAULT_SEGMENTS, 1.0, 1.0, 1.0);
    }

    BevelTemplateLibrary(double cellHeight) {
        this(DEFAULT_RADIUS, DEFAULT_SEGMENTS, 1.0, cellHeight, 1.0);
    }

    @SuppressWarnings("unchecked")
    BevelTemplateLibrary(
        double radius,
        int segments,
        double cellWidth,
        double cellHeight,
        double cellDepth
    ) {
        if (!Double.isFinite(radius) || radius <= 0.0) {
            throw new IllegalArgumentException("Radius must be finite and positive");
        }
        if (segments <= 0) {
            throw new IllegalArgumentException("Segments must be positive");
        }
        this.radius = radius;
        this.segments = segments;
        this.cellSizes = new double[]{cellWidth, cellHeight, cellDepth};
        for (double cellSize : cellSizes) {
            if (cellSize <= 2.0 * radius) {
                throw new IllegalArgumentException("Cell extent must be wider than the bevel diameter");
            }
        }
        this.templates = (List<MeshPrimitive>[]) new List<?>[256];
        this.partitionedTemplates = (List<RegionPrimitive>[]) new List<?>[256];
        for (int mask = 0; mask <= 255; mask++) {
            templates[mask] = mask == 0 || mask == 255 ? List.of() : generate(mask);
        }
        for (int mask = 0; mask <= 255; mask++) {
            partitionedTemplates[mask] = partition(mask, templates[mask]);
        }
    }

    List<MeshPrimitive> template(int mask) {
        if (mask < 0 || mask > 255) {
            throw new IllegalArgumentException("Invalid bevel template mask=" + mask);
        }
        if (mask == 0 || mask == 255) {
            return List.of();
        }
        return templates[mask];
    }

    List<RegionPrimitive> partitionedTemplate(int mask) {
        if (mask < 0 || mask > 255) {
            throw new IllegalArgumentException("Invalid bevel template mask=" + mask);
        }
        return partitionedTemplates[mask];
    }

    boolean isFullyPrepared() {
        for (int mask = 0; mask <= 255; mask++) {
            if (templates[mask] == null || partitionedTemplates[mask] == null) {
                return false;
            }
        }
        return true;
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

        CellSurface[][][] cellSurfaces = new CellSurface[cellCount][cellCount][cellCount];
        for (int x = 0; x < cellCount; x++) {
            for (int y = 0; y < cellCount; y++) {
                for (int z = 0; z < cellCount; z++) {
                    cellSurfaces[x][y][z] = cellSurface(mask, samples, x, y, z);
                }
            }
        }

        List<MeshPrimitive> untrimmed = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            emitCrossedEdges(samples, cellSurfaces, axis, untrimmed);
        }
        List<MeshPrimitive> result = new ArrayList<>();
        for (MeshPrimitive primitive : untrimmed) {
            clipToTemplateCell(primitive, result);
        }
        return List.copyOf(result);
    }

    private double[] axisCoordinates(int axis) {
        double halfExtent = cellSizes[axis] * 0.5;
        double[] result = new double[2 * segments + 5];
        result[0] = -halfExtent - radius;
        result[1] = -halfExtent;
        for (int index = 0; index <= 2 * segments; index++) {
            result[index + 2] = -radius + 2.0 * radius * index / (2.0 * segments);
        }
        if (segments > 1) {
            // Keep the shared grid size unchanged while resolving the narrow
            // diagonal neck on both sides of every template boundary.
            double nearCenter = radius / (4.0 * segments);
            result[segments + 1] = -nearCenter;
            result[segments + 3] = nearCenter;
        }
        result[result.length - 2] = halfExtent;
        result[result.length - 1] = halfExtent + radius;
        return result;
    }

    private CellSurface cellSurface(int mask, Sample[][][] samples, int x, int y, int z) {
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

        boolean[] crossed = new boolean[CUBE_EDGES.length];
        Vec3[] crossingPositions = new Vec3[CUBE_EDGES.length];
        Vec3[] crossingNormals = new Vec3[CUBE_EDGES.length];
        int[] parents = new int[CUBE_EDGES.length];
        for (int edgeIndex = 0; edgeIndex < CUBE_EDGES.length; edgeIndex++) {
            int[] edge = CUBE_EDGES[edgeIndex];
            Sample first = corners[edge[0]];
            Sample second = corners[edge[1]];
            boolean firstInside = first.density() > ISO_LEVEL;
            if (firstInside == (second.density() > ISO_LEVEL)) {
                continue;
            }
            double amount = (ISO_LEVEL - first.density()) / (second.density() - first.density());
            crossed[edgeIndex] = true;
            parents[edgeIndex] = edgeIndex;
            crossingPositions[edgeIndex] = interpolate(first.position(), second.position(), amount);
            crossingNormals[edgeIndex] = second.position().subtract(first.position())
                .multiply(firstInside ? 1.0 : -1.0)
                .normalize();
        }

        for (int[] face : CUBE_FACES) {
            int[] faceEdges = new int[4];
            int crossingCount = 0;
            for (int index = 0; index < 4; index++) {
                int edgeIndex = localEdgeIndex(face[index], face[(index + 1) % 4]);
                if (crossed[edgeIndex]) {
                    faceEdges[crossingCount++] = edgeIndex;
                }
            }
            if (crossingCount == 2) {
                union(parents, faceEdges[0], faceEdges[1]);
            } else if (crossingCount == 4) {
                // Pair around outside corners: diagonal solids stay connected without
                // collapsing both surface sheets into a single non-manifold vertex.
                for (int index = 0; index < 4; index++) {
                    int corner = face[index];
                    if (corners[corner].density() <= ISO_LEVEL) {
                        int previousEdge = localEdgeIndex(face[(index + 3) % 4], corner);
                        int nextEdge = localEdgeIndex(corner, face[(index + 1) % 4]);
                        union(parents, previousEdge, nextEdge);
                    }
                }
            }
        }

        MeshVertex[] componentVertices = new MeshVertex[CUBE_EDGES.length];
        for (int edgeIndex = 0; edgeIndex < CUBE_EDGES.length; edgeIndex++) {
            if (!crossed[edgeIndex] || find(parents, edgeIndex) != edgeIndex) {
                continue;
            }
            Vec3 positionSum = Vec3.ZERO;
            Vec3 normalSum = Vec3.ZERO;
            Vec3 fallbackNormal = null;
            int crossingCount = 0;
            for (int candidate = 0; candidate < CUBE_EDGES.length; candidate++) {
                if (crossed[candidate] && find(parents, candidate) == edgeIndex) {
                    positionSum = positionSum.add(crossingPositions[candidate]);
                    normalSum = normalSum.add(crossingNormals[candidate]);
                    fallbackNormal = crossingNormals[candidate];
                    crossingCount++;
                }
            }
            Vec3 position = positionSum.multiply(1.0 / crossingCount);
            Vec3 normal = sample(mask, position).outward();
            if (normal.length() <= EPSILON) {
                normal = normalSum.length() > EPSILON ? normalSum : fallbackNormal;
            }
            normal = normal.normalize();
            position = snapAxisAlignedPosition(position, normal);
            componentVertices[edgeIndex] = new MeshVertex(position, normal);
        }

        MeshVertex[] edgeVertices = new MeshVertex[CUBE_EDGES.length];
        for (int edgeIndex = 0; edgeIndex < CUBE_EDGES.length; edgeIndex++) {
            if (crossed[edgeIndex]) {
                edgeVertices[edgeIndex] = componentVertices[find(parents, edgeIndex)];
            }
        }
        return new CellSurface(edgeVertices);
    }

    private static int find(int[] parents, int value) {
        int root = value;
        while (parents[root] != root) {
            root = parents[root];
        }
        while (parents[value] != value) {
            int next = parents[value];
            parents[value] = root;
            value = next;
        }
        return root;
    }

    private static void union(int[] parents, int first, int second) {
        int firstRoot = find(parents, first);
        int secondRoot = find(parents, second);
        if (firstRoot != secondRoot) {
            parents[secondRoot] = firstRoot;
        }
    }

    private static int localEdgeIndex(int firstCorner, int secondCorner) {
        for (int edgeIndex = 0; edgeIndex < CUBE_EDGES.length; edgeIndex++) {
            int[] edge = CUBE_EDGES[edgeIndex];
            if ((edge[0] == firstCorner && edge[1] == secondCorner)
                || (edge[0] == secondCorner && edge[1] == firstCorner)) {
                return edgeIndex;
            }
        }
        throw new IllegalArgumentException("Corners do not form a cube edge");
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
        CellSurface[][][] cells,
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
                        CellSurface surface = cells[index[0]][index[1]][index[2]];
                        int firstCorner = (edge[0] - index[0])
                            | ((edge[1] - index[1]) << 1)
                            | ((edge[2] - index[2]) << 2);
                        int secondCorner = firstCorner | (1 << axis);
                        int localEdge = localEdgeIndex(firstCorner, secondCorner);
                        MeshVertex vertex = surface == null ? null : surface.vertexForEdge(localEdge);
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

    private record CellSurface(MeshVertex[] edgeVertices) {
        MeshVertex vertexForEdge(int edgeIndex) {
            return edgeVertices[edgeIndex];
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
            double halfExtent = cellSizes[axis] * 0.5;
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

    private Sample sample(int mask, Vec3 position) {
        return smoothSample(mask, position);
    }

    private Sample smoothSample(int mask, Vec3 position) {
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
        Bridge bridge = diagonalBridge(mask, x, y, z);
        density += bridge.density();
        gradientX += bridge.gradient().x();
        gradientY += bridge.gradient().y();
        gradientZ += bridge.gradient().z();
        return new Sample(position, density, new Vec3(-gradientX, -gradientY, -gradientZ));
    }

    private static Bridge diagonalBridge(int mask, AxisWeight x, AxisWeight y, AxisWeight z) {
        AxisWeight[] weights = {x, y, z};
        AxisBump[] bumps = {AxisBump.of(x), AxisBump.of(y), AxisBump.of(z)};
        double centerNeed = Math.max(0.0, ISO_LEVEL + VERTEX_MARGIN - Integer.bitCount(mask) / 8.0);
        Bridge combined = new Bridge(0.0, Vec3.ZERO);
        for (int first = 0; first < 8; first++) {
            if ((mask & (1 << first)) == 0) {
                continue;
            }
            for (int second = first + 1; second < 8; second++) {
                if ((mask & (1 << second)) == 0) {
                    continue;
                }
                int changedAxes = first ^ second;
                int distance = Integer.bitCount(changedAxes);
                Bridge candidate;
                if (distance == 2) {
                    int sharedAxis = Integer.numberOfTrailingZeros(~changedAxes & 0b111);
                    // This predicate is local to the physical edge. Using the
                    // whole mask here makes adjacent templates disagree when
                    // one has an unrelated face-connected route.
                    if (!isDiagonalSlice(mask, first, second, sharedAxis)) {
                        continue;
                    }
                    boolean positiveSide = (first & (1 << sharedAxis)) != 0;
                    double side = weights[sharedAxis].value(positiveSide);
                    double gate;
                    double gateDerivative;
                    if (side <= 0.5) {
                        gate = 0.0;
                        gateDerivative = 0.0;
                    } else {
                        double amount = 2.0 * side - 1.0;
                        gate = amount * amount * (3.0 - 2.0 * amount);
                        gateDerivative = 12.0 * amount * (1.0 - amount)
                            * weights[sharedAxis].derivative(positiveSide);
                    }
                    Contribution firstContribution = contribution(first, weights);
                    Contribution secondContribution = contribution(second, weights);
                    double sum = firstContribution.density() + secondContribution.density();
                    if (sum <= EPSILON) {
                        continue;
                    }
                    double harmonic = 2.0 * firstContribution.density() * secondContribution.density() / sum;
                    double denominator = sum * sum;
                    Vec3 harmonicGradient = firstContribution.gradient().multiply(
                        2.0 * secondContribution.density() * secondContribution.density() / denominator
                    ).add(secondContribution.gradient().multiply(
                        2.0 * firstContribution.density() * firstContribution.density() / denominator
                    ));
                    candidate = new Bridge(
                        EDGE_COUPLING * harmonic * gate,
                        harmonicGradient.multiply(EDGE_COUPLING * gate).add(
                            Vec3.ZERO.withComponent(sharedAxis, EDGE_COUPLING * harmonic * gateDerivative)
                        )
                    );
                } else if (distance == 3 && !faceConnected(mask, first, second)) {
                    double bx = bumps[0].value();
                    double by = bumps[1].value();
                    double bz = bumps[2].value();
                    double vertexNeed = centerNeed * 1.05;
                    candidate = new Bridge(
                        vertexNeed * bx * by * bz,
                        new Vec3(
                            vertexNeed * bumps[0].derivative() * by * bz,
                            vertexNeed * bx * bumps[1].derivative() * bz,
                            vertexNeed * bx * by * bumps[2].derivative()
                        )
                    );
                } else {
                    continue;
                }
                combined = new Bridge(
                    combined.density() + candidate.density(),
                    combined.gradient().add(candidate.gradient())
                );
            }
        }
        return combined;
    }

    private static Contribution contribution(int octant, AxisWeight[] weights) {
        double[] values = new double[3];
        double[] derivatives = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            boolean positiveSide = (octant & (1 << axis)) != 0;
            values[axis] = weights[axis].value(positiveSide);
            derivatives[axis] = weights[axis].derivative(positiveSide);
        }
        return new Contribution(
            values[0] * values[1] * values[2],
            new Vec3(
                derivatives[0] * values[1] * values[2],
                values[0] * derivatives[1] * values[2],
                values[0] * values[1] * derivatives[2]
            )
        );
    }

    private static boolean isDiagonalSlice(int mask, int first, int second, int sharedAxis) {
        boolean positiveSide = (first & (1 << sharedAxis)) != 0;
        int sliceMask = 0;
        for (int octant = 0; octant < 8; octant++) {
            if (((octant & (1 << sharedAxis)) != 0) == positiveSide) {
                sliceMask |= 1 << octant;
            }
        }
        return (mask & sliceMask) == ((1 << first) | (1 << second));
    }

    private static boolean faceConnected(int mask, int first, int target) {
        int visited = 1 << first;
        int frontier = visited;
        while (frontier != 0) {
            int octant = Integer.numberOfTrailingZeros(frontier);
            frontier &= ~(1 << octant);
            if (octant == target) {
                return true;
            }
            for (int axis = 0; axis < 3; axis++) {
                int neighbor = octant ^ (1 << axis);
                int neighborBit = 1 << neighbor;
                if ((mask & neighborBit) != 0 && (visited & neighborBit) == 0) {
                    visited |= neighborBit;
                    frontier |= neighborBit;
                }
            }
        }
        return false;
    }

    private AxisWeight weight(double coordinate) {
        if (coordinate <= -radius) {
            return new AxisWeight(0.0, 0.0);
        }
        if (coordinate >= radius) {
            return new AxisWeight(1.0, 0.0);
        }
        double angle = Math.PI * coordinate / (2.0 * radius);
        return new AxisWeight(
            0.5 + 0.5 * Math.sin(angle),
            Math.PI * Math.cos(angle) / (4.0 * radius)
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

    private record AxisBump(double value, double derivative) {
        private static AxisBump of(AxisWeight weight) {
            return new AxisBump(
                4.0 * weight.positive() * (1.0 - weight.positive()),
                4.0 * weight.positiveDerivative() * (1.0 - 2.0 * weight.positive())
            );
        }

    }

    private record Contribution(double density, Vec3 gradient) {
    }

    private record Bridge(double density, Vec3 gradient) {
    }

    private record Sample(Vec3 position, double density, Vec3 outward) {
    }

    record RegionPrimitive(int octant, MeshPrimitive primitive) {
    }
}
