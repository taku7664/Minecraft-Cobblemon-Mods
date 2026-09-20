package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmoothButtonScaleTest {
    @Test
    void approachesFocusedScaleAndReturnsWithoutOvershoot() {
        SmoothButtonScale scale = new SmoothButtonScale(1.0, 1.06, 0.12);

        double growing = scale.advance(1.0, true, 0.06);
        double grown = scale.advance(growing, true, 0.20);
        double shrinking = scale.advance(grown, false, 0.06);
        double restored = scale.advance(shrinking, false, 0.20);

        assertTrue(growing > 1.0 && growing < 1.06);
        assertEquals(1.06, grown, 0.0001);
        assertTrue(shrinking > 1.0 && shrinking < 1.06);
        assertEquals(1.0, restored, 0.0001);
    }
}
