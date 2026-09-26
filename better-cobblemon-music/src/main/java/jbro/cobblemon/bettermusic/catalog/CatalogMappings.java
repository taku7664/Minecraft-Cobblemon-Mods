package jbro.cobblemon.bettermusic.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;

public record CatalogMappings(Field field, Battle battle) {
    public CatalogMappings {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
    }

    public record Field(
        String defaultPlaylistId,
        Map<String, String> dimensions,
        Map<String, String> biomes,
        Map<String, String> biomePathContains,
        Optional<String> undergroundPlaylistId
    ) {
        public Field {
            Objects.requireNonNull(defaultPlaylistId, "defaultPlaylistId");
            dimensions = ordered(dimensions, "dimensions");
            biomes = ordered(biomes, "biomes");
            biomePathContains = ordered(biomePathContains, "biomePathContains");
            undergroundPlaylistId = Objects.requireNonNull(undergroundPlaylistId, "undergroundPlaylistId");
        }
    }

    public record Battle(
        String wildPlaylistId,
        String trainerPlaylistId,
        String pvpPlaylistId,
        Map<String, String> content,
        Optional<String> legendaryPlaylistId,
        Optional<String> ultraBeastPlaylistId,
        List<PokemonMapping> pokemon
    ) {
        public Battle {
            Objects.requireNonNull(wildPlaylistId, "wildPlaylistId");
            Objects.requireNonNull(trainerPlaylistId, "trainerPlaylistId");
            Objects.requireNonNull(pvpPlaylistId, "pvpPlaylistId");
            content = ordered(content, "content");
            legendaryPlaylistId = Objects.requireNonNull(legendaryPlaylistId, "legendaryPlaylistId");
            ultraBeastPlaylistId = Objects.requireNonNull(ultraBeastPlaylistId, "ultraBeastPlaylistId");
            pokemon = List.copyOf(Objects.requireNonNull(pokemon, "pokemon"));
        }
    }

    public record PokemonMapping(
        Set<String> species,
        Set<BattleMusicConfig.BattleType> only,
        String playlistId
    ) {
        public PokemonMapping {
            species = Set.copyOf(Objects.requireNonNull(species, "species"));
            if (species.isEmpty()) {
                throw new IllegalArgumentException("species must not be empty");
            }
            only = Set.copyOf(Objects.requireNonNull(only, "only"));
            Objects.requireNonNull(playlistId, "playlistId");
        }
    }

    private static Map<String, String> ordered(Map<String, String> values, String name) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, name)));
    }
}
