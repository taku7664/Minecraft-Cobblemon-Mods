package jbro.cobblemon.bettermusic.playback;

import java.util.Objects;
import java.util.Optional;

public final class FadingMusicPlayer {
    private static final double STARTUP_GRACE_SECONDS = 0.1;

    private final Backend backend;
    private double betweenTracksSeconds;
    private Optional<TrackSource> desiredSource = Optional.empty();
    private ActiveTrack active;
    private ActiveTrack outgoing;
    private double restartAtSeconds = Double.POSITIVE_INFINITY;
    private double lastTimeSeconds = Double.NEGATIVE_INFINITY;

    public FadingMusicPlayer(Backend backend, double betweenTracksSeconds) {
        this.backend = Objects.requireNonNull(backend, "backend");
        requireDuration(betweenTracksSeconds, "betweenTracksSeconds");
        this.betweenTracksSeconds = betweenTracksSeconds;
    }

    public void setBetweenTracksSeconds(double betweenTracksSeconds) {
        requireDuration(betweenTracksSeconds, "betweenTracksSeconds");
        this.betweenTracksSeconds = betweenTracksSeconds;
    }

    public void transition(
        double nowSeconds,
        Optional<Track> target,
        double fadeOutSeconds,
        double fadeInSeconds
    ) {
        target = Objects.requireNonNull(target, "target");
        Optional<TrackSource> source = target.map(track -> new TrackSource() {
            @Override
            public Track nextTrack() {
                return track;
            }

            @Override
            public double betweenTracksSeconds() {
                return betweenTracksSeconds;
            }
        });
        transitionSource(nowSeconds, source, fadeOutSeconds, fadeInSeconds);
    }

    public void transitionSource(
        double nowSeconds,
        Optional<TrackSource> target,
        double fadeOutSeconds,
        double fadeInSeconds
    ) {
        requireTime(nowSeconds);
        target = Objects.requireNonNull(target, "target");
        requireDuration(fadeOutSeconds, "fadeOutSeconds");
        requireDuration(fadeInSeconds, "fadeInSeconds");
        updateEnvelopes(nowSeconds);

        stopOutgoing();
        if (active != null) {
            if (fadeOutSeconds > 0.0 && active.currentVolume > 0.0) {
                outgoing = active.fadeToZero(nowSeconds, fadeOutSeconds);
            } else {
                backend.stop(active.handle);
            }
            active = null;
        }

        desiredSource = target;
        restartAtSeconds = Double.POSITIVE_INFINITY;
        target.ifPresent(source -> startTrack(source.nextTrack(), nowSeconds, fadeInSeconds));
    }

    public void tick(double nowSeconds) {
        requireTime(nowSeconds);
        updateEnvelopes(nowSeconds);

        if (active != null
            && nowSeconds - active.startedAtSeconds >= STARTUP_GRACE_SECONDS
            && !backend.isPlaying(active.handle)) {
            active = null;
            restartAtSeconds = desiredSource.isPresent()
                ? nowSeconds + desiredSource.orElseThrow().betweenTracksSeconds()
                : Double.POSITIVE_INFINITY;
        }

        if (active == null && desiredSource.isPresent() && nowSeconds >= restartAtSeconds) {
            startTrack(desiredSource.orElseThrow().nextTrack(), nowSeconds, 0.0);
        }
    }

    public boolean ownsMusic() {
        return desiredSource.isPresent() || active != null || outgoing != null;
    }

    public void stopImmediately() {
        if (active != null) {
            backend.stop(active.handle);
            active = null;
        }
        stopOutgoing();
        desiredSource = Optional.empty();
        restartAtSeconds = Double.POSITIVE_INFINITY;
    }

