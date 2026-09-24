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
}
