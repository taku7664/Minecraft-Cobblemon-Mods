package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MusicCatalogCompilerTest {
    @Test
    void compilesBasePlusExtensionAndAppliesExplicitOverride() {
        MusicCatalog base = parse(MusicCatalogParserTest.baseCatalogJson());
        MusicCatalog extension = parse(extensionJson(
            "username:one",
            "username:custom",
            "username:music.track.custom",
            "username:custom_playlist"
        ));
        MusicMappingOverrides overrides = MusicMappingOverridesParser.parse(new StringReader("""
            {
              "schemaVersion": 1,
              "field": {
                "biomes": {
                  "minecraft:plains": "username:custom_playlist"
                }
              }
            }
            """));

        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(base, extension),
            MusicCatalogSettings.defaults("cobleserver:official"),
            overrides
        );

        assertEquals(
            List.of("username:custom"),
            compiled.snapshot().field().biomes().get("minecraft:plains").tracks()
        );
        assertEquals(
            "username:music.track.custom",
            compiled.trackEvents().get("username:custom")
        );
        assertTrue(compiled.activeExtensionPackIds().contains("username:one"));
    }

    @Test
    void extensionCannotOverrideBaseTrackId() {
        MusicCatalog base = parse(MusicCatalogParserTest.baseCatalogJson());
        MusicCatalog extension = parse(extensionJson(
            "username:one",
            "cobleserver:plains",
            "username:music.track.replacement",
            "username:custom_playlist"
        ));

        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(base, extension),
            MusicCatalogSettings.defaults("cobleserver:official"),
            MusicMappingOverrides.empty()
        );

        assertEquals(
            "cobleserver:music.track.plains",
            compiled.trackEvents().get("cobleserver:plains")
        );
        assertEquals(
            List.of("cobleserver:plains"),
            compiled.playlists().get("username:custom_playlist").tracks()
        );
        assertTrue(compiled.diagnostics().stream().anyMatch(message -> message.contains("cobleserver:plains")));
    }

    @Test
    void extensionWithOnlyRejectedOverridesIsNotReportedAsActive() {
        MusicCatalog base = parse(MusicCatalogParserTest.baseCatalogJson());
        MusicCatalog extension = parse(extensionJson(
            "username:rejected",
            "cobleserver:plains",
            "username:music.track.replacement",
            "cobleserver:field_plains"
        ));

        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(base, extension),
            MusicCatalogSettings.defaults("cobleserver:official"),
            MusicMappingOverrides.empty()
        );

        assertFalse(compiled.activeExtensionPackIds().contains("username:rejected"));
    }

    @Test
    void duplicateExtensionTrackIdsDisableBothDeclarationsDeterministically() {
        MusicCatalog base = parse(MusicCatalogParserTest.baseCatalogJson());
        MusicCatalog first = parse(extensionJson(
            "username:first",
            "shared:track",
            "username:music.track.first",
            "username:first_playlist"
        ));
        MusicCatalog second = parse(extensionJson(
            "username:second",
            "shared:track",
            "username:music.track.second",
            "username:second_playlist"
        ));

        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(base, second, first),
            MusicCatalogSettings.defaults("cobleserver:official"),
            MusicMappingOverrides.empty()
        );

        assertFalse(compiled.trackEvents().containsKey("shared:track"));
        assertFalse(compiled.playlists().containsKey("username:first_playlist"));
        assertFalse(compiled.playlists().containsKey("username:second_playlist"));
        assertTrue(compiled.diagnostics().stream().anyMatch(message -> message.contains("shared:track")));
    }

    @Test
    void invalidOverrideFallsBackToBaseMappingWithoutDeletingUserIntent() {
        MusicCatalog base = parse(MusicCatalogParserTest.baseCatalogJson());
        MusicMappingOverrides overrides = MusicMappingOverridesParser.parse(new StringReader("""
            {
              "schemaVersion": 1,
              "battle": {
                "content": {
                  "cobblemon_more_battle_content:battle_tower": "missing:playlist"
                }
              }
            }
            """));

        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(base),
            MusicCatalogSettings.defaults("cobleserver:official"),
            overrides
        );

        assertFalse(compiled.snapshot().battle().content().containsKey(
            "cobblemon_more_battle_content:battle_tower"
        ));
        assertEquals(
            "missing:playlist",
            compiled.inactiveOverrides().get("battle.content.cobblemon_more_battle_content:battle_tower")
        );
    }

    @Test
    void invalidOptionalOverrideFallsBackToAbsentBaseValue() {
        MusicCatalog base = parse(MusicCatalogParserTest.baseCatalogJson());
        MusicMappingOverrides overrides = MusicMappingOverridesParser.parse(new StringReader("""
            {
              "schemaVersion": 1,
              "field": {
                "underground": "missing:playlist"
              }
            }
            """));

        CompiledMusicConfiguration compiled = MusicCatalogCompiler.compile(
            "cobleserver:official",
            List.of(base),
            MusicCatalogSettings.defaults("cobleserver:official"),
            overrides
        );

        assertTrue(compiled.snapshot().field().underground().isEmpty());
        assertEquals("missing:playlist", compiled.inactiveOverrides().get("field.underground"));
    }

    private static MusicCatalog parse(String json) {
        return MusicCatalogParser.parse(new StringReader(json));
    }

    private static String extensionJson(
        String packId,
        String trackId,
        String eventId,
        String playlistId
    ) {
        return """
            {
              "schemaVersion": 1,
              "packId": "%s",
              "kind": "extension",
              "tracks": {
                "%s": {"event": "%s", "title": "Custom"}
              },
              "playlists": {
                "%s": {"tracks": ["%s"]}
              }
            }
            """.formatted(packId, trackId, eventId, playlistId, trackId);
    }
}
