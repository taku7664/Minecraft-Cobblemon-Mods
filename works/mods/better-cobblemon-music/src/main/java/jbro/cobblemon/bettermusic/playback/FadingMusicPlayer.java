package jbro.cobblemon.bettermusic.playback;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class FadingMusicPlayer {
    private static final double STARTUP_GRACE_SECONDS = 0.1;
    private static final double MUFFLE_TRANSITION_SECONDS = 0.75;
    private static final double UNDERWATER_TRANSITION_SECONDS = 0.75;

    private final Backend backend;
    private final Consumer<Track> trackStarted;
    private Optional<TrackSource> desiredSource = Optional.empty();
    private ActiveTrack active;
    private ActiveTrack outgoing;
    private double restartAtSeconds = Double.POSITIVE_INFINITY;
    private double lastTimeSeconds = Double.NEGATIVE_INFINITY;
    private double lastEffectUpdateSeconds = Double.NaN;
    private double muffleAmount;
    private double underwaterAmount;
    private double underwaterTarget;
    private boolean muffled;
    private boolean distorted;

    public FadingMusicPlayer(Backend backend) {
        this(backend, ignored -> { });
    }

    public FadingMusicPlayer(Backend backend, Consumer<Track> trackStarted) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.trackStarted = Objects.requireNonNull(trackStarted, "trackStarted");
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
        updateEffects(nowSeconds);
        backend.setDistortion(distorted ? 1.0 : 0.0);
        backend.setEffects(muffleAmount, underwaterAmount);
        updateEnvelopes(nowSeconds);

        if (active != null
            && nowSeconds - active.startedAtSeconds >= STARTUP_GRACE_SECONDS
            && !backend.isPlaying(active.handle)) {
            backend.stop(active.handle);
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

    /** Reports a live track, not a selected playlist or the last announcement. */
    public Optional<Track> currentTrack() {
        if (active != null && backend.isPlaying(active.handle)) {
            return Optional.of(active.track);
        }
        if (outgoing != null && backend.isPlaying(outgoing.handle)) {
            return Optional.of(outgoing.track);
        }
        return Optional.empty();
    }

    public void setMuffled(boolean muffled) {
        this.muffled = muffled;
    }

    /** Hard-route battles play under a distortion. */
    public void setDistorted(boolean distorted) {
        this.distorted = distorted;
    }

    public void setUnderwater(double target) {
        if (!Double.isFinite(target) || target < 0.0 || target > 1.0) {
            throw new IllegalArgumentException("underwater target must be finite and between zero and one");
        }
        underwaterTarget = target;
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
        trackStarted.accept(track);
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

    private void updateEffects(double nowSeconds) {
        if (Double.isNaN(lastEffectUpdateSeconds)) {
            lastEffectUpdateSeconds = nowSeconds;
            return;
        }
        double elapsed = nowSeconds - lastEffectUpdateSeconds;
        lastEffectUpdateSeconds = nowSeconds;
        muffleAmount = approach(muffleAmount, muffled ? 1.0 : 0.0, elapsed / MUFFLE_TRANSITION_SECONDS);
        underwaterAmount = approach(underwaterAmount, underwaterTarget, elapsed / UNDERWATER_TRANSITION_SECONDS);
    }

    private static double approach(double current, double target, double maximumChange) {
        if (current < target) {
            return Math.min(target, current + maximumChange);
        }
        return Math.max(target, current - maximumChange);
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

        void setEffects(double muffleAmount, double underwaterAmount);

        /** How much of a distorted copy plays over the music, from zero (none) to one. */
        default void setDistortion(double amount) {
        }

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
            if (!Double.isFinite(volume) || volume < 0.0 || volume > Float.MAX_VALUE) {
                throw new IllegalArgumentException("volume must be a non-negative finite float");
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
