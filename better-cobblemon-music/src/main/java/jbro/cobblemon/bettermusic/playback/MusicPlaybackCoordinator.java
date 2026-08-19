package jbro.cobblemon.bettermusic.playback;

import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;

public final class MusicPlaybackCoordinator {
    private final PlaybackSettings settings;
    private Optional<Selection> current = Optional.empty();
    private String pendingFieldCue;
    private double pendingFieldSince;
    private String stableFieldCue;
    private double lastUpdateSeconds = Double.NEGATIVE_INFINITY;

    public MusicPlaybackCoordinator(PlaybackSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Optional<Transition> update(double nowSeconds, Input input) {
        requireMonotonicTime(nowSeconds);
        Objects.requireNonNull(input, "input");
        updateStableFieldCue(nowSeconds, input.fieldCue());

        Optional<Selection> target;
        if (input.battleActive()) {
            target = input.battleCue().map(cue -> new Selection(Mode.BATTLE, cue));
        } else {
            target = Optional.ofNullable(stableFieldCue).map(cue -> new Selection(Mode.FIELD, cue));
        }

        if (target.equals(current)) {
            return Optional.empty();
        }
        var transition = new Transition(
            current,
            target,
            settings.fadeOutSeconds(),
            settings.fadeInSeconds()
        );
        current = target;
        return Optional.of(transition);
    }

    public Optional<Selection> current() {
        return current;
    }

    private void updateStableFieldCue(double nowSeconds, Optional<String> detectedCue) {
        if (detectedCue.isEmpty()) {
            pendingFieldCue = null;
            stableFieldCue = null;
            return;
        }

        String cue = detectedCue.orElseThrow();
        if (!cue.equals(pendingFieldCue)) {
            pendingFieldCue = cue;
            pendingFieldSince = nowSeconds;
        }
        if (nowSeconds - pendingFieldSince >= settings.fieldChangeDelaySeconds()) {
            stableFieldCue = cue;
        }
    }

    private void requireMonotonicTime(double nowSeconds) {
        if (!Double.isFinite(nowSeconds) || nowSeconds < 0.0) {
            throw new IllegalArgumentException("nowSeconds must be finite and non-negative");
        }
        if (nowSeconds < lastUpdateSeconds) {
            throw new IllegalArgumentException("nowSeconds must not move backwards");
        }
        lastUpdateSeconds = nowSeconds;
    }

    public record Input(Optional<String> fieldCue, boolean battleActive, Optional<String> battleCue) {
        public Input {
            fieldCue = Objects.requireNonNull(fieldCue, "fieldCue");
            battleCue = Objects.requireNonNull(battleCue, "battleCue");
            fieldCue.ifPresent(cue -> requireCue(cue, "fieldCue"));
            battleCue.ifPresent(cue -> requireCue(cue, "battleCue"));
            if (!battleActive && battleCue.isPresent()) {
                throw new IllegalArgumentException("battleCue requires battleActive=true");
            }
        }

        public static Input field(String cue) {
            return new Input(Optional.of(cue), false, Optional.empty());
        }

        public static Input none() {
            return new Input(Optional.empty(), false, Optional.empty());
        }
    }

    public record Selection(Mode mode, String cue) {
        public Selection {
            Objects.requireNonNull(mode, "mode");
            requireCue(cue, "cue");
        }
    }

    public record Transition(
        Optional<Selection> from,
        Optional<Selection> to,
        double fadeOutSeconds,
        double fadeInSeconds
    ) {
        public Transition {
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
        }
    }

    public enum Mode {
        FIELD,
        BATTLE
    }

    private static void requireCue(String cue, String name) {
        Objects.requireNonNull(cue, name);
        if (cue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
