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
    AffineUvMapping uvMapping
) {
    private static final double POSITION_EPSILON = 1.0e-5;

    static Map<CubeFace, List<FaceAppearance>> analyze(
        BakedModel model,
        BlockState state,
        VerticalBlockShape shape,
        Supplier<RandomSource> randomSupplier,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        EnumMap<CubeFace, List<FaceAppearance>> result = new EnumMap<>(CubeFace.class);
        EnumMap<CubeFace, java.util.ArrayList<FaceAppearance>> layersByFace = new EnumMap<>(CubeFace.class);
        java.util.Set<BakedQuad> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!collect(
            model.getQuads(state, null, randomSupplier.get()), shape, appearanceCache, seen, layersByFace
        )) {
            return Map.of();
        }
        for (Direction direction : Direction.values()) {
            List<BakedQuad> quads = model.getQuads(state, direction, randomSupplier.get());
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

    AffineUvMapping.Uv uv(Vec3 position) {
        return uvMapping.map(
            wrapBlockCoordinate(position.component(coordinateAxisA)),
            wrapBlockCoordinate(position.component(coordinateAxisB))
        );
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
