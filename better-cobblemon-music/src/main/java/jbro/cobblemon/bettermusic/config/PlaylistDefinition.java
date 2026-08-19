package jbro.cobblemon.bettermusic.config;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record PlaylistDefinition(
    Selection selection,
    double volume,
    double betweenTracksSeconds,
    List<String> tracks
) {
    public PlaylistDefinition {
        Objects.requireNonNull(selection, "selection");
        if (!Double.isFinite(volume) || volume < 0.0) {
            throw new IllegalArgumentException("volume must be finite and non-negative");
        }
        if (!Double.isFinite(betweenTracksSeconds) || betweenTracksSeconds < 0.0) {
            throw new IllegalArgumentException("betweenTracksSeconds must be finite and non-negative");
        }
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("tracks must not be empty");
        }
        if (new HashSet<>(tracks).size() != tracks.size()) {
            throw new IllegalArgumentException("tracks must not contain duplicates");
        }
    }

    public enum Selection {
        SHUFFLE,
        RANDOM,
        SEQUENTIAL
    }
}
