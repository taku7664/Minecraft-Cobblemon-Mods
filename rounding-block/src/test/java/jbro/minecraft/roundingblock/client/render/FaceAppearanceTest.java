package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import jbro.minecraft.roundingblock.mesh.CubeFace;
import jbro.minecraft.roundingblock.mesh.Vec3;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class FaceAppearanceTest {
    private static final AffineUvMapping UV = new AffineUvMapping(0.0, 1.0, 0.0, 0.0, 0.0, 1.0);

    @Test
    void complexAnalysisPreservesEveryPieceOnTheSameFace() {
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            if (direction == Direction.NORTH) {
                quads.add(quad(direction, 0.0, 1.0, 0.0, 0.5));
                quads.add(quad(direction, 0.0, 1.0, 0.5, 1.0));
            } else {
                quads.add(quad(direction, 0.0, 1.0, 0.0, 1.0));
            }
        }

        @SuppressWarnings("unchecked")
        List<BakedQuad>[] selected = (List<BakedQuad>[]) new List<?>[]{quads};
        Map<CubeFace, List<FaceAppearance>> result = FaceAppearance.analyzeComplexSelected(
            selected, new ConcurrentHashMap<BakedQuad, Optional<FaceAppearance>>()
        );

        assertEquals(6, result.size());
        assertEquals(2, result.get(CubeFace.NORTH).size());
    }

    @Test
    void generatedPrimitiveChoosesNearestUvPieceButKeepsOverlays() {
        FaceAppearance lower = appearance(-1, 0.0, 0.5);
        FaceAppearance upper = appearance(-1, 0.5, 1.0);
        FaceAppearance upperOverlay = appearance(0, 0.5, 1.0);
        List<FaceAppearance> appearances = List.of(lower, upper, upperOverlay);

        Vec3 lowerPoint = new Vec3(0.5, 0.25, 0.0);
        assertTrue(FaceAppearance.isBestMatch(appearances, 0, lowerPoint));
        assertFalse(FaceAppearance.isBestMatch(appearances, 1, lowerPoint));

        Vec3 upperPoint = new Vec3(0.5, 0.75, 0.0);
        assertFalse(FaceAppearance.isBestMatch(appearances, 0, upperPoint));
        assertTrue(FaceAppearance.isBestMatch(appearances, 1, upperPoint));
        assertTrue(FaceAppearance.isBestMatch(appearances, 2, upperPoint));
    }

    @Test
    void uvPiecesOnParallelStairPlanesDoNotBecomeOverlays() {
        FaceAppearance outer = appearance(-1, 0.0, 0.0, 1.0);
        FaceAppearance riser = appearance(-1, 0.5, 0.0, 1.0);
        List<FaceAppearance> appearances = List.of(outer, riser);

        Vec3 pointOnRiser = new Vec3(0.5, 0.5, 0.48);
        assertFalse(FaceAppearance.isBestMatch(appearances, 0, pointOnRiser));
        assertTrue(FaceAppearance.isBestMatch(appearances, 1, pointOnRiser));
    }

    @Test
    void coverageFastPathAcceptsTrueLayersButRejectsPiecewiseFaces() {
        FaceAppearance base = appearance(-1, 0.0, 0.0, 1.0);
        FaceAppearance overlay = appearance(0, 0.0, 0.0, 1.0);
        FaceAppearance upperPiece = appearance(-1, 0.0, 0.5, 1.0);

        assertTrue(FaceAppearance.allShareCoverage(List.of(base, overlay)));
        assertFalse(FaceAppearance.allShareCoverage(List.of(base, upperPiece)));
    }

    private static FaceAppearance appearance(int tintIndex, double minY, double maxY) {
        return appearance(tintIndex, 0.0, minY, maxY);
    }

    private static FaceAppearance appearance(int tintIndex, double plane, double minY, double maxY) {
        return new FaceAppearance(
            CubeFace.NORTH, null, tintIndex, true, 0, 1,
            plane, 0.0, 1.0, minY, maxY, UV
        );
    }

    private static BakedQuad quad(
        Direction direction,
        double minA,
        double maxA,
        double minB,
        double maxB
    ) {
        CubeFace face = FaceAppearance.toCubeFace(direction);
        int axisA = (face.axis() + 1) % 3;
        int axisB = (face.axis() + 2) % 3;
        double plane = face.sign() > 0 ? 1.0 : 0.0;
        double[][] corners = {
            {minA, minB}, {maxA, minB}, {maxA, maxB}, {minA, maxB}
        };
        int[] data = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            double[] coordinates = new double[3];
            coordinates[face.axis()] = plane;
            coordinates[axisA] = corners[vertex][0];
            coordinates[axisB] = corners[vertex][1];
            int base = vertex * 8;
            data[base] = Float.floatToRawIntBits((float) coordinates[0]);
            data[base + 1] = Float.floatToRawIntBits((float) coordinates[1]);
            data[base + 2] = Float.floatToRawIntBits((float) coordinates[2]);
            data[base + 4] = Float.floatToRawIntBits((float) corners[vertex][0]);
            data[base + 5] = Float.floatToRawIntBits((float) corners[vertex][1]);
        }
        return new BakedQuad(data, -1, direction, null, true);
    }
}
