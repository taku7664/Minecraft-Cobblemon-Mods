package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class AudioEffectsSettingsTest {
    @Test
    void defaultsKeepExistingEffectsEnabledAtTheirCurrentVolume() {
        assertEquals(new AudioEffectsSettings(true, 1.0, true, 1.0), AudioEffectsSettings.defaults());
    }

    @Test
    void rejectsNonFiniteNegativeAndExcessiveVolumes() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AudioEffectsSettings(true, Double.NaN, true, 1.0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AudioEffectsSettings(true, -0.01, true, 1.0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AudioEffectsSettings(true, 1.0, true, 2.01)
        );
    }

    @Test
    void underwaterStrengthHasAConservativeDefaultAndRejectsInvalidValues() {
        assertEquals(0.35, AudioEffectsSettings.defaults().underwaterEffectStrength(), 0.0001);
        assertEquals(true, AudioEffectsSettings.defaults().underwaterEffectsEnabled());
        for (double strength : new double[]{Double.NaN, -0.01, 1.01}) {
            assertThrows(IllegalArgumentException.class,
                () -> new AudioEffectsSettings(true, 1.0, true, 1.0, true, strength));
        }
    }
}
