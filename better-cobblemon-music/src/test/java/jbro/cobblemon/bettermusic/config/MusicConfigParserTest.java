package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Set;
import jbro.cobblemon.bettermusic.field.FieldMusicContext;
import jbro.cobblemon.bettermusic.field.FieldPlaylistResolver;
import org.junit.jupiter.api.Test;

final class MusicConfigParserTest {
    @Test
    void parsesOrderedSchemaTwoBiomeRulesWithMultiTrackPlaylists() {
        var config = MusicConfigParser.parse(new StringReader("""
            {
              "schemaVersion": 2,
              "scanIntervalSeconds": 1.0,
              "fieldChangeDelaySeconds": 4.0,
              "betweenTracksSeconds": 0.0,
              "fadeInSeconds": 1.0,
              "fadeOutSeconds": 1.0,
              "selection": "shuffle",
              "volume": 1.0,
              "field": {
                "default": "field/default.ogg",
                "dimensions": {},
                "biomes": {
                  "minecraft:plains": ["field/plains_1.ogg", "field/plains_2.ogg"]
                },
                "biomeTags": [
                  {
                    "tag": "minecraft:is_forest",
                    "playlist": {
                      "selection": "sequential",
                      "tracks": ["field/forest_1.ogg", "field/forest_2.ogg"]
                    }
                  },
                  {"tag": "minecraft:is_mountain", "playlist": "field/mountain.ogg"}
                ],
                "biomePathContains": [
                  {"contains": "frozen_river", "playlist": "field/frozen_river.ogg"},
                  {
                    "contains": "river",
                    "playlist": ["field/river_1.ogg", "field/river_2.ogg"]
                  }
                ]
              },
              "battle": {
                "wild": "battle/wild.ogg",
                "trainer": "battle/trainer.ogg",
                "pvp": "battle/pvp.ogg",
                "pokemon": []
              }
            }
            """));

        assertEquals(
            java.util.List.of("field/plains_1.ogg", "field/plains_2.ogg"),
            config.field().biomes().get("minecraft:plains").tracks()
        );
        assertEquals(
            java.util.List.of("#minecraft:is_forest", "#minecraft:is_mountain"),
            config.field().biomes().keySet().stream().filter(key -> key.startsWith("#")).toList()
        );
        assertEquals(
            PlaylistDefinition.Selection.SEQUENTIAL,
            config.field().biomes().get("#minecraft:is_forest").selection()
        );
        assertEquals(
            java.util.List.of("frozen_river", "river"),
            java.util.List.copyOf(config.field().biomePathContains().keySet())
        );
        assertEquals(2, config.field().biomePathContains().get("river").tracks().size());
        var fieldSelection = new FieldPlaylistResolver(config.field()).select(new FieldMusicContext(
            "minecraft:overworld",
            "minecraft:frozen_river",
            Set.of(),
            false
        ));
        assertEquals("field.path:frozen_river", fieldSelection.id());
        assertEquals(java.util.List.of("field/frozen_river.ogg"), fieldSelection.playlist().tracks());
    }

