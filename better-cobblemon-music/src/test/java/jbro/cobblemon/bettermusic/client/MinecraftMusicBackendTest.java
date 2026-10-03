package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MinecraftMusicBackendTest {
    @Test
    void boostsDefaultUnderwaterIntensityWithoutChangingTheSliderEndpoints() {
        assertEquals(0.0, MinecraftMusicBackend.underwaterIntensity(0.0), 0.0001);
        assertEquals(Math.sqrt(0.35), MinecraftMusicBackend.underwaterIntensity(0.35), 0.0001);
        assertEquals(1.0, MinecraftMusicBackend.underwaterIntensity(1.0), 0.0001);
        assertThrows(IllegalArgumentException.class,
            () -> MinecraftMusicBackend.underwaterIntensity(Double.NaN));
    }
}
