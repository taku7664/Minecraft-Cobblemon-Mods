package jbro.minecraft.roundingblock.client.render;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import jbro.minecraft.roundingblock.mesh.CubeFace;
import jbro.minecraft.roundingblock.mesh.Vec3;
import jbro.minecraft.roundingblock.mesh.VerticalBlockShape;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

record FaceAppearance(
    CubeFace face,
    TextureAtlasSprite sprite,
    int tintIndex,
    boolean shade,
    int coordinateAxisA,
    int coordinateAxisB,
    double coveragePlane,
    double coverageAMin,
    double coverageAMax,
    double coverageBMin,
    double coverageBMax,
    AffineUvMapping uvMapping
) {
    private static final double POSITION_EPSILON = 1.0e-5;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<List<BakedQuad>[]> SELECTED_QUADS =
        ThreadLocal.withInitial(FaceAppearance::newSelectionArray);

    static Map<CubeFace, List<FaceAppearance>> analyze(
        BakedModel model,
        BlockState state,
        VerticalBlockShape shape,
        Supplier<RandomSource> randomSupplier,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        List<BakedQuad>[] selected = selectedQuads(model, state, randomSupplier);
        try {
            return analyzeSelected(selected, shape, appearanceCache);
        } finally {
            clearSelection(selected);
        }
    }

    static Map<CubeFace, List<FaceAppearance>> analyzeDynamic(
        BakedModel model,
        BlockState state,
        VerticalBlockShape shape,
        boolean complex,
        Supplier<RandomSource> randomSupplier,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache,
        IdentitySelectionCache<BakedQuad, Map<CubeFace, List<FaceAppearance>>> selectionCache
    ) {
        List<BakedQuad>[] selected = selectedQuads(model, state, randomSupplier);
        try {
            Map<CubeFace, List<FaceAppearance>> cached = selectionCache.find(selected);
            if (cached != null) {
                return cached;
            }
            Map<CubeFace, List<FaceAppearance>> analyzed = complex
                ? analyzeComplexSelected(selected, appearanceCache)
                : analyzeSelected(selected, shape, appearanceCache);
            return selectionCache.putIfAbsent(selected, analyzed);
        } finally {
            clearSelection(selected);
        }
    }

    private static Map<CubeFace, List<FaceAppearance>> analyzeSelected(
        List<BakedQuad>[] selected,
        VerticalBlockShape shape,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        EnumMap<CubeFace, List<FaceAppearance>> result = new EnumMap<>(CubeFace.class);
        EnumMap<CubeFace, java.util.ArrayList<FaceAppearance>> layersByFace = new EnumMap<>(CubeFace.class);
        java.util.Set<BakedQuad> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<BakedQuad> quads : selected) {
            if (!collect(quads, shape, appearanceCache, seen, layersByFace)) {
                return Map.of();
            }
        }
        for (CubeFace face : CubeFace.values()) {
            List<FaceAppearance> layers = layersByFace.get(face);
            if (layers == null || layers.isEmpty()) {
                return Map.of();
            }
            result.put(face, List.copyOf(layers));
        }
        return Map.copyOf(result);
    }

    static Map<CubeFace, List<FaceAppearance>> analyzeComplex(
        BakedModel model,
        BlockState state,
        Supplier<RandomSource> randomSupplier,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        List<BakedQuad>[] selected = selectedQuads(model, state, randomSupplier);
        try {
            return analyzeComplexSelected(selected, appearanceCache);
        } finally {
            clearSelection(selected);
        }
    }

    static Map<CubeFace, List<FaceAppearance>> analyzeComplexSelected(
        List<BakedQuad>[] selected,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        EnumMap<CubeFace, List<FaceAppearance>> result = new EnumMap<>(CubeFace.class);
        EnumMap<CubeFace, java.util.ArrayList<FaceAppearance>> piecesByFace = new EnumMap<>(CubeFace.class);
        java.util.Set<BakedQuad> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<BakedQuad> quads : selected) {
            if (!collectComplex(quads, appearanceCache, seen, piecesByFace)) {
                return Map.of();
            }
        }
        for (CubeFace face : CubeFace.values()) {
            List<FaceAppearance> pieces = piecesByFace.get(face);
            if (pieces == null || pieces.isEmpty()) {
                return Map.of();
            }
            result.put(face, List.copyOf(pieces));
        }
        return Map.copyOf(result);
    }

    private static List<BakedQuad>[] selectedQuads(
        BakedModel model,
        BlockState state,
        Supplier<RandomSource> randomSupplier
    ) {
        List<BakedQuad>[] selected = SELECTED_QUADS.get();
        try {
            selected[0] = model.getQuads(state, null, randomSupplier.get());
            for (int index = 0; index < DIRECTIONS.length; index++) {
                selected[index + 1] = model.getQuads(state, DIRECTIONS[index], randomSupplier.get());
            }
            return selected;
        } catch (RuntimeException | Error exception) {
            clearSelection(selected);
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] newSelectionArray() {
        return (List<BakedQuad>[]) new List<?>[DIRECTIONS.length + 1];
    }

    private static void clearSelection(List<BakedQuad>[] selected) {
        java.util.Arrays.fill(selected, null);
    }

    private static boolean collectComplex(
        List<BakedQuad> quads,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache,
        java.util.Set<BakedQuad> seen,
        EnumMap<CubeFace, java.util.ArrayList<FaceAppearance>> piecesByFace
    ) {
        for (BakedQuad quad : quads) {
            if (!seen.add(quad)) {
                continue;
            }
            FaceAppearance appearance = appearanceCache.computeIfAbsent(
                quad,
                ignored -> Optional.ofNullable(fromComplexQuad(quad))
            ).orElse(null);
            if (appearance == null || !appearance.shade()) {
                return false;
            }
            piecesByFace.computeIfAbsent(
                appearance.face(), ignored -> new java.util.ArrayList<>()
            ).add(appearance);
        }
        return true;
    }

    private static boolean collect(
        List<BakedQuad> quads,
        VerticalBlockShape shape,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache,
        java.util.Set<BakedQuad> seen,
        EnumMap<CubeFace, java.util.ArrayList<FaceAppearance>> layersByFace
    ) {
        for (BakedQuad quad : quads) {
            if (!seen.add(quad)) {
                continue;
            }
            FaceAppearance appearance = appearanceCache.computeIfAbsent(
                quad,
                ignored -> Optional.ofNullable(fromQuad(quad, shape))
            ).orElse(null);
            if (appearance == null || !appearance.shade()) {
                return false;
            }
            layersByFace.computeIfAbsent(appearance.face(), ignored -> new java.util.ArrayList<>()).add(appearance);
        }
        return true;
    }

    long packedUv(Vec3 position) {
        return uvMapping.pack(
            wrapBlockCoordinate(position.component(coordinateAxisA)),
            wrapBlockCoordinate(position.component(coordinateAxisB))
        );
    }

    static boolean isBestMatch(List<FaceAppearance> appearances, int candidateIndex, Vec3 position) {
        return isBestMatch(appearances, candidateIndex, position.x(), position.y(), position.z());
    }

    static boolean allShareCoverage(List<FaceAppearance> appearances) {
        if (appearances.size() < 2) {
            return true;
        }
        FaceAppearance first = appearances.getFirst();
        for (int index = 1; index < appearances.size(); index++) {
            if (!first.hasSameCoverage(appearances.get(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean isBestMatch(
        List<FaceAppearance> appearances,
        int candidateIndex,
        double x,
        double y,
        double z
    ) {
        FaceAppearance candidate = appearances.get(candidateIndex);
        double candidateDistance = candidate.coverageDistanceSquared(x, y, z);
        for (int index = 0; index < appearances.size(); index++) {
            if (index == candidateIndex) {
                continue;
            }
            FaceAppearance other = appearances.get(index);
            if (candidate.tintIndex != other.tintIndex
                || candidate.shade != other.shade
                || candidate.hasSameCoverage(other)) {
                continue;
            }
            double otherDistance = other.coverageDistanceSquared(x, y, z);
            if (otherDistance < candidateDistance - POSITION_EPSILON
                || index < candidateIndex && Math.abs(otherDistance - candidateDistance) <= POSITION_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSameCoverage(FaceAppearance other) {
        return Math.abs(coveragePlane - other.coveragePlane) <= POSITION_EPSILON
            && Math.abs(coverageAMin - other.coverageAMin) <= POSITION_EPSILON
            && Math.abs(coverageAMax - other.coverageAMax) <= POSITION_EPSILON
            && Math.abs(coverageBMin - other.coverageBMin) <= POSITION_EPSILON
            && Math.abs(coverageBMax - other.coverageBMax) <= POSITION_EPSILON;
    }

    private double coverageDistanceSquared(double x, double y, double z) {
        double deltaPlane = component(face.axis(), x, y, z) - coveragePlane;
        double a = wrapBlockCoordinate(component(coordinateAxisA, x, y, z));
        double b = wrapBlockCoordinate(component(coordinateAxisB, x, y, z));
        double deltaA = a < coverageAMin ? coverageAMin - a : Math.max(0.0, a - coverageAMax);
        double deltaB = b < coverageBMin ? coverageBMin - b : Math.max(0.0, b - coverageBMax);
        return deltaPlane * deltaPlane + deltaA * deltaA + deltaB * deltaB;
    }

    private static double component(int axis, double x, double y, double z) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IllegalArgumentException("Axis must be 0, 1, or 2");
        };
    }

    private static double wrapBlockCoordinate(double coordinate) {
        if (coordinate >= 0.0 && coordinate <= 1.0) {
            return coordinate;
        }
        return coordinate - Math.floor(coordinate);
    }

    private static FaceAppearance fromQuad(BakedQuad quad, VerticalBlockShape shape) {
        for (CubeFace face : CubeFace.values()) {
            FaceAppearance appearance = fromQuadOnFace(quad, face, shape);
            if (appearance != null) {
                return appearance;
            }
        }
        return null;
    }

    private static FaceAppearance fromComplexQuad(BakedQuad quad) {
        CubeFace face = toCubeFace(quad.getDirection());
        int[] data = quad.getVertices();
        if (data.length % 4 != 0 || data.length / 4 < 6) {
            return null;
        }
        int stride = data.length / 4;
        int axisA = (face.axis() + 1) % 3;
        int axisB = (face.axis() + 2) % 3;
        double[] a = new double[4];
        double[] b = new double[4];
        float[] u = new float[4];
        float[] v = new float[4];
        double minA = Double.POSITIVE_INFINITY;
        double maxA = Double.NEGATIVE_INFINITY;
        double minB = Double.POSITIVE_INFINITY;
        double maxB = Double.NEGATIVE_INFINITY;
        double faceCoordinate = Double.NaN;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            Vec3 position = new Vec3(
                Float.intBitsToFloat(data[base]),
                Float.intBitsToFloat(data[base + 1]),
                Float.intBitsToFloat(data[base + 2])
            );
            double coordinate = position.component(face.axis());
            if (vertex == 0) {
                faceCoordinate = coordinate;
            } else if (Math.abs(coordinate - faceCoordinate) > POSITION_EPSILON) {
                return null;
            }
            a[vertex] = position.component(axisA);
            b[vertex] = position.component(axisB);
            u[vertex] = Float.intBitsToFloat(data[base + 4]);
            v[vertex] = Float.intBitsToFloat(data[base + 5]);
            minA = Math.min(minA, a[vertex]);
            maxA = Math.max(maxA, a[vertex]);
            minB = Math.min(minB, b[vertex]);
            maxB = Math.max(maxB, b[vertex]);
        }
        try {
            return new FaceAppearance(
                face, quad.getSprite(), quad.getTintIndex(), quad.isShade(), axisA, axisB,
                faceCoordinate, minA, maxA, minB, maxB,
                AffineUvMapping.fit(a, b, u, v)
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static FaceAppearance fromQuadOnFace(BakedQuad quad, CubeFace face, VerticalBlockShape shape) {
        int[] data = quad.getVertices();
        if (data.length % 4 != 0 || data.length / 4 < 6) {
            return null;
        }
        int stride = data.length / 4;
        int axisA = (face.axis() + 1) % 3;
        int axisB = (face.axis() + 2) % 3;
        double[] a = new double[4];
        double[] b = new double[4];
        float[] u = new float[4];
        float[] v = new float[4];
        double minA = Double.POSITIVE_INFINITY;
        double maxA = Double.NEGATIVE_INFINITY;
        double minB = Double.POSITIVE_INFINITY;
        double maxB = Double.NEGATIVE_INFINITY;
        double faceCoordinate = face.sign() > 0 ? shape.maximum(face.axis()) : shape.minimum(face.axis());
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            Vec3 position = new Vec3(
                Float.intBitsToFloat(data[base]),
                Float.intBitsToFloat(data[base + 1]),
                Float.intBitsToFloat(data[base + 2])
            );
            if (Math.abs(position.component(face.axis()) - faceCoordinate) > POSITION_EPSILON) {
                return null;
            }
            a[vertex] = position.component(axisA);
            b[vertex] = position.component(axisB);
            u[vertex] = Float.intBitsToFloat(data[base + 4]);
            v[vertex] = Float.intBitsToFloat(data[base + 5]);
            minA = Math.min(minA, a[vertex]);
            maxA = Math.max(maxA, a[vertex]);
            minB = Math.min(minB, b[vertex]);
            maxB = Math.max(maxB, b[vertex]);
        }
        if (minA > shape.minimum(axisA) + POSITION_EPSILON
            || maxA < shape.maximum(axisA) - POSITION_EPSILON
            || minB > shape.minimum(axisB) + POSITION_EPSILON
            || maxB < shape.maximum(axisB) - POSITION_EPSILON) {
            return null;
        }
        try {
            return new FaceAppearance(
                face, quad.getSprite(), quad.getTintIndex(), quad.isShade(), axisA, axisB,
                faceCoordinate, minA, maxA, minB, maxB,
                AffineUvMapping.fit(a, b, u, v)
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static CubeFace toCubeFace(Direction direction) {
        return switch (direction) {
            case WEST -> CubeFace.WEST;
            case EAST -> CubeFace.EAST;
            case DOWN -> CubeFace.DOWN;
            case UP -> CubeFace.UP;
            case NORTH -> CubeFace.NORTH;
            case SOUTH -> CubeFace.SOUTH;
        };
    }

    static Direction toDirection(CubeFace face) {
        return switch (face) {
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
        };
    }
}
