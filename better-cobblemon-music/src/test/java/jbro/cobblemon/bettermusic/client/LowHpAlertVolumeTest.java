package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class LowHpAlertVolumeTest {
    @Test
    void configuredVolumeReachesTheAlertWithoutAnAdditionalHiddenGainReduction() {
        assertEquals(0.0F, LastPokemonLowHpAlertPlayer.scaledVolume(0.0));
        assertEquals(0.2F, LastPokemonLowHpAlertPlayer.scaledVolume(0.2));
        assertEquals(1.0F, LastPokemonLowHpAlertPlayer.scaledVolume(1.0));
        assertEquals(2.0F, LastPokemonLowHpAlertPlayer.scaledVolume(2.0));
    }

    @Test
    void invalidVolumeCannotReachTheSoundEngine() {
        for (double volume : new double[]{-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> LastPokemonLowHpAlertPlayer.scaledVolume(volume));
        }
    }
}