    @Test
    void schemaTwoRejectsLegacyOrderedObjectsAndTagsMixedIntoExactBiomes() {
        String schemaTwo = minimalConfig("\"field/plains.ogg\"")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
            .replace(
                "\"biomes\": {},",
                "\"biomes\": {\"#minecraft:is_forest\": \"field/forest.ogg\"}, \"biomeTags\": [],"
            );
        var tagException = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(schemaTwo.replace(
                "\"biomePathContains\": {}",
                "\"biomePathContains\": []"
            )))
        );
        assertTrue(tagException.getMessage().contains("$.field.biomes['#minecraft:is_forest']"));

        var objectException = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(schemaTwo.replace(
                "\"biomes\": {\"#minecraft:is_forest\": \"field/forest.ogg\"}",
                "\"biomes\": {}"
            )))
        );
        assertTrue(objectException.getMessage().contains("$.field.biomePathContains"));
        assertTrue(objectException.getMessage().contains("array"));
    }

    @Test
    void schemaTwoRejectsDuplicateOrderedSelectors() {
        String duplicateTags = minimalConfig("\"field/plains.ogg\"")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
            .replace(
                "\"biomes\": {},",
                "\"biomes\": {}, \"biomeTags\": ["
                    + "{\"tag\":\"minecraft:is_forest\",\"playlist\":\"field/forest_1.ogg\"},"
                    + "{\"tag\":\"minecraft:is_forest\",\"playlist\":\"field/forest_2.ogg\"}],"
            )
            .replace("\"biomePathContains\": {}", "\"biomePathContains\": []");

        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(duplicateTags))
        );

        assertTrue(exception.getMessage().contains("$.field.biomeTags[1].tag"));
        assertTrue(exception.getMessage().contains("duplicate ordered selector"));
    }

    @Test
    void parsesStringArrayAndAdvancedPlaylistFormsWithoutCueIndirection() {
        var config = MusicConfigParser.parse(new StringReader("""
            {
              "schemaVersion": 1,
              "scanIntervalSeconds": 1.0,
              "fieldChangeDelaySeconds": 4.0,
              "betweenTracksSeconds": 2.0,
              "fadeInSeconds": 1.0,
              "fadeOutSeconds": 1.0,
              "selection": "shuffle",
              "volume": 0.9,
              "field": {
                "default": "field/plains.ogg",
                "dimensions": {
                  "minecraft:the_nether": ["field/nether_1.ogg", "field/nether_2.ogg"]
                },
                "biomes": {
                  "#minecraft:is_forest": {
                    "selection": "sequential",
                    "betweenTracksSeconds": 3.0,
                    "volume": 0.8,
                    "tracks": ["field/forest_1.ogg", "field/forest_2.ogg"]
                  }
                },
                "biomePathContains": {},
                "underground": "field/cave.ogg"
              },
              "battle": {
                "wild": "battle/wild.ogg",
                "trainer": ["battle/trainer_1.ogg", "battle/trainer_2.ogg"],
                "pvp": ["battle/pvp_1.ogg", "battle/pvp_2.ogg"],
                "content": {
                  "cobblemon_more_battle_content:battle_tower": [
                    "battle/tower_1.ogg",
                    "battle/tower_2.ogg"
                  ]
                },
                "roles": {
                  "gym": ["battle/gym_1.ogg", "battle/gym_2.ogg"],
                  "champion": "battle/champion.ogg"
                },
                "pokemon": [
                  {
                    "species": ["uxie", "mesprit", "azelf"],
                    "only": ["wild"],
                    "tracks": ["battle/lake_trio_1.ogg", "battle/lake_trio_2.ogg"]
                  }
                ]
              }
            }
            """));

        assertEquals(java.util.List.of("field/plains.ogg"), config.field().defaultPlaylist().tracks());
        assertEquals(2, config.field().dimensions().get("minecraft:the_nether").tracks().size());
        var forest = config.field().biomes().get("#minecraft:is_forest");
        assertEquals(PlaylistDefinition.Selection.SEQUENTIAL, forest.selection());
        assertEquals(3.0, forest.betweenTracksSeconds());
        assertEquals(0.8, forest.volume());
        assertEquals(PlaylistDefinition.Selection.SHUFFLE, config.battle().wild().selection());
        assertEquals(0.9, config.battle().wild().volume());
        assertEquals(
            java.util.List.of("battle/tower_1.ogg", "battle/tower_2.ogg"),
            config.battle().content().get("cobblemon_more_battle_content:battle_tower").tracks()
        );
        assertEquals(2, config.battle().roles().get("gym").tracks().size());
        assertEquals(
            java.util.List.of("battle/champion.ogg"),
            config.battle().roles().get("champion").tracks()
        );
        assertEquals(
            java.util.Set.of("cobblemon:uxie", "cobblemon:mesprit", "cobblemon:azelf"),
            config.battle().pokemon().getFirst().species()
        );
        assertEquals(
            java.util.Set.of(BattleMusicConfig.BattleType.WILD),
            config.battle().pokemon().getFirst().only()
        );
    }

    @Test
    void acceptsFormQualifiedPokemonRules() {
        var config = MusicConfigParser.parse(new StringReader(
            minimalConfig("\"field/plains.ogg\"").replace(
                "\"pokemon\": []",
                "\"pokemon\": [{\"species\":[\"articuno#galarian\","
                    + "\"cobblemon:necrozma#dusk-mane\"],"
                    + "\"tracks\":[\"battle/forms.ogg\"]}]"
            )
        ));

        assertEquals(
            Set.of("cobblemon:articuno#galarian", "cobblemon:necrozma#dusk-mane"),
            config.battle().pokemon().getFirst().species()
        );
    }

    @Test
    void contentPlaylistKeysMustBeNamespacedIds() {
        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(
                minimalConfig("\"field/plains.ogg\"").replace(
                    "\"pokemon\": []",
                    "\"content\": {\"battle_tower\": \"battle/tower.ogg\"}, \"pokemon\": []"
                )
            ))
        );

        assertTrue(exception.getMessage().contains("$.battle.content['battle_tower']"));
        assertTrue(exception.getMessage().contains("resource id"));
    }

    @Test
    void rejectsPathsThatEscapeTheMusicDirectoryWithAnExactJsonPath() {
        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(minimalConfig("\"../outside.ogg\"")))
        );

        assertTrue(exception.getMessage().contains("$.field.default"));
        assertTrue(exception.getMessage().contains("relative .ogg path"));
    }

    @Test
    void rejectsDuplicateTracksInsteadOfUsingAccidentalDuplicationAsWeight() {
        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(minimalConfig(
                "[\"field/plains.ogg\", \"field/plains.ogg\"]"
            )))
        );

        assertTrue(exception.getMessage().contains("duplicate track"));
    }

    @Test
    void acceptsLegacyGymAsARoleButRejectsAnAmbiguousDuplicate() {
        var legacy = MusicConfigParser.parse(new StringReader(
            minimalConfig("\"field/plains.ogg\"").replace(
                "\"pokemon\": []",
                "\"gym\": \"battle/gym.ogg\", \"pokemon\": []"
            )
        ));
        assertEquals(java.util.List.of("battle/gym.ogg"), legacy.battle().roles().get("gym").tracks());

        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(
                minimalConfig("\"field/plains.ogg\"").replace(
                    "\"pokemon\": []",
                    "\"gym\": \"battle/legacy.ogg\", "
                        + "\"roles\": {\"gym\": \"battle/new.ogg\"}, \"pokemon\": []"
                )
            ))
        );
        assertTrue(exception.getMessage().contains("$.battle.roles.gym"));
    }

    @Test
    void rejectsTrackPathsThatCannotBecomeMinecraftResourceIds() {
        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(minimalConfig("\"field/My Song.ogg\"")))
        );

        assertTrue(exception.getMessage().contains("$.field.default"));
        assertTrue(exception.getMessage().contains("lowercase Minecraft resource path"));
    }

    @Test
    void rejectsPollingIntervalsBelowAQuarterSecond() {
        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(
                minimalConfig("\"field/plains.ogg\"").replace(
                    "\"scanIntervalSeconds\": 1.0",
                    "\"scanIntervalSeconds\": 0.01"
                )
            ))
        );

        assertTrue(exception.getMessage().contains("$.scanIntervalSeconds"));
        assertTrue(exception.getMessage().contains("at least 0.25"));
    }

    @Test
    void rejectsTrainerRoleKeysThatNoRuntimeAdapterCanProduce() {
        var exception = assertThrows(ConfigValidationException.class, () ->
            MusicConfigParser.parse(new StringReader(
                minimalConfig("\"field/plains.ogg\"").replace(
                    "\"pokemon\": []",
                    "\"roles\": {\"boss\": \"battle/boss.ogg\"}, \"pokemon\": []"
                )
            ))
        );

        assertTrue(exception.getMessage().contains("$.battle.roles['boss']"));
        assertTrue(exception.getMessage().contains("champion, elite, gym, or rival"));
    }

    private static String minimalConfig(String defaultPlaylistJson) {
        return """
            {
              "schemaVersion": 1,
              "scanIntervalSeconds": 1.0,
              "fieldChangeDelaySeconds": 4.0,
              "betweenTracksSeconds": 0.0,
              "fadeInSeconds": 1.0,
              "fadeOutSeconds": 1.0,
              "selection": "shuffle",
              "volume": 1.0,
              "field": {
                "default": %s,
                "dimensions": {},
                "biomes": {},
                "biomePathContains": {}
              },
              "battle": {
                "wild": "battle/wild.ogg",
                "trainer": "battle/trainer.ogg",
                "pvp": "battle/pvp.ogg",
                "pokemon": []
              }
            }
            """.formatted(defaultPlaylistJson);
    }
}
