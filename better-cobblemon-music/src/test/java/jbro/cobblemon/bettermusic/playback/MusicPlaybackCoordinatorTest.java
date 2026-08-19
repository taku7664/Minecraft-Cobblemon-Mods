package jbro.cobblemon.bettermusic.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;
import org.junit.jupiter.api.Test;

final class MusicPlaybackCoordinatorTest {
    private static final PlaybackSettings SETTINGS = new PlaybackSettings(
        1.0,
        4.0,
        0.0,
        1.0,
        1.0,
        PlaybackSettings.MissingCueBehavior.FALLBACK
    );

    @Test
    void fieldCueMustRemainStableBeforeItStarts() {
        var coordinator = new MusicPlaybackCoordinator(SETTINGS);
        var forest = MusicPlaybackCoordinator.Input.field("field.forest");

        assertTrue(coordinator.update(0.0, forest).isEmpty());
        assertTrue(coordinator.update(3.99, forest).isEmpty());

        var transition = coordinator.update(4.0, forest).orElseThrow();
        assertTrue(transition.from().isEmpty());
        assertEquals(selection(MusicPlaybackCoordinator.Mode.FIELD, "field.forest"), transition.to().orElseThrow());
        assertEquals(1.0, transition.fadeInSeconds());
    }

    @Test
    void fieldBoundaryJitterKeepsTheOldTrackUntilTheNewCueIsStable() {
        var coordinator = new MusicPlaybackCoordinator(SETTINGS);
        coordinator.update(0.0, MusicPlaybackCoordinator.Input.field("field.forest"));
        coordinator.update(4.0, MusicPlaybackCoordinator.Input.field("field.forest")).orElseThrow();

        assertTrue(coordinator.update(5.0, MusicPlaybackCoordinator.Input.field("field.desert")).isEmpty());
        assertTrue(coordinator.update(8.99, MusicPlaybackCoordinator.Input.field("field.desert")).isEmpty());

        var transition = coordinator.update(9.0, MusicPlaybackCoordinator.Input.field("field.desert")).orElseThrow();
        assertEquals(selection(MusicPlaybackCoordinator.Mode.FIELD, "field.forest"), transition.from().orElseThrow());
        assertEquals(selection(MusicPlaybackCoordinator.Mode.FIELD, "field.desert"), transition.to().orElseThrow());
    }

    @Test
    void battleImmediatelyPreemptsFieldAndAnOriginalBattleSuppressesCustomMusic() {
        var coordinator = new MusicPlaybackCoordinator(SETTINGS);
        coordinator.update(0.0, MusicPlaybackCoordinator.Input.field("field.forest"));
        coordinator.update(4.0, MusicPlaybackCoordinator.Input.field("field.forest")).orElseThrow();

        var battle = coordinator.update(5.0, new MusicPlaybackCoordinator.Input(
            Optional.of("field.forest"),
            true,
            Optional.of("battle.gym")
        )).orElseThrow();
        assertEquals(selection(MusicPlaybackCoordinator.Mode.FIELD, "field.forest"), battle.from().orElseThrow());
        assertEquals(selection(MusicPlaybackCoordinator.Mode.BATTLE, "battle.gym"), battle.to().orElseThrow());

        assertTrue(coordinator.update(5.5, new MusicPlaybackCoordinator.Input(
            Optional.of("field.forest"),
            true,
            Optional.of("battle.gym")
        )).isEmpty());

        var useOriginal = coordinator.update(6.0, new MusicPlaybackCoordinator.Input(
            Optional.of("field.forest"),
            true,
            Optional.empty()
        )).orElseThrow();
        assertEquals(selection(MusicPlaybackCoordinator.Mode.BATTLE, "battle.gym"), useOriginal.from().orElseThrow());
        assertTrue(useOriginal.to().isEmpty());

        var resumeField = coordinator.update(7.0, MusicPlaybackCoordinator.Input.field("field.forest"))
            .orElseThrow();
        assertTrue(resumeField.from().isEmpty());
        assertEquals(selection(MusicPlaybackCoordinator.Mode.FIELD, "field.forest"), resumeField.to().orElseThrow());
    }

    @Test
    void fieldStabilityContinuesWhileBattleOwnsPlayback() {
        var coordinator = new MusicPlaybackCoordinator(SETTINGS);
        var battle = new MusicPlaybackCoordinator.Input(
            Optional.of("field.plains"),
            true,
            Optional.of("battle.wild")
        );

        var startBattle = coordinator.update(0.0, battle).orElseThrow();
        assertEquals(selection(MusicPlaybackCoordinator.Mode.BATTLE, "battle.wild"), startBattle.to().orElseThrow());
        assertTrue(coordinator.update(4.0, battle).isEmpty());

        var endBattle = coordinator.update(5.0, MusicPlaybackCoordinator.Input.field("field.plains"))
            .orElseThrow();
        assertEquals(selection(MusicPlaybackCoordinator.Mode.FIELD, "field.plains"), endBattle.to().orElseThrow());
    }

    private static MusicPlaybackCoordinator.Selection selection(
        MusicPlaybackCoordinator.Mode mode,
        String cue
    ) {
        return new MusicPlaybackCoordinator.Selection(mode, cue);
    }
}
