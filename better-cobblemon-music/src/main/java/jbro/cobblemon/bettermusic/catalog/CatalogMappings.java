package jbro.cobblemon.bettermusic.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.field.FieldMusicRule;

/** A base pack's mappings; {@code screens} maps screen keys, such as a content hub, to playlists. */
public record CatalogMappings(Field field, Battle battle, Map<String, String> screens) {
    public CatalogMappings {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
        screens = ordered(screens, "screens");
    }

    public CatalogMappings(Field field, Battle battle) {
        this(field, battle, Map.of());
    }

    public record Field(
        String defaultPlaylistId,
        Map<String, String> dimensions,
        Map<String, String> biomes,
        Map<String, String> biomePathContains,
        Optional<String> undergroundPlaylistId,
        Map<String, String> dayDimensions,
        Map<String, String> nightDimensions,
        List<FieldMusicRule> ruleOrder
    ) {
        public Field {
            Objects.requireNonNull(defaultPlaylistId, "defaultPlaylistId");
            dimensions = ordered(dimensions, "dimensions");
            biomes = ordered(biomes, "biomes");
            biomePathContains = ordered(biomePathContains, "biomePathContains");
            undergroundPlaylistId = Objects.requireNonNull(undergroundPlaylistId, "undergroundPlaylistId");
            dayDimensions = ordered(dayDimensions, "dayDimensions");
            nightDimensions = ordered(nightDimensions, "nightDimensions");
            ruleOrder = FieldMusicRule.validate(ruleOrder, biomes, biomePathContains, undergroundPlaylistId.isPresent());
        }

        public Field(String defaultPlaylistId, Map<String, String> dimensions, Map<String, String> biomes,
            Map<String, String> biomePathContains, Optional<String> undergroundPlaylistId,
            Map<String, String> dayDimensions, Map<String, String> nightDimensions) {
            this(defaultPlaylistId, dimensions, biomes, biomePathContains, undergroundPlaylistId,
                dayDimensions, nightDimensions, List.of());
        }

        public Field(String defaultPlaylistId, Map<String, String> dimensions, Map<String, String> biomes,
            Map<String, String> biomePathContains, Optional<String> undergroundPlaylistId) {
            this(defaultPlaylistId, dimensions, biomes, biomePathContains, undergroundPlaylistId, Map.of(), Map.of());
        }
    }

    public record Battle(
        String wildPlaylistId,
        String trainerPlaylistId,
        String pvpPlaylistId,
        Map<String, String> content,
        Optional<String> legendaryPlaylistId,
        Optional<String> ultraBeastPlaylistId,
        Optional<String> alphaPlaylistId,
        List<PokemonMapping> pokemon
    ) {
        public Battle {
            Objects.requireNonNull(wildPlaylistId, "wildPlaylistId");
            Objects.requireNonNull(trainerPlaylistId, "trainerPlaylistId");
            Objects.requireNonNull(pvpPlaylistId, "pvpPlaylistId");
            content = ordered(content, "content");
            legendaryPlaylistId = Objects.requireNonNull(legendaryPlaylistId, "legendaryPlaylistId");
            ultraBeastPlaylistId = Objects.requireNonNull(ultraBeastPlaylistId, "ultraBeastPlaylistId");
            alphaPlaylistId = Objects.requireNonNull(alphaPlaylistId, "alphaPlaylistId");
            pokemon = List.copyOf(Objects.requireNonNull(pokemon, "pokemon"));
        }

        public Battle(String wildPlaylistId, String trainerPlaylistId, String pvpPlaylistId,
            Map<String, String> content, Optional<String> legendaryPlaylistId,
            Optional<String> ultraBeastPlaylistId, List<PokemonMapping> pokemon) {
            this(wildPlaylistId, trainerPlaylistId, pvpPlaylistId, content, legendaryPlaylistId,
                ultraBeastPlaylistId, Optional.empty(), pokemon);
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
