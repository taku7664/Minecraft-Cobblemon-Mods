package jbro.cobblemon.battleui.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VignetteFadeTest {
    @Test
    void opensAndClosesAtDifferentRates() {
        VignetteFade fade = new VignetteFade();
        assertEquals(0f, fade.advance(true, 0L), .001f);
        assertEquals(.5f, fade.advance(true, 100_000_000L), .001f);
        assertEquals(1f, fade.advance(true, 200_000_000L), .001f);
        assertEquals(.5f, fade.advance(false, 325_000_000L), .001f);
        assertEquals(0f, fade.advance(false, 450_000_000L), .001f);
    }

    @Test
    void resumesAfterIdleWithoutJumpingOpen() {
        VignetteFade fade = new VignetteFade();
        fade.advance(true, 0L);
        fade.advance(true, 200_000_000L);
        assertEquals(0f, fade.advance(true, 2_000_000_000L), .001f);
    }
}
