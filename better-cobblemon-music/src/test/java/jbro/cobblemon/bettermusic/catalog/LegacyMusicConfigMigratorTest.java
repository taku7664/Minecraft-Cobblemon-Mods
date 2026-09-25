package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.StringReader;
import java.util.List;
import jbro.cobblemon.bettermusic.config.MusicConfigParser;
import org.junit.jupiter.api.Test;

final class LegacyMusicConfigMigratorTest {
    @Test
    void preservesOnlyUserMappingDifferencesUsingLegacyTrackAliases() {
        MusicCatalog catalog = MusicCatalogParser.parse(new StringReader(catalogJson()));
        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(catalog),
            MusicCatalogSettings.defaults("cobleserver:official"),
            MusicMappingOverrides.empty()
        );
        var legacyDefault = MusicConfigParser.parse(new StringReader(legacyJson("battle/trainer.ogg")));
        var legacyUser = MusicConfigParser.parse(new StringReader(legacyJson("battle/content/gym.ogg")));

        LegacyMusicConfigMigrator.MigrationResult result = LegacyMusicConfigMigrator.migrate(
            legacyUser,
            legacyDefault,
            List.of(catalog),
            compiled
        );

        assertEquals(
            "cobleserver:gym",
            result.overrides().battle().content().get("cobblemon_more_battle_content:battle_tower")
        );
        assertEquals(1, result.overrides().battle().content().size());
        assertFalse(result.overrides().field().biomes().containsKey("minecraft:plains"));
    }

    private static String catalogJson() {
        return """
            {
              "schemaVersion": 1,
              "packId": "cobleserver:official",
              "kind": "base",
              "tracks": {
                "cobleserver:plains": {
                  "event": "cobleserver:music.track.plains",
                  "title": "Plains",
                  "legacyPaths": ["field/plains.ogg", "field/cave.ogg"]
                },
                "cobleserver:wild": {
                  "event": "cobleserver:music.track.wild",
                  "title": "Wild",
                  "legacyPaths": ["battle/wild.ogg", "battle/legendary.ogg", "battle/ultra_beast.ogg"]
                },
                "cobleserver:trainer": {
                  "event": "cobleserver:music.track.trainer",
                  "title": "Trainer",
                  "legacyPaths": ["battle/trainer.ogg"]
                },
                "cobleserver:pvp": {
                  "event": "cobleserver:music.track.pvp",
                  "title": "PvP",
                  "legacyPaths": ["battle/pvp.ogg"]
                },
                "cobleserver:gym_track": {
                  "event": "cobleserver:music.track.gym",
                  "title": "Gym",
                  "legacyPaths": ["battle/pvp/gym.ogg", "battle/content/gym.ogg"]
                }
              },
              "playlists": {
                "cobleserver:plains": {"tracks": ["cobleserver:plains"]},
                "cobleserver:wild": {"tracks": ["cobleserver:wild"]},
                "cobleserver:trainer": {"tracks": ["cobleserver:trainer"]},
                "cobleserver:pvp": {"tracks": ["cobleserver:pvp"]},
                "cobleserver:gym": {"tracks": ["cobleserver:gym_track"]}
              },
              "mappings": {
                "field": {
                  "default": "cobleserver:plains",
                  "dimensions": {},
                  "biomes": {"minecraft:plains": "cobleserver:plains"},
                  "biomePathContains": {},
                  "underground": "cobleserver:plains"
                },
                "battle": {
                  "wild": "cobleserver:wild",
                  "trainer": "cobleserver:trainer",
                  "pvp": "cobleserver:pvp",
                  "content": {
                    "cobblemon_more_battle_content:battle_tower": "cobleserver:trainer"
                  },
                  "legendary": "cobleserver:wild",
                  "ultraBeast": "cobleserver:wild",
                  "pokemon": []
                }
              },
              "audioEvents": {
                "hitNormal": "cobleserver:battle.hit.normal",
                "hitSuperEffective": "cobleserver:battle.hit.super_effective",
                "hitNotVeryEffective": "cobleserver:battle.hit.not_very_effective",
                "heartbeat": "minecraft:entity.warden.heartbeat"
              }
            }
            """;
    }

    private static String legacyJson(String towerTrack) {
        return MusicCatalogConfigStoreTest.legacyJson()
            .replace("\"biomes\": {}", "\"biomes\": {\"minecraft:plains\": \"field/plains.ogg\"}")
            .replace("\"content\": {}", "\"content\": {\"cobblemon_more_battle_content:battle_tower\": \"" + towerTrack + "\"}");
    }
}
