package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MusicLowPassFilterTest {
    @Test
    void mapsTransitionAmountToHighFrequencyAttenuation() {
        assertEquals(1.0F, MusicLowPassFilter.gainHighFrequency(0.0), 0.0001F);
        assertEquals(0.54F, MusicLowPassFilter.gainHighFrequency(0.5), 0.0001F);
        assertEquals(0.08F, MusicLowPassFilter.gainHighFrequency(1.0), 0.0001F);
    }

    @Test
    void rejectsAnInvalidTransitionAmount() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MusicLowPassFilter.gainHighFrequency(Double.NaN)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MusicLowPassFilter.gainHighFrequency(1.01)
        );
    }
}
