package jbro.minecraft.roundingblock.client.render;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import jbro.minecraft.roundingblock.mesh.CubeFace;
import jbro.minecraft.roundingblock.mesh.Vec3;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

record FaceAppearance(
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
        Supplier<RandomSource> randomSupplier,
        Map<BakedQuad, Optional<FaceAppearance>> appearanceCache
    ) {
        if (!model.getQuads(state, null, randomSupplier.get()).isEmpty()) {
            return Map.of();
        }
        EnumMap<CubeFace, List<FaceAppearance>> result = new EnumMap<>(CubeFace.class);
        for (Direction direction : Direction.values()) {
            List<BakedQuad> quads = model.getQuads(state, direction, randomSupplier.get());
            if (quads.isEmpty()) {
                return Map.of();
            }
            CubeFace face = toCubeFace(direction);
            java.util.ArrayList<FaceAppearance> layers = new java.util.ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                FaceAppearance appearance = appearanceCache.computeIfAbsent(
                    quad,
                    ignored -> Optional.ofNullable(fromQuad(quad, face))
                ).orElse(null);
                if (appearance == null || !appearance.shade()) {
                    return Map.of();
                }
                layers.add(appearance);
            }
            result.put(face, List.copyOf(layers));
        }
        return Map.copyOf(result);
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

    private static FaceAppearance fromQuad(BakedQuad quad, CubeFace face) {
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
        double faceCoordinate = face.sign() > 0 ? 1.0 : 0.0;
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
        if (minA > POSITION_EPSILON || maxA < 1.0 - POSITION_EPSILON
            || minB > POSITION_EPSILON || maxB < 1.0 - POSITION_EPSILON) {
            return null;
        }
        try {
            return new FaceAppearance(
                quad.getSprite(), quad.getTintIndex(), quad.isShade(), axisA, axisB,
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
