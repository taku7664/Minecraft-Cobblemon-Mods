package jbro.cobblemon.bettermusic.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import org.junit.jupiter.api.Test;

final class PlayablePlaylistResolverTest {
    @Test
    void missingSpecializedPlaylistFallsBackToDefaultBattlePlaylist() {
        List<String> unavailable = new ArrayList<>();

        var resolved = PlayablePlaylistResolver.resolve(
            "battle.pokemon:0",
            Map.of(
                "battle.pokemon:0", playlist("missing_legendary.ogg"),
                "battle.wild", playlist("wild.ogg")
            ),
            Map.of("battle.pokemon:0", "battle.wild"),
            track -> track.equals("wild.ogg"),
            unavailable::add
        ).orElseThrow();

        assertEquals("battle.wild", resolved.id());
        assertEquals(List.of("wild.ogg"), resolved.playlist().tracks());
        assertEquals(List.of("missing_legendary.ogg"), unavailable);
    }

    @Test
    void partiallyAvailableSpecializedPlaylistDoesNotFallBack() {
        var resolved = PlayablePlaylistResolver.resolve(
            "battle.pokemon:0",
            Map.of(
                "battle.pokemon:0", new PlaylistDefinition(
                    PlaylistDefinition.Selection.SHUFFLE,
                    0.7,
                    1.5,
                    List.of("missing.ogg", "legendary.ogg")
                ),
                "battle.wild", playlist("wild.ogg")
            ),
            Map.of("battle.pokemon:0", "battle.wild"),
            track -> !track.equals("missing.ogg"),
            ignored -> { }
        ).orElseThrow();

        assertEquals("battle.pokemon:0", resolved.id());
        assertEquals(List.of("legendary.ogg"), resolved.playlist().tracks());
        assertEquals(0.7, resolved.playlist().volume());
        assertEquals(1.5, resolved.playlist().betweenTracksSeconds());
    }

    private static PlaylistDefinition playlist(String track) {
        return new PlaylistDefinition(
            PlaylistDefinition.Selection.SHUFFLE, 1.0, 0.0, List.of(track)
        );
    }
}
