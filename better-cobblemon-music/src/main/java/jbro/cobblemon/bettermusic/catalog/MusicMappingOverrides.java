package jbro.cobblemon.bettermusic.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record MusicMappingOverrides(Field field, Battle battle) {
    public MusicMappingOverrides {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
    }

    public static MusicMappingOverrides empty() {
        return new MusicMappingOverrides(Field.empty(), Battle.empty());
    }

    public record Field(
        Optional<String> defaultPlaylistId,
        Map<String, String> dimensions,
        Map<String, String> biomes,
        Map<String, String> biomePathContains,
        Optional<String> undergroundPlaylistId
    ) {
        public Field {
            defaultPlaylistId = Objects.requireNonNull(defaultPlaylistId, "defaultPlaylistId");
            dimensions = ordered(dimensions, "dimensions");
            biomes = ordered(biomes, "biomes");
            biomePathContains = ordered(biomePathContains, "biomePathContains");
            undergroundPlaylistId = Objects.requireNonNull(undergroundPlaylistId, "undergroundPlaylistId");
        }

        public static Field empty() {
            return new Field(Optional.empty(), Map.of(), Map.of(), Map.of(), Optional.empty());
        }
    }

    public record Battle(
        Optional<String> wildPlaylistId,
        Optional<String> trainerPlaylistId,
        Optional<String> pvpPlaylistId,
        Map<String, String> content,
        Optional<String> legendaryPlaylistId,
        Optional<String> ultraBeastPlaylistId,
        List<CatalogMappings.PokemonMapping> pokemon
    ) {
        public Battle {
            wildPlaylistId = Objects.requireNonNull(wildPlaylistId, "wildPlaylistId");
            trainerPlaylistId = Objects.requireNonNull(trainerPlaylistId, "trainerPlaylistId");
            pvpPlaylistId = Objects.requireNonNull(pvpPlaylistId, "pvpPlaylistId");
            content = ordered(content, "content");
            legendaryPlaylistId = Objects.requireNonNull(legendaryPlaylistId, "legendaryPlaylistId");
            ultraBeastPlaylistId = Objects.requireNonNull(ultraBeastPlaylistId, "ultraBeastPlaylistId");
            pokemon = List.copyOf(Objects.requireNonNull(pokemon, "pokemon"));
        }

        public static Battle empty() {
            return new Battle(
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(),
                Optional.empty(), Optional.empty(), List.of()
            );
        }
    }

    private static Map<String, String> ordered(Map<String, String> values, String name) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, name)));
    }
}
