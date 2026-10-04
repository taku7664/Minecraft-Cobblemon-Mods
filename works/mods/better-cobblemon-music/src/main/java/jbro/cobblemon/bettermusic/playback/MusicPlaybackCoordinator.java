package jbro.cobblemon.bettermusic.playback;

import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;

public final class MusicPlaybackCoordinator {
    /** How long the field's music takes to fall away when a battle cuts in. */
    static final double BATTLE_ENTRY_FADE_OUT_SECONDS = 0.35;

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

        // Battle music wins, then an open screen's music, then the field's; the field keeps settling underneath.
        Optional<Selection> target;
        if (input.battleActive()) {
            target = input.battleCue().map(cue -> new Selection(Mode.BATTLE, cue));
        } else if (input.screenCue().isPresent()) {
            target = input.screenCue().map(cue -> new Selection(Mode.SCREEN, cue));
        } else {
            target = Optional.ofNullable(stableFieldCue).map(cue -> new Selection(Mode.FIELD, cue));
        }

        if (target.equals(current)) {
            return Optional.empty();
        }
        // Entering a battle cuts in at once, as the games do with the entry transition; the rest crossfade.
        boolean enteringBattle = target.map(selection -> selection.mode() == Mode.BATTLE).orElse(false)
            && current.map(selection -> selection.mode() != Mode.BATTLE).orElse(true);
        var transition = new Transition(
            current,
            target,
            enteringBattle ? Math.min(settings.fadeOutSeconds(), BATTLE_ENTRY_FADE_OUT_SECONDS) : settings.fadeOutSeconds(),
            enteringBattle ? 0.0 : settings.fadeInSeconds()
        );
        current = target;
        return Optional.of(transition);
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

    public record Input(
        Optional<String> fieldCue,
        boolean battleActive,
        Optional<String> battleCue,
        Optional<String> screenCue
    ) {
        public Input {
            fieldCue = Objects.requireNonNull(fieldCue, "fieldCue");
            battleCue = Objects.requireNonNull(battleCue, "battleCue");
            screenCue = Objects.requireNonNull(screenCue, "screenCue");
            fieldCue.ifPresent(cue -> requireCue(cue, "fieldCue"));
            battleCue.ifPresent(cue -> requireCue(cue, "battleCue"));
            screenCue.ifPresent(cue -> requireCue(cue, "screenCue"));
            if (!battleActive && battleCue.isPresent()) {
                throw new IllegalArgumentException("battleCue requires battleActive=true");
            }
        }

        public Input(Optional<String> fieldCue, boolean battleActive, Optional<String> battleCue) {
            this(fieldCue, battleActive, battleCue, Optional.empty());
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
        SCREEN,
        BATTLE
    }

    private static void requireCue(String cue, String name) {
        Objects.requireNonNull(cue, name);
        if (cue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
