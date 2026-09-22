package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class UiConfigSanitizerTest {
    @Test
    void scaleRejectsNonFiniteValuesAndClampsFiniteValues() {
        assertEquals(1.0f, UiConfigSanitizer.scale(Float.NaN, 0.5f, 2.0f, 1.0f));
        assertEquals(1.0f, UiConfigSanitizer.scale(Float.POSITIVE_INFINITY, 0.5f, 2.0f, 1.0f));
        assertEquals(0.5f, UiConfigSanitizer.scale(0.1f, 0.5f, 2.0f, 1.0f));
        assertEquals(2.0f, UiConfigSanitizer.scale(4.0f, 0.5f, 2.0f, 1.0f));
    }

    @Test
    void optionalDimensionPreservesNullAndClampsPresentValues() {
        assertNull(UiConfigSanitizer.optionalDimension(null, 120, 400));
        assertEquals(120, UiConfigSanitizer.optionalDimension(-1, 120, 400));
        assertEquals(400, UiConfigSanitizer.optionalDimension(9999, 120, 400));
    }
}
