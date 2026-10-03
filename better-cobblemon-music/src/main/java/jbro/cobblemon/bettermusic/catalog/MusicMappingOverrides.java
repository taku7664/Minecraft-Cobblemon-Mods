package jbro.cobblemon.bettermusic.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record MusicMappingOverrides(Field field, Battle battle, Map<String, String> screens) {
    public MusicMappingOverrides {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
        screens = ordered(screens, "screens");
    }

    public MusicMappingOverrides(Field field, Battle battle) {
        this(field, battle, Map.of());
    }

    public static MusicMappingOverrides empty() {
        return new MusicMappingOverrides(Field.empty(), Battle.empty(), Map.of());
    }

    public MusicMappingOverrides withField(Field value) {
        return new MusicMappingOverrides(value, battle, screens);
    }

    public MusicMappingOverrides withBattle(Battle value) {
        return new MusicMappingOverrides(field, value, screens);
    }

    public MusicMappingOverrides withScreens(Map<String, String> value) {
        return new MusicMappingOverrides(field, battle, value);
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
        Optional<String> alphaPlaylistId,
        List<CatalogMappings.PokemonMapping> pokemon
    ) {
        public Battle {
            wildPlaylistId = Objects.requireNonNull(wildPlaylistId, "wildPlaylistId");
            trainerPlaylistId = Objects.requireNonNull(trainerPlaylistId, "trainerPlaylistId");
            pvpPlaylistId = Objects.requireNonNull(pvpPlaylistId, "pvpPlaylistId");
            content = ordered(content, "content");
            legendaryPlaylistId = Objects.requireNonNull(legendaryPlaylistId, "legendaryPlaylistId");
            ultraBeastPlaylistId = Objects.requireNonNull(ultraBeastPlaylistId, "ultraBeastPlaylistId");
            alphaPlaylistId = Objects.requireNonNull(alphaPlaylistId, "alphaPlaylistId");
            pokemon = List.copyOf(Objects.requireNonNull(pokemon, "pokemon"));
        }

        public Battle(Optional<String> wildPlaylistId, Optional<String> trainerPlaylistId,
            Optional<String> pvpPlaylistId, Map<String, String> content, Optional<String> legendaryPlaylistId,
            Optional<String> ultraBeastPlaylistId, List<CatalogMappings.PokemonMapping> pokemon) {
            this(wildPlaylistId, trainerPlaylistId, pvpPlaylistId, content, legendaryPlaylistId,
                ultraBeastPlaylistId, Optional.empty(), pokemon);
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
