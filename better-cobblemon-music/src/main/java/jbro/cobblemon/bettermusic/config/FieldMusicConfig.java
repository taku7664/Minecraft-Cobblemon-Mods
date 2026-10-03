package jbro.cobblemon.bettermusic.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import jbro.cobblemon.bettermusic.field.FieldMusicRule;

public record FieldMusicConfig(
    PlaylistDefinition defaultPlaylist,
    Map<String, PlaylistDefinition> dimensions,
    Map<String, PlaylistDefinition> biomes,
    Map<String, PlaylistDefinition> biomePathContains,
    Optional<PlaylistDefinition> underground,
    Map<String, PlaylistDefinition> dayDimensions,
    Map<String, PlaylistDefinition> nightDimensions,
    List<FieldMusicRule> ruleOrder
) {
    public FieldMusicConfig {
        Objects.requireNonNull(defaultPlaylist, "defaultPlaylist");
        dimensions = copyOrdered(dimensions, "dimensions");
        biomes = copyOrdered(biomes, "biomes");
        biomePathContains = copyOrdered(biomePathContains, "biomePathContains");
        underground = Objects.requireNonNull(underground, "underground");
        dayDimensions = copyOrdered(dayDimensions, "dayDimensions");
        nightDimensions = copyOrdered(nightDimensions, "nightDimensions");
        ruleOrder = FieldMusicRule.validate(ruleOrder, biomes, biomePathContains, underground.isPresent());
    }

    public FieldMusicConfig(PlaylistDefinition defaultPlaylist, Map<String, PlaylistDefinition> dimensions,
        Map<String, PlaylistDefinition> biomes, Map<String, PlaylistDefinition> biomePathContains,
        Optional<PlaylistDefinition> underground, Map<String, PlaylistDefinition> dayDimensions,
        Map<String, PlaylistDefinition> nightDimensions) {
        this(defaultPlaylist, dimensions, biomes, biomePathContains, underground, dayDimensions, nightDimensions, List.of());
    }

    public FieldMusicConfig(PlaylistDefinition defaultPlaylist, Map<String, PlaylistDefinition> dimensions,
        Map<String, PlaylistDefinition> biomes, Map<String, PlaylistDefinition> biomePathContains,
        Optional<PlaylistDefinition> underground) {
        this(defaultPlaylist, dimensions, biomes, biomePathContains, underground, Map.of(), Map.of());
    }

    private static <K, V> Map<K, V> copyOrdered(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
