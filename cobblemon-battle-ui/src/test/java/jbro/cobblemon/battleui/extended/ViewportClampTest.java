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
}
