package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LowHpAlertPulseSchedulerTest {
    @Test
    void playerThrottlesOnlyRecoveryAttemptsNotTheAudioLoop() {
        assertEquals(0.70, LastPokemonLowHpAlertPlayer.RETRY_SECONDS, 0.0001);
    }

    @Test
    void nativeAlertIsOneNonPositionalLoopOnTheExistingUiSoundCategory() {
        var sound = LastPokemonLowHpAlertPlayer.loopingSound("cobleserver:battle.low_hp.alert", 0.1F);
        assertTrue(sound.isLooping());
        assertTrue(sound.isRelative());
        assertEquals(0, sound.getDelay());
        assertEquals(net.minecraft.sounds.SoundSource.MASTER, sound.getSource());
        assertEquals(net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, sound.getAttenuation());
        assertEquals("cobleserver:battle.low_hp.alert", sound.getLocation().toString());
        // getVolume() needs a resolved Minecraft Sound; gain is covered by LowHpAlertVolumeTest.
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
