package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LowHpAlertLoopPlayerTest {
    @Test
    void playsOnlyOnceEvenAcrossManyClipLengths() {
        var player = new LowHpAlertLoopPlayer(0.7);
        var backend = new FakeBackend();
        player.update(0, true, 0.1F, "test:alert", backend);
        for (int tick = 1; tick <= 1200; tick++) {
            player.update(tick * 0.05, true, 0.1F, "test:alert", backend);
        }
        assertEquals(1, backend.starts);
        assertEquals(0, backend.stops);
    }

    @Test
    void leavingRedHpStopsTheClipAndReentryStartsImmediately() {
        var player = new LowHpAlertLoopPlayer(0.7);
        var backend = new FakeBackend();
        player.update(0, true, 0.1F, "test:alert", backend);
        player.update(0.1, false, 0.1F, "test:alert", backend);
        player.update(0.2, false, 0.1F, "test:alert", backend);
        assertEquals(1, backend.stops);
        player.update(0.3, true, 0.1F, "test:alert", backend);
        assertEquals(2, backend.starts);
    }

    @Test
    void zeroVolumeStopsInsteadOfKeepingAMutedLoop() {
        var player = new LowHpAlertLoopPlayer(0.7);
        var backend = new FakeBackend();
        player.update(0, true, 0.1F, "test:alert", backend);
        player.update(0.1, true, 0, "test:alert", backend);
        assertEquals(1, backend.stops);
        player.update(0.2, true, 0.1F, "test:alert", backend);
        assertEquals(2, backend.starts);
    }

    @Test
    void changingTheEventOrVolumeReplacesOnlyOneExistingLoop() {
        var player = new LowHpAlertLoopPlayer(0.7);
        var backend = new FakeBackend();
        player.update(0, true, 0.1F, "test:alert", backend);
        player.update(0.1, true, 0.1F, "test:new_alert", backend);
        player.update(0.2, true, 0.2F, "test:new_alert", backend);
        assertEquals(3, backend.starts);
        assertEquals(2, backend.stops);
        assertEquals("test:new_alert", backend.eventId);
        assertEquals(0.2F, backend.volume);
    }

    @Test
    void aResourceReloadOrFailedPlaybackRetriesWithoutPerTickStartSpam() {
        var player = new LowHpAlertLoopPlayer(0.7);
        var backend = new FakeBackend();
        player.update(0, true, 0.1F, "test:alert", backend);
        backend.playing = false;
        player.update(0.1, true, 0.1F, "test:alert", backend);
        assertEquals(1, backend.starts);
        player.update(0.7, true, 0.1F, "test:alert", backend);
        assertEquals(2, backend.starts);
    }

    private static final class FakeBackend implements LowHpAlertLoopPlayer.Backend {
        int starts;
        int stops;
        boolean playing;
        String eventId;
        float volume;

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void play(String eventId, float volume) {
            starts++;
            playing = true;
            this.eventId = eventId;
            this.volume = volume;
        }

        @Override
        public void stop() {
            stops++;
            playing = false;
        }
    }
}
