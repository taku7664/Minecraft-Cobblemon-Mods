package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

class MusicMappingOverridesParserTest {
    @Test
    void discardsRenderedPackDefaultsButKeepsRealOverrides() {
        MusicMappingOverrides parsed = MusicMappingOverridesParser.parse(new StringReader("""
            {
              "schemaVersion": 1,
              "field": {
                "default": "리소스팩 기본값 (cobleserver:track/field/plains/theme)",
                "dimensions": {
                  "minecraft:the_nether": "Use resource pack default (cobleserver:track/field/nether/theme)"
                }
              },
              "battle": {
                "content": {
                  "cobblemon_more_battle_content:battle_tower": "cobleserver:track/battle/pvp/theme"
                },
                "pokemon": [
                  {
                    "species": ["mewtwo"],
                    "only": ["wild"],
                    "playlist": "리소스팩 기본값 (cobleserver:track/battle/legendary/mewtwo)"
                  }
                ]
              }
            }
            """));

        assertFalse(parsed.field().defaultPlaylistId().isPresent());
        assertFalse(parsed.field().dimensions().containsKey("minecraft:the_nether"));
        assertEquals(
            "cobleserver:track/battle/pvp/theme",
            parsed.battle().content().get("cobblemon_more_battle_content:battle_tower")
        );
        assertEquals(0, parsed.battle().pokemon().size());
    }

    @Test
    void stillRejectsUnknownMalformedOverrideValues() {
        assertThrows(CatalogValidationException.class, () -> MusicMappingOverridesParser.parse(new StringReader("""
            {
              "schemaVersion": 1,
              "field": {
                "default": "not a playlist id"
              }
            }
            """)));
    }
}
