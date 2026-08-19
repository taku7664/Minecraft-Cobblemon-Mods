package jbro.cobblemon.bettermusic.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class FadingMusicPlayerTest {
    @Test
    void crossFadeUsesTheCurrentOutgoingVolumeAndTheConfiguredTargetVolume() {
        var backend = new FakeBackend();
        var player = new FadingMusicPlayer(backend, 0.0);
        var forest = new FadingMusicPlayer.Track("example:forest", 0.8);
        var desert = new FadingMusicPlayer.Track("example:desert", 0.6);

        player.transition(0.0, Optional.of(forest), 2.0, 2.0);
        assertEquals(0.0, backend.handles.get(0).volume, 0.0001);
        player.tick(1.0);
        assertEquals(0.4, backend.handles.get(0).volume, 0.0001);

        player.transition(1.0, Optional.of(desert), 2.0, 2.0);
        assertEquals(0.0, backend.handles.get(1).volume, 0.0001);
        player.tick(2.0);
        assertEquals(0.2, backend.handles.get(0).volume, 0.0001);
        assertEquals(0.3, backend.handles.get(1).volume, 0.0001);

        player.tick(3.0);
        assertTrue(backend.handles.get(0).stopped);
        assertEquals(0.6, backend.handles.get(1).volume, 0.0001);
        assertTrue(player.ownsMusic());
    }

    @Test
    void naturalTrackEndWaitsBeforeStartingTheSameCueAgain() {
        var backend = new FakeBackend();
        var player = new FadingMusicPlayer(backend, 2.0);
        var track = new FadingMusicPlayer.Track("example:forest", 1.0);
        player.transition(0.0, Optional.of(track), 0.0, 0.0);
        backend.handles.get(0).playing = false;

        player.tick(5.0);
        player.tick(6.99);
        assertEquals(1, backend.handles.size());

        player.tick(7.0);
        assertEquals(2, backend.handles.size());
        assertEquals(1.0, backend.handles.get(1).volume, 0.0001);
    }

    @Test
    void naturalTrackEndRequestsTheNextTrackFromTheActivePlaylist() {
        var backend = new FakeBackend();
        var player = new FadingMusicPlayer(backend, 99.0);
        var tracks = List.of(
            new FadingMusicPlayer.Track("example:first", 1.0),
            new FadingMusicPlayer.Track("example:second", 0.8)
        );
        int[] next = {0};
        var source = new FadingMusicPlayer.TrackSource() {
            @Override
            public FadingMusicPlayer.Track nextTrack() {
                return tracks.get(next[0]++);
            }

            @Override
            public double betweenTracksSeconds() {
                return 2.0;
            }
        };

        player.transitionSource(0.0, Optional.of(source), 0.0, 0.0);
        backend.handles.getFirst().playing = false;

        player.tick(5.0);
        player.tick(6.99);
        assertEquals(1, backend.handles.size());
        player.tick(7.0);

        assertEquals(2, backend.handles.size());
        assertEquals("example:second", backend.handles.get(1).track.sound());
        assertEquals(0.8, backend.handles.get(1).volume, 0.0001);
    }

    @Test
    void immediateStopReleasesEveryHandleAndMusicOwnership() {
        var backend = new FakeBackend();
        var player = new FadingMusicPlayer(backend, 0.0);
        player.transition(
            0.0,
            Optional.of(new FadingMusicPlayer.Track("example:first", 1.0)),
            1.0,
            1.0
        );
        player.transition(
            0.5,
            Optional.of(new FadingMusicPlayer.Track("example:second", 1.0)),
            1.0,
            1.0
        );

        player.stopImmediately();

        assertTrue(backend.handles.stream().allMatch(handle -> handle.stopped));
        assertFalse(player.ownsMusic());
    }

    private static final class FakeBackend implements FadingMusicPlayer.Backend {
        private final List<FakeHandle> handles = new ArrayList<>();

        @Override
        public FadingMusicPlayer.Handle play(FadingMusicPlayer.Track track, double initialVolume) {
            var handle = new FakeHandle(track, initialVolume);
            handles.add(handle);
            return handle;
        }

        @Override
        public void setVolume(FadingMusicPlayer.Handle handle, double volume) {
            ((FakeHandle) handle).volume = volume;
        }

        @Override
        public void stop(FadingMusicPlayer.Handle handle) {
            var fake = (FakeHandle) handle;
            fake.stopped = true;
            fake.playing = false;
        }

        @Override
        public boolean isPlaying(FadingMusicPlayer.Handle handle) {
            return ((FakeHandle) handle).playing;
        }
    }

    private static final class FakeHandle implements FadingMusicPlayer.Handle {
        private final FadingMusicPlayer.Track track;
        private double volume;
        private boolean playing = true;
        private boolean stopped;

        private FakeHandle(FadingMusicPlayer.Track track, double volume) {
            this.track = track;
            this.volume = volume;
        }
    }
}
