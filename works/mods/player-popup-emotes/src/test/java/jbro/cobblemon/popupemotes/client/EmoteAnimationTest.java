package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class EmoteAnimationTest {
    @Test
    void fadesInAndOutInHalfTheFormerTimeWithSmoothMidpoints() {
        assertEquals(2.5F, EmoteAnimation.FADE_IN_TICKS);
        assertEquals(2.5F, EmoteAnimation.FADE_OUT_TICKS);

        assertEquals(0.0F, EmoteAnimation.alpha(0.0F), 0.0001F);
        assertEquals(0.5F, EmoteAnimation.alpha(1.25F), 0.0001F);
        assertEquals(1.0F, EmoteAnimation.alpha(2.5F), 0.0001F);
        assertEquals(0.5F, EmoteAnimation.alpha(43.75F), 0.0001F);
        assertEquals(0.0F, EmoteAnimation.alpha(45.0F), 0.0001F);
    }

    @Test
    void usesTheSameSmoothCurveForScale() {
        assertEquals(0.7F, EmoteAnimation.scale(0.0F), 0.0001F);
        assertEquals(0.85F, EmoteAnimation.scale(1.25F), 0.0001F);
        assertEquals(1.0F, EmoteAnimation.scale(2.5F), 0.0001F);
        assertEquals(0.5F, EmoteAnimation.scale(43.75F), 0.0001F);
        assertEquals(0.0F, EmoteAnimation.scale(45.0F), 0.0001F);
    }
}
