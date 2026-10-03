package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import jbro.cobblemon.bettermusic.playback.FadingMusicPlayer;
import jbro.cobblemon.bettermusic.playback.PlaylistNavigator;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class FadingMusicSoundInstanceTest {
    @Test
    void zeroVolumeFadeInIsAllowedToStartWithoutLoopingTheFirstSelectedSongForever() {
        var sound = new FadingMusicSoundInstance(ResourceLocation.parse("example:bgm"), 0.0);
        assertTrue(sound.canStartSilent(), "Minecraft otherwise discards a fade-in before it starts");
        assertFalse(sound.isLooping(), "The player, not the sound engine, advances the playlist");
    }

    @Test
    void theFirstRandomPickSurvivesFadeInAndTheNextSongExcludesTheFinishedSong() {
        var navigator = new PlaylistNavigator(new Random() {
            @Override public int nextInt(int bound) { return bound - 1; }
        });
        var playlist = new PlaylistDefinition(PlaylistDefinition.Selection.RANDOM, 1.0, 0.0,
            List.of("example:first", "example:second"));
        var backend = new ZeroVolumeAwareBackend();
        var starts = new ArrayList<String>();
        var player = new FadingMusicPlayer(backend, track -> starts.add(track.sound()));
        var source = new FadingMusicPlayer.TrackSource() {
            public FadingMusicPlayer.Track nextTrack() {
                return new FadingMusicPlayer.Track(navigator.next("test", playlist), 1.0);
            }
            public double betweenTracksSeconds() { return 0.0; }
        };
        player.transitionSource(0.0, Optional.of(source), 1.0, 1.0);
        for (int tick = 1; tick <= 20; tick++) {
            player.tick(tick * 0.05);
        }
        assertEquals(List.of("example:second"), starts, "A discarded zero-volume start must not consume another random pick");
        assertEquals("example:second", player.currentTrack().orElseThrow().sound());
        backend.playing.clear();
        player.tick(2.0);
        assertEquals(List.of("example:second", "example:first"), starts);
    }

    /** Mirrors the verified Minecraft 1.21.1 zero-volume admission rule. */
    private static final class ZeroVolumeAwareBackend implements FadingMusicPlayer.Backend {
        private final Set<FadingMusicPlayer.Handle> playing = new HashSet<>();

        public FadingMusicPlayer.Handle play(FadingMusicPlayer.Track track, double initialVolume) {
            var sound = new FadingMusicSoundInstance(ResourceLocation.parse(track.sound()), initialVolume);
            if (initialVolume > 0.0 || sound.canStartSilent()) {
                playing.add(sound);
            }
            return sound;
        }
        public void setVolume(FadingMusicPlayer.Handle handle, double volume) {
            ((FadingMusicSoundInstance) handle).setMusicVolume(volume);
        }
        public void setEffects(double muffleAmount, double underwaterAmount) { }
        public void stop(FadingMusicPlayer.Handle handle) { playing.remove(handle); }
        public boolean isPlaying(FadingMusicPlayer.Handle handle) { return playing.contains(handle); }
    }
}
