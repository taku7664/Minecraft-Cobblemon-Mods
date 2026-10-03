package jbro.cobblemon.bettermusic.playback;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class NowPlayingAnnouncementTest {
    @Test
    void slidesAndFadesInThenExpiresWithoutAnAccumulatingQueue() {
        var alert = new NowPlayingAnnouncement();
        assertTrue(alert.frame(0.0).isEmpty());
        alert.trackStarted("example:a", "First", 10.0);
        var start = alert.frame(10.0).orElseThrow();
        var halfway = alert.frame(10.175).orElseThrow();
        var held = alert.frame(11.0).orElseThrow();
        assertEquals(0.0, start.opacity());
        assertTrue(start.offsetX() < halfway.offsetX());
        assertTrue(halfway.opacity() > 0.0 && halfway.opacity() < 1.0);
        assertEquals(1.0, held.opacity());
        assertEquals(0.0, held.offsetX());
        var leaving = alert.frame(12.8).orElseThrow();
        assertTrue(leaving.opacity() < 1.0 && leaving.opacity() > 0.0);
        assertTrue(leaving.offsetX() < 0.0);
        assertTrue(alert.frame(13.0).isEmpty());
        alert.trackStarted("example:b", "Second", 13.0);
        assertEquals("Second", alert.frame(14.0).orElseThrow().title());
    }

    @Test
    void sameSoundRestartDoesNotResetTheAnnouncementButANewSoundDoes() {
        var alert = new NowPlayingAnnouncement();
        alert.trackStarted("example:a", "First", 0.0);
        alert.trackStarted("example:a", "First", 2.0);
        assertTrue(alert.frame(3.0).isEmpty());
        alert.trackStarted("example:b", "Second", 3.0);
        assertEquals("Second", alert.frame(4.0).orElseThrow().title());
        alert.clear();
        assertTrue(alert.frame(4.0).isEmpty());
        alert.trackStarted("example:b", "Second", 5.0);
        assertTrue(alert.frame(6.0).isPresent());
    }

    @Test
    void disablingClearsTextAndReenablingDoesNotReplaySkippedChanges() {
        var alert = new NowPlayingAnnouncement();
        alert.trackStarted("example:a", "First", 0.0);
        alert.setEnabled(false);
        assertTrue(alert.frame(0.5).isEmpty());
        alert.trackStarted("example:b", "Second", 1.0);
        alert.setEnabled(true);
        assertTrue(alert.frame(1.5).isEmpty());
        alert.trackStarted("example:b", "Second", 2.0);
        assertTrue(alert.frame(2.5).isEmpty());
        alert.trackStarted("example:c", "Third", 3.0);
        assertEquals("Third", alert.frame(4.0).orElseThrow().title());
    }

    @Test
    void titlesAreSingleLineAndCannotInjectMinecraftFormatting() {
        var alert = new NowPlayingAnnouncement();
        alert.trackStarted("example:a", "  Title\nSecond\tLine\u00a7c  ", 0.0);
        assertEquals("Title Second Line", alert.frame(1.0).orElseThrow().title());
        alert.trackStarted("example:b", "   ", 1.0);
        assertEquals("example:b", alert.frame(2.0).orElseThrow().title());
    }
}
