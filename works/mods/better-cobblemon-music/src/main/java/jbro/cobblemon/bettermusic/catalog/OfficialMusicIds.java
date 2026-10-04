package jbro.cobblemon.bettermusic.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Current official pack IDs and read-only aliases for the former namespace. */
public final class OfficialMusicIds {
    public static final String NAMESPACE = "better_cobblemon_music";
    public static final String PACK_ID = NAMESPACE + ":official";
    public static final String OLD_PACK_ID = "cobleserver:official";
    private static final String OLD_NAMESPACE_PREFIX = "cobleserver:";

    private OfficialMusicIds() {
    }

    public static String current(String id) {
        return id.startsWith(OLD_NAMESPACE_PREFIX)
            ? NAMESPACE + ":" + id.substring(OLD_NAMESPACE_PREFIX.length()) : id;
    }

    public static MusicMappingOverrides current(MusicMappingOverrides overrides) {
        var field = overrides.field();
        var battle = overrides.battle();
        return new MusicMappingOverrides(
            new MusicMappingOverrides.Field(
                current(field.defaultPlaylistId()), currentValues(field.dimensions()),
                currentValues(field.biomes()), currentValues(field.biomePathContains()),
                current(field.undergroundPlaylistId()), currentValues(field.dayDimensions()),
                currentValues(field.nightDimensions())
            ),
            new MusicMappingOverrides.Battle(
                current(battle.wildPlaylistId()), current(battle.trainerPlaylistId()),
                current(battle.pvpPlaylistId()), currentValues(battle.content()),
                current(battle.legendaryPlaylistId()), current(battle.ultraBeastPlaylistId()),
                current(battle.alphaPlaylistId()), battle.pokemon().stream()
                    .map(rule -> new CatalogMappings.PokemonMapping(
                        rule.species(), rule.only(), current(rule.playlistId())))
                    .toList()
            ),
            currentValues(overrides.screens())
        );
    }

    private static Optional<String> current(Optional<String> id) {
        return id.map(OfficialMusicIds::current);
    }

    private static Map<String, String> currentValues(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, current(value)));
        return result;
    }
}
