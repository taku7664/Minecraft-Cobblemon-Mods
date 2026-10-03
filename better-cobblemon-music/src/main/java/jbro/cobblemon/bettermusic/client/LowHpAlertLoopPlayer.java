package jbro.cobblemon.bettermusic.client;

import java.util.Objects;

/** One continuous alert, with throttled recovery when the sound engine unloads it. */
final class LowHpAlertLoopPlayer {
    private final LowHpAlertPulseScheduler retryScheduler;
    private String currentEvent;
    private float currentVolume;
    private boolean started;

    LowHpAlertLoopPlayer(double retrySeconds) {
        retryScheduler = new LowHpAlertPulseScheduler(retrySeconds);
    }

    void update(double nowSeconds, boolean active, float volume, String eventId, Backend backend) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(backend, "backend");
        if (!Float.isFinite(volume) || volume < 0 || !Double.isFinite(nowSeconds)) {
            throw new IllegalArgumentException("Alert volume and clock must be finite and volume non-negative");
        }
        if (!active || volume == 0) {
            stop(nowSeconds, backend);
            return;
        }
        if (!eventId.equals(currentEvent) || Float.compare(volume, currentVolume) != 0) {
            stop(nowSeconds, backend);
            currentEvent = eventId;
            currentVolume = volume;
        }
        if (started && backend.isPlaying()) {
            return;
        }
        if (retryScheduler.shouldPulse(nowSeconds, true)) {
            if (started) {
                backend.stop();
            }
            backend.play(eventId, volume);
            started = true;
        }
    }

    private void stop(double nowSeconds, Backend backend) {
        if (started) {
            backend.stop();
        }
        started = false;
        currentEvent = null;
        retryScheduler.shouldPulse(nowSeconds, false);
    }

    interface Backend {
        boolean isPlaying();
        void play(String eventId, float volume);
        void stop();
    }
}
