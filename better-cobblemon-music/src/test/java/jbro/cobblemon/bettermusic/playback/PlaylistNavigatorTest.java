package jbro.cobblemon.bettermusic.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import org.junit.jupiter.api.Test;

final class PlaylistNavigatorTest {
    @Test
    void shufflePlaysEveryTrackBeforeRepeatingAndAvoidsCycleBoundaryRepeat() {
        var navigator = new PlaylistNavigator(new Random(17));
        var playlist = playlist(PlaylistDefinition.Selection.SHUFFLE, "a.ogg", "b.ogg", "c.ogg");

        var firstCycle = List.of(
            navigator.next("forest", playlist),
            navigator.next("forest", playlist),
            navigator.next("forest", playlist)
        );
        String nextCycleFirst = navigator.next("forest", playlist);

        assertEquals(new HashSet<>(List.of("a.ogg", "b.ogg", "c.ogg")), new HashSet<>(firstCycle));
        assertNotEquals(firstCycle.getLast(), nextCycleFirst);
    }

    @Test
    void sequentialUsesJsonOrderAndWraps() {
        var navigator = new PlaylistNavigator(new Random(1));
        var playlist = playlist(PlaylistDefinition.Selection.SEQUENTIAL, "a.ogg", "b.ogg");

        assertEquals("a.ogg", navigator.next("pvp", playlist));
        assertEquals("b.ogg", navigator.next("pvp", playlist));
        assertEquals("a.ogg", navigator.next("pvp", playlist));
    }

    @Test
    void randomNeverImmediatelyRepeatsWhenAnotherTrackExists() {
        var navigator = new PlaylistNavigator(new Random(2));
        var playlist = playlist(PlaylistDefinition.Selection.RANDOM, "a.ogg", "b.ogg", "c.ogg");
        String previous = navigator.next("wild", playlist);

        for (int index = 0; index < 100; index++) {
            String next = navigator.next("wild", playlist);
            assertNotEquals(previous, next);
            previous = next;
        }
    }

    private static PlaylistDefinition playlist(
        PlaylistDefinition.Selection selection,
        String... tracks
    ) {
        return new PlaylistDefinition(selection, 1.0, 0.0, List.of(tracks));
    }
}
