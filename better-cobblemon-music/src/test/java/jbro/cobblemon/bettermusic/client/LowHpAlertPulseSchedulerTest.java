package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LowHpAlertPulseSchedulerTest {
    @Test
    void playerCadenceLeavesTheFullAlertClipUnclipped() {
        assertEquals(0.70, LastPokemonLowHpAlertPlayer.CADENCE_SECONDS, 0.0001);
    }

    @Test
    void appliesTheConfiguredAlertVolumeDirectly() {
        assertTrue(Math.abs(LastPokemonLowHpAlertPlayer.scaledVolume(0.0) - 0.0F) < 0.0001F);
        assertTrue(Math.abs(LastPokemonLowHpAlertPlayer.scaledVolume(1.0) - 1.0F) < 0.0001F);
        assertTrue(Math.abs(LastPokemonLowHpAlertPlayer.scaledVolume(2.0) - 2.0F) < 0.0001F);
    }

    @Test
    void pulsesImmediatelyThenAtTheConfiguredCadence() {
        var scheduler = new LowHpAlertPulseScheduler(0.65);

        assertTrue(scheduler.shouldPulse(10.0, true));
        assertFalse(scheduler.shouldPulse(10.64, true));
        assertTrue(scheduler.shouldPulse(10.65, true));
        assertFalse(scheduler.shouldPulse(11.0, true));
    }

    @Test
    void disablingResetsTheCadenceForImmediateReentry() {
        var scheduler = new LowHpAlertPulseScheduler(1.0);

        assertTrue(scheduler.shouldPulse(10.0, true));
        assertFalse(scheduler.shouldPulse(10.5, false));
        assertTrue(scheduler.shouldPulse(10.6, true));
    }

    @Test
    void rejectsInvalidCadenceAndClockValues() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new LowHpAlertPulseScheduler(0.0)
        );
        var scheduler = new LowHpAlertPulseScheduler(1.0);
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> scheduler.shouldPulse(Double.NaN, true)
        );
    }
}
