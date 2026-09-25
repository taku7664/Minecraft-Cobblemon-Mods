package jbro.cobblemon.bettermusic.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public record MusicCatalog(
    int schemaVersion,
    String packId,
    Kind kind,
    Map<String, Track> tracks,
    Map<String, Playlist> playlists,
    Optional<CatalogMappings> mappings,
    Optional<AudioEvents> audioEvents
) {
    public MusicCatalog {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(kind, "kind");
        tracks = ordered(tracks, "tracks");
        playlists = ordered(playlists, "playlists");
        mappings = Objects.requireNonNull(mappings, "mappings");
        audioEvents = Objects.requireNonNull(audioEvents, "audioEvents");
    }

    public enum Kind {
        BASE,
        EXTENSION
    }

    public record Track(String eventId, String title) {
        public Track {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(title, "title");
        }
    }

    public record Playlist(
        Optional<PlaylistDefinition.Selection> selection,
        Optional<Double> volume,
        Optional<Double> betweenTracksSeconds,
        List<String> tracks
    ) {
        public Playlist {
            selection = Objects.requireNonNull(selection, "selection");
            volume = Objects.requireNonNull(volume, "volume");
            betweenTracksSeconds = Objects.requireNonNull(betweenTracksSeconds, "betweenTracksSeconds");
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
            if (tracks.isEmpty()) {
                throw new IllegalArgumentException("tracks must not be empty");
            }
        }
    }

    public record AudioEvents(
        String hitNormal,
        String hitSuperEffective,
        String hitNotVeryEffective,
        String heartbeat
    ) {
        public AudioEvents {
            Objects.requireNonNull(hitNormal, "hitNormal");
            Objects.requireNonNull(hitSuperEffective, "hitSuperEffective");
            Objects.requireNonNull(hitNotVeryEffective, "hitNotVeryEffective");
            Objects.requireNonNull(heartbeat, "heartbeat");
        }
    }

    private static <T> Map<String, T> ordered(Map<String, T> values, String name) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, name)));
    }
}
