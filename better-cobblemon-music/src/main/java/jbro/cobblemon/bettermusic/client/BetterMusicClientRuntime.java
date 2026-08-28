package jbro.cobblemon.bettermusic.client;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigManager;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import jbro.cobblemon.bettermusic.battle.BattlePlaylistResolver;
import jbro.cobblemon.bettermusic.field.FieldPlaylistResolver;
import jbro.cobblemon.bettermusic.playback.FadingMusicPlayer;
import jbro.cobblemon.bettermusic.playback.MusicFileSoundIds;
import jbro.cobblemon.bettermusic.playback.MusicPlaybackCoordinator;
import jbro.cobblemon.bettermusic.playback.PlaylistNavigator;
import jbro.cobblemon.bettermusic.playback.PlayablePlaylistResolver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class BetterMusicClientRuntime {
    private static final double TICKS_PER_SECOND = 20.0;

    private final BetterMusicConfigManager configManager;
    private final Logger logger;
    private final MinecraftFieldMusicSampler fieldSampler = new MinecraftFieldMusicSampler();
    private final Cobblemon173BattleMusicSampler battleSampler;
    private final Set<String> reportedUnavailableTracks = new HashSet<>();
    private final Map<String, PlaylistDefinition> playlistsById = new HashMap<>();
    private final Map<String, String> fallbackPlaylistIds = new HashMap<>();
    private final PlaylistNavigator playlistNavigator = new PlaylistNavigator(ThreadLocalRandom.current());

    private BetterMusicConfigSnapshot snapshot;
    private MinecraftMusicBackend backend;
    private FadingMusicPlayer player;
    private MusicPlaybackCoordinator coordinator;
    private FieldPlaylistResolver fieldResolver;
    private BattlePlaylistResolver battleResolver;
    private long clientTicks;
    private double nextScanSeconds;
    private boolean inWorld;
    private boolean suppressOriginalMusic;
    private String lastDesiredPlaylistId;

    public BetterMusicClientRuntime(BetterMusicConfigManager configManager, Logger logger) {
        this.configManager = java.util.Objects.requireNonNull(configManager, "configManager");
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
        this.battleSampler = new Cobblemon173BattleMusicSampler(logger);
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        double nowSeconds = clientTicks++ / TICKS_PER_SECOND;
        applyConfigIfChanged(client, nowSeconds);
        if (player == null || coordinator == null || fieldResolver == null || battleResolver == null) {
            return;
        }

        player.tick(nowSeconds);
        if (client.player == null || client.level == null) {
            leaveWorld(nowSeconds);
        } else {
            inWorld = true;
            scanFieldIfDue(client, nowSeconds);
        }

        if (suppressOriginalMusic || player.ownsMusic()) {
            client.getMusicManager().stopPlaying();
        }
    }

    private void applyConfigIfChanged(Minecraft client, double nowSeconds) {
        BetterMusicConfigSnapshot latest = configManager.activeSnapshot().orElse(null);
        if (latest == null || latest == snapshot) {
            return;
        }

        if (backend == null) {
            backend = new MinecraftMusicBackend(client.getSoundManager());
        }
        if (player == null) {
            player = new FadingMusicPlayer(backend, latest.playback().betweenTracksSeconds());
        } else {
            player.setBetweenTracksSeconds(latest.playback().betweenTracksSeconds());
        }
        snapshot = latest;
        coordinator = new MusicPlaybackCoordinator(latest.playback());
        fieldResolver = new FieldPlaylistResolver(latest.field());
        battleResolver = new BattlePlaylistResolver(latest.battle());
        playlistsById.clear();
        fallbackPlaylistIds.clear();
        playlistNavigator.reset();
        reportedUnavailableTracks.clear();
        nextScanSeconds = nowSeconds;
        lastDesiredPlaylistId = null;
    }

    private void leaveWorld(double nowSeconds) {
        if (!inWorld) {
            return;
        }
        inWorld = false;
        coordinator.update(nowSeconds, MusicPlaybackCoordinator.Input.none())
            .ifPresent(transition -> applyTransition(nowSeconds, transition));
        nextScanSeconds = nowSeconds;
    }

    private void scanFieldIfDue(Minecraft client, double nowSeconds) {
        if (nowSeconds < nextScanSeconds) {
            return;
        }
        nextScanSeconds = nowSeconds + snapshot.playback().scanIntervalSeconds();

        Optional<String> fieldCue = fieldSampler.sample(client)
            .map(fieldResolver::select)
            .map(selection -> {
                playlistsById.put(selection.id(), selection.playlist());
                return selection.id();
            });
        Optional<String> battleCue = battleSampler.sample(client)
            .map(battleResolver::select)
            .map(selection -> {
                playlistsById.put(selection.id(), selection.playlist());
                selection.fallback().ifPresent(fallback -> {
                    playlistsById.put(fallback.id(), fallback.playlist());
                    fallbackPlaylistIds.put(selection.id(), fallback.id());
                });
                return selection.id();
            });
        var input = new MusicPlaybackCoordinator.Input(fieldCue, battleCue.isPresent(), battleCue);
        Optional<MusicPlaybackCoordinator.Transition> transition = coordinator.update(nowSeconds, input);
        transition.ifPresent(value -> applyTransition(nowSeconds, value));
        if (transition.isEmpty() && !player.ownsMusic() && lastDesiredPlaylistId != null) {
            playableSource(lastDesiredPlaylistId).ifPresent(source -> {
                player.transitionSource(
                    nowSeconds,
                    Optional.of(source),
                    snapshot.playback().fadeOutSeconds(),
                    snapshot.playback().fadeInSeconds()
                );
                suppressOriginalMusic = true;
            });
        }
    }

    private void applyTransition(double nowSeconds, MusicPlaybackCoordinator.Transition transition) {
        lastDesiredPlaylistId = transition.to().map(MusicPlaybackCoordinator.Selection::cue).orElse(null);
        Optional<FadingMusicPlayer.TrackSource> target = transition.to()
            .flatMap(selection -> playableSource(selection.cue()));
        player.transitionSource(
            nowSeconds,
            target,
            transition.fadeOutSeconds(),
            transition.fadeInSeconds()
        );
        suppressOriginalMusic = target.isPresent();
    }

    private Optional<FadingMusicPlayer.TrackSource> playableSource(String playlistId) {
        if (!playlistsById.containsKey(playlistId)) {
            logger.warn("Music playlist '{}' was selected but is no longer configured", playlistId);
            return Optional.empty();
        }

        Optional<PlayablePlaylistResolver.ResolvedPlaylist> resolved = PlayablePlaylistResolver.resolve(
            playlistId,
            playlistsById,
            fallbackPlaylistIds,
            track -> backend.isSoundAvailable(MusicFileSoundIds.soundEvent(track)),
            track -> {
                if (reportedUnavailableTracks.add(track)) {
                    logger.warn("Music file '{}' is unavailable in the generated resource pack", track);
                }
            }
        );
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String resolvedId = resolved.orElseThrow().id();
        PlaylistDefinition playable = resolved.orElseThrow().playlist();
        return Optional.of(new FadingMusicPlayer.TrackSource() {
            @Override
            public FadingMusicPlayer.Track nextTrack() {
                String track = playlistNavigator.next(resolvedId, playable);
                return new FadingMusicPlayer.Track(MusicFileSoundIds.soundEvent(track), playable.volume());
            }

            @Override
            public double betweenTracksSeconds() {
                return playable.betweenTracksSeconds();
            }
        });
    }
}
