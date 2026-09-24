package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MusicLowPassFilterTest {
    @Test
    void mapsTransitionAmountToHighFrequencyAttenuation() {
        assertEquals(1.0F, MusicLowPassFilter.gainHighFrequency(0.0), 0.0001F);
        assertEquals(0.51F, MusicLowPassFilter.gainHighFrequency(0.5), 0.0001F);
        assertEquals(0.02F, MusicLowPassFilter.gainHighFrequency(1.0), 0.0001F);
        assertEquals(1.0F, MusicLowPassFilter.gain(0.0), 0.0001F);
        assertEquals(1.0F, MusicLowPassFilter.gain(0.5), 0.0001F);
        assertEquals(1.0F, MusicLowPassFilter.gain(1.0), 0.0001F);
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
        assertThrows(IllegalArgumentException.class, () -> MusicLowPassFilter.gain(-0.01));
    }
}
