package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ViewportClampTest {
    @Test
    void oversizedContentPinsToTheMinimumWithoutThrowing() {
        assertEquals(4, ViewportClamp.clamp(40, 4, 320, 420, 4));
    }

    @Test
    void ordinaryContentStaysInsideBothMargins() {
        assertEquals(4, ViewportClamp.clamp(-20, 4, 320, 100, 4));
        assertEquals(216, ViewportClamp.clamp(300, 4, 320, 100, 4));
        assertEquals(120, ViewportClamp.clamp(120, 4, 320, 100, 4));
    }

    @Test
    void defaultPositionIsClampedWhenCustomCoordinateIsMissing() {
        assertEquals(0, ViewportClamp.resolve(null, -10, 0, 320, 318, 0));
    }

    @Test
    void partialCustomPositionIsStillClamped() {
        assertEquals(220, ViewportClamp.resolve(500, 12, 0, 320, 100, 0));
        assertEquals(40, ViewportClamp.resolve(null, 40, 0, 180, 48, 0));
    }
}
