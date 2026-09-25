package jbro.cobblemon.bettermusic.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public record CompiledMusicConfiguration(
    BetterMusicConfigSnapshot snapshot,
    Map<String, String> trackEvents,
    Map<String, PlaylistDefinition> playlists,
    MusicCatalog.AudioEvents audioEvents,
    Set<String> activeExtensionPackIds,
    List<String> diagnostics,
    Map<String, String> inactiveOverrides
) {
    public CompiledMusicConfiguration {
        Objects.requireNonNull(snapshot, "snapshot");
        trackEvents = immutableMap(trackEvents, "trackEvents");
        playlists = immutableMap(playlists, "playlists");
        Objects.requireNonNull(audioEvents, "audioEvents");
        activeExtensionPackIds = Set.copyOf(Objects.requireNonNull(activeExtensionPackIds, "activeExtensionPackIds"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        inactiveOverrides = immutableMap(inactiveOverrides, "inactiveOverrides");
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values, String name) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, name)));
    }
}
