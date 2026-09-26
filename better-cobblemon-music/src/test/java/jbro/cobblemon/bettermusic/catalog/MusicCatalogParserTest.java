package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

final class MusicCatalogParserTest {
    @Test
    void parsesNamespacedTracksPlaylistsMappingsAndAudioEvents() {
        MusicCatalog catalog = MusicCatalogParser.parse(new StringReader(baseCatalogJson()));

        assertEquals("cobleserver:official", catalog.packId());
        assertEquals(MusicCatalog.Kind.BASE, catalog.kind());
        assertEquals(
            "cobleserver:music.track.plains",
            catalog.tracks().get("cobleserver:plains").eventId()
        );
        assertEquals(
            java.util.List.of("cobleserver:plains"),
            catalog.playlists().get("cobleserver:field_plains").tracks()
        );
        assertEquals(
            "cobleserver:field_plains",
            catalog.mappings().orElseThrow().field().defaultPlaylistId()
        );
        assertEquals(
            "cobleserver:battle.hit.normal",
            catalog.audioEvents().orElseThrow().hitNormal()
        );
        assertEquals(
            "cobleserver:battle.low_hp.alert",
            catalog.audioEvents().orElseThrow().lowHpAlert()
        );
    }

    @Test
    void extensionCannotDeclareDefaultMappings() {
        String json = baseCatalogJson()
            .replace("\"kind\": \"base\"", "\"kind\": \"extension\"");

        CatalogValidationException exception = assertThrows(
            CatalogValidationException.class,
            () -> MusicCatalogParser.parse(new StringReader(json))
        );

        assertTrue(exception.getMessage().contains("$.mappings"));
    }

    @Test
    void acceptsLegacyHeartbeatKeyAsTheLowHpAlertEvent() {
        String json = baseCatalogJson().replace(
            "\"lowHpAlert\": \"cobleserver:battle.low_hp.alert\"",
            "\"heartbeat\": \"minecraft:entity.warden.heartbeat\""
        );

        MusicCatalog catalog = MusicCatalogParser.parse(new StringReader(json));

        assertEquals("minecraft:entity.warden.heartbeat", catalog.audioEvents().orElseThrow().lowHpAlert());
    }

    @Test
    void rejectsPlaylistWithNonNamespacedTrackId() {
        String json = baseCatalogJson().replace(
            "\"cobleserver:plains\"\n      ]",
            "\"plains\"\n      ]"
        );

        CatalogValidationException exception = assertThrows(
            CatalogValidationException.class,
            () -> MusicCatalogParser.parse(new StringReader(json))
        );

        assertTrue(exception.getMessage().contains("$.playlists.cobleserver:field_plains.tracks[0]"));
    }

    static String baseCatalogJson() {
        return """
            {
              "schemaVersion": 1,
              "packId": "cobleserver:official",
              "kind": "base",
              "tracks": {
                "cobleserver:plains": {
                  "event": "cobleserver:music.track.plains",
                  "title": "Plains"
                },
                "cobleserver:wild": {
                  "event": "cobleserver:music.track.wild",
                  "title": "Wild"
                },
                "cobleserver:trainer": {
                  "event": "cobleserver:music.track.trainer",
                  "title": "Trainer"
                },
                "cobleserver:pvp": {
                  "event": "cobleserver:music.track.pvp",
                  "title": "PvP"
                }
              },
              "playlists": {
                "cobleserver:field_plains": {
                  "tracks": [
                    "cobleserver:plains"
                  ]
                },
                "cobleserver:battle_wild": {"tracks": ["cobleserver:wild"]},
                "cobleserver:battle_trainer": {"tracks": ["cobleserver:trainer"]},
                "cobleserver:battle_pvp": {"tracks": ["cobleserver:pvp"]}
              },
              "mappings": {
                "field": {
                  "default": "cobleserver:field_plains",
                  "dimensions": {},
                  "biomes": {},
                  "biomePathContains": {}
                },
                "battle": {
                  "wild": "cobleserver:battle_wild",
                  "trainer": "cobleserver:battle_trainer",
                  "pvp": "cobleserver:battle_pvp",
                  "content": {},
                  "pokemon": []
                }
              },
              "audioEvents": {
                "hitNormal": "cobleserver:battle.hit.normal",
                "hitSuperEffective": "cobleserver:battle.hit.super_effective",
                "hitNotVeryEffective": "cobleserver:battle.hit.not_very_effective",
                "lowHpAlert": "cobleserver:battle.low_hp.alert"
              }
            }
            """;
    }
}
