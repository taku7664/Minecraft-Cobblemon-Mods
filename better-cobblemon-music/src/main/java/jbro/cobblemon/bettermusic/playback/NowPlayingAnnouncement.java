package jbro.cobblemon.bettermusic.playback;

import java.util.Optional;
import java.util.Objects;

/** Client-independent, bounded state for a single text-only BGM announcement. */
public final class NowPlayingAnnouncement {
    private static final double ENTER_SECONDS = 0.35;
    private static final double HOLD_SECONDS = 2.2;
    private static final double EXIT_SECONDS = 0.45;
    private static final double TOTAL_SECONDS = 3.0;
    private boolean enabled = true;
    private String lastSound;
    private String title;
    private double startedAt;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            title = null;
        }
    }

    public void trackStarted(String sound, String title, double nowSeconds) {
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(title, "title");
        if (!Double.isFinite(nowSeconds)) {
            throw new IllegalArgumentException("nowSeconds must be finite");
        }
        if (sound.equals(lastSound)) {
            return;
        }
        lastSound = sound;
        if (enabled) {
            String clean = title.replaceAll("\u00a7.", "")
                .replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").strip();
            if (clean.isEmpty()) {
                clean = sound;
            }
            int count = clean.codePointCount(0, clean.length());
            this.title = count > 256 ? clean.substring(0, clean.offsetByCodePoints(0, 256)) : clean;
            startedAt = nowSeconds;
        }
    }

    public void clear() {
        title = null;
        lastSound = null;
    }

    public Optional<Frame> frame(double nowSeconds) {
        double elapsed = nowSeconds - startedAt;
        if (!enabled || title == null || !Double.isFinite(elapsed) || elapsed < 0.0
            || elapsed >= TOTAL_SECONDS) {
            return Optional.empty();
        }
        double opacity = 1.0;
        double offset = 0.0;
        if (elapsed < ENTER_SECONDS) {
            double progress = elapsed / ENTER_SECONDS;
            opacity = 1.0 - Math.pow(1.0 - progress, 3.0);
            offset = -16.0 * (1.0 - opacity);
        } else if (elapsed > ENTER_SECONDS + HOLD_SECONDS) {
            double progress = (elapsed - ENTER_SECONDS - HOLD_SECONDS) / EXIT_SECONDS;
            opacity = 1.0 - progress * progress * progress;
            offset = -12.0 * progress * progress * progress;
        }
        return Optional.of(new Frame(title, opacity, offset));
    }

    public record Frame(String title, double opacity, double offsetX) { }
}
