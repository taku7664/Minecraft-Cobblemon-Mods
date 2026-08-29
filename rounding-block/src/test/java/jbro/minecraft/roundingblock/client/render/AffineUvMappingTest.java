package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AffineUvMappingTest {
    @Test
    void preservesRotatedAtlasUvMappingInsideRoundedPatches() {
        AffineUvMapping mapping = AffineUvMapping.fit(
            new double[]{0.0, 1.0, 1.0, 0.0},
            new double[]{0.0, 0.0, 1.0, 1.0},
            new float[]{0.75f, 0.75f, 0.50f, 0.50f},
            new float[]{0.25f, 0.50f, 0.50f, 0.25f}
        );

        AffineUvMapping.Uv center = mapping.map(0.5, 0.5);
        assertEquals(0.625f, center.u(), 1.0e-6f);
        assertEquals(0.375f, center.v(), 1.0e-6f);
    }
}
