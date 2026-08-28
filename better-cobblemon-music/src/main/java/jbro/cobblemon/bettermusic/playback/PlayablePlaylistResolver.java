package jbro.cobblemon.bettermusic.playback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class PlayablePlaylistResolver {
    private PlayablePlaylistResolver() {
    }

    public static Optional<ResolvedPlaylist> resolve(
        String desiredId,
        Map<String, PlaylistDefinition> playlistsById,
        Map<String, String> fallbackById,
        Predicate<String> trackAvailable,
        Consumer<String> unavailableTrack
    ) {
        Objects.requireNonNull(desiredId, "desiredId");
        Objects.requireNonNull(playlistsById, "playlistsById");
        Objects.requireNonNull(fallbackById, "fallbackById");
        Objects.requireNonNull(trackAvailable, "trackAvailable");
        Objects.requireNonNull(unavailableTrack, "unavailableTrack");

        Set<String> visited = new HashSet<>();
        String candidateId = desiredId;
        while (candidateId != null && visited.add(candidateId)) {
            PlaylistDefinition candidate = playlistsById.get(candidateId);
            if (candidate == null) {
                return Optional.empty();
            }

            List<String> playableTracks = new ArrayList<>();
            for (String track : candidate.tracks()) {
                if (trackAvailable.test(track)) {
                    playableTracks.add(track);
                } else {
                    unavailableTrack.accept(track);
                }
            }
            if (!playableTracks.isEmpty()) {
                return Optional.of(new ResolvedPlaylist(
                    candidateId,
                    new PlaylistDefinition(
                        candidate.selection(),
                        candidate.volume(),
                        candidate.betweenTracksSeconds(),
                        playableTracks
                    )
                ));
            }
            candidateId = fallbackById.get(candidateId);
        }
        return Optional.empty();
    }

    public record ResolvedPlaylist(String id, PlaylistDefinition playlist) {
        public ResolvedPlaylist {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(playlist, "playlist");
        }
    }
}