    private void startTrack(Track track, double nowSeconds, double fadeInSeconds) {
        double initialVolume = fadeInSeconds == 0.0 ? track.volume() : 0.0;
        Handle handle = backend.play(track, initialVolume);
        active = new ActiveTrack(
            handle,
            track,
            nowSeconds,
            nowSeconds,
            fadeInSeconds,
            initialVolume,
            track.volume(),
            initialVolume
        );
        restartAtSeconds = Double.POSITIVE_INFINITY;
    }

    private void updateEnvelopes(double nowSeconds) {
        if (outgoing != null) {
            updateVolume(outgoing, nowSeconds);
            if (outgoing.fadeComplete(nowSeconds) || !backend.isPlaying(outgoing.handle)) {
                backend.stop(outgoing.handle);
                outgoing = null;
            }
        }
        if (active != null) {
            updateVolume(active, nowSeconds);
        }
    }

    private void updateVolume(ActiveTrack track, double nowSeconds) {
        track.currentVolume = interpolate(
            track.startVolume,
            track.targetVolume,
            track.progress(nowSeconds)
        );
        backend.setVolume(track.handle, track.currentVolume);
    }

    private void stopOutgoing() {
        if (outgoing != null) {
            backend.stop(outgoing.handle);
            outgoing = null;
        }
    }

    private void requireTime(double nowSeconds) {
        if (!Double.isFinite(nowSeconds) || nowSeconds < 0.0) {
            throw new IllegalArgumentException("nowSeconds must be finite and non-negative");
        }
        if (nowSeconds < lastTimeSeconds) {
            throw new IllegalArgumentException("nowSeconds must not move backwards");
        }
        lastTimeSeconds = nowSeconds;
    }

    private static void requireDuration(double duration, String name) {
        if (!Double.isFinite(duration) || duration < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static double interpolate(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    public interface Backend {
        Handle play(Track track, double initialVolume);

        void setVolume(Handle handle, double volume);

        void stop(Handle handle);

        boolean isPlaying(Handle handle);
    }

    public interface Handle {
    }

    public interface TrackSource {
        Track nextTrack();

        double betweenTracksSeconds();
    }

    public record Track(String sound, double volume) {
        public Track {
            Objects.requireNonNull(sound, "sound");
            if (sound.isBlank()) {
                throw new IllegalArgumentException("sound must not be blank");
            }
            if (!Double.isFinite(volume) || volume < 0.0) {
                throw new IllegalArgumentException("volume must be finite and non-negative");
            }
        }
    }

    private static final class ActiveTrack {
        private final Handle handle;
        private final Track track;
        private final double startedAtSeconds;
        private final double fadeStartedAtSeconds;
        private final double fadeDurationSeconds;
        private final double startVolume;
        private final double targetVolume;
        private double currentVolume;

        private ActiveTrack(
            Handle handle,
            Track track,
            double startedAtSeconds,
            double fadeStartedAtSeconds,
            double fadeDurationSeconds,
            double startVolume,
            double targetVolume,
            double currentVolume
        ) {
            this.handle = Objects.requireNonNull(handle, "handle");
            this.track = Objects.requireNonNull(track, "track");
            this.startedAtSeconds = startedAtSeconds;
            this.fadeStartedAtSeconds = fadeStartedAtSeconds;
            this.fadeDurationSeconds = fadeDurationSeconds;
            this.startVolume = startVolume;
            this.targetVolume = targetVolume;
            this.currentVolume = currentVolume;
        }

        private ActiveTrack fadeToZero(double nowSeconds, double fadeOutSeconds) {
            return new ActiveTrack(
                handle,
                track,
                startedAtSeconds,
                nowSeconds,
                fadeOutSeconds,
                currentVolume,
                0.0,
                currentVolume
            );
        }

        private double progress(double nowSeconds) {
            if (fadeDurationSeconds == 0.0) {
                return 1.0;
            }
            return Math.clamp((nowSeconds - fadeStartedAtSeconds) / fadeDurationSeconds, 0.0, 1.0);
        }

        private boolean fadeComplete(double nowSeconds) {
            return progress(nowSeconds) >= 1.0;
        }
    }
}
