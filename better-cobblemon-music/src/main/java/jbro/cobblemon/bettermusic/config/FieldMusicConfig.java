package jbro.cobblemon.bettermusic.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record FieldMusicConfig(
    PlaylistDefinition defaultPlaylist,
    Map<String, PlaylistDefinition> dimensions,
    Map<String, PlaylistDefinition> biomes,
    Map<String, PlaylistDefinition> biomePathContains,
    Optional<PlaylistDefinition> underground
) {
    public FieldMusicConfig {
        Objects.requireNonNull(defaultPlaylist, "defaultPlaylist");
        dimensions = copyOrdered(dimensions, "dimensions");
        biomes = copyOrdered(biomes, "biomes");
        biomePathContains = copyOrdered(biomePathContains, "biomePathContains");
        underground = Objects.requireNonNull(underground, "underground");
    }

    private static <K, V> Map<K, V> copyOrdered(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
