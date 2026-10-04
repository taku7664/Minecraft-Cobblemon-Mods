package jbro.cobblemon.betterbattlepresentation.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DynamaxSkyAnimationClockTest {
    @Test
    void usesAUniformNameThatVanillaDefaultUniformsDoNotOverwrite() {
        assertEquals("EffectTimeSeconds", DynamaxSkyAnimationClock.UNIFORM_NAME);
    }

    @Test
    void convertsMonotonicNanosecondsToLoopingSeconds() {
        assertEquals(0.0F, DynamaxSkyAnimationClock.seconds(0L), 0.000_001F);
        assertEquals(1.25F, DynamaxSkyAnimationClock.seconds(1_250_000_000L), 0.000_001F);
        assertEquals(60.5F, DynamaxSkyAnimationClock.seconds(60_500_000_000L), 0.000_001F);
        assertEquals(120.5F, DynamaxSkyAnimationClock.seconds(120_500_000_000L), 0.000_001F);
        assertEquals(0.5F, DynamaxSkyAnimationClock.seconds(180_500_000_000L), 0.000_001F);
    }
}
