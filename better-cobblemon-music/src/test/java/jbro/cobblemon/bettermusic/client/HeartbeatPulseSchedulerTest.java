package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HeartbeatPulseSchedulerTest {
    @Test
    void scalesTheHeartbeatBaseVolumeFromTheConfiguredMultiplier() {
        assertTrue(Math.abs(LastPokemonHeartbeatPlayer.scaledVolume(0.0) - 0.0F) < 0.0001F);
        assertTrue(Math.abs(LastPokemonHeartbeatPlayer.scaledVolume(1.0) - 0.35F) < 0.0001F);
        assertTrue(Math.abs(LastPokemonHeartbeatPlayer.scaledVolume(2.0) - 0.7F) < 0.0001F);
    }

    @Test
    void pulsesImmediatelyThenAtTheConfiguredCadence() {
        var scheduler = new HeartbeatPulseScheduler(1.0);

        assertTrue(scheduler.shouldPulse(10.0, true));
        assertFalse(scheduler.shouldPulse(10.99, true));
        assertTrue(scheduler.shouldPulse(11.0, true));
        assertFalse(scheduler.shouldPulse(11.5, true));
    }

    @Test
    void disablingResetsTheCadenceForImmediateReentry() {
        var scheduler = new HeartbeatPulseScheduler(1.0);

        assertTrue(scheduler.shouldPulse(10.0, true));
        assertFalse(scheduler.shouldPulse(10.5, false));
        assertTrue(scheduler.shouldPulse(10.6, true));
    }

    @Test
    void rejectsInvalidCadenceAndClockValues() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new HeartbeatPulseScheduler(0.0)
        );
        var scheduler = new HeartbeatPulseScheduler(1.0);
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> scheduler.shouldPulse(Double.NaN, true)
        );
    }
}
