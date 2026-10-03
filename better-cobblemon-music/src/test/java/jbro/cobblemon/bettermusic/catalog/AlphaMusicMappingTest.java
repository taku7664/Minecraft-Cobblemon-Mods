package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.battle.BattlePlaylistResolver;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AlphaMusicMappingTest {
    @TempDir
    Path directory;

    @Test
    void alphaIsWildOnlyAndFallsBackToOrdinaryWildIfThePackHasNoAlphaMapping() {
        var withAlpha = compile(alphaCatalog(), MusicMappingOverrides.empty());
        assertEquals("battle.alpha", select(withAlpha, BattleMusicConfig.BattleType.WILD, Set.of()).id());
        assertEquals("battle.trainer", select(withAlpha, BattleMusicConfig.BattleType.TRAINER, Set.of()).id());
        assertEquals("battle.pvp", select(withAlpha, BattleMusicConfig.BattleType.PVP, Set.of()).id());
        var oldPack = compile(MusicCatalogParserTest.baseCatalogJson(), MusicMappingOverrides.empty());
        assertEquals("battle.wild", select(oldPack, BattleMusicConfig.BattleType.WILD, Set.of()).id());
        assertEquals("battle.wild", select(withAlpha, BattleMusicConfig.BattleType.WILD, Set.of()).fallback().orElseThrow().id());
    }

    @Test
    void explicitSpeciesRuleWinsOverAlphaAndAlphaWinsOverGenericLegendaryLabel() {
        String catalog = alphaCatalog().replace("\"pokemon\": []", """
            "pokemon": [{"species": ["lugia"], "only": ["wild"], "playlist": "cobleserver:battle_trainer"}]
            """);
        var compiled = compile(catalog, MusicMappingOverrides.empty());
        assertEquals("battle.pokemon:0", select(compiled, BattleMusicConfig.BattleType.WILD, Set.of("cobblemon:lugia")).id());
        var allLabels = Set.of(BattleMusicContext.Label.valueOf("ALPHA"), BattleMusicContext.Label.LEGENDARY);
        assertEquals("battle.alpha", new BattlePlaylistResolver(compiled.snapshot().battle()).select(
            new BattleMusicContext(BattleMusicConfig.BattleType.WILD, Set.of("cobblemon:unknown"), allLabels)).id());
    }

    @Test
    void alphaOverrideSurvivesSaveAndReloadAndInvalidReferencesCannotSilenceMusic() throws Exception {
        var override = MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion": 1, "battle": {"alpha": "cobleserver:battle_trainer"}}
            """));
        var settings = MusicCatalogSettings.defaults("cobleserver:official");
        var store = new MusicCatalogConfigStore(directory);
        store.saveSettings(settings);
        store.saveOverrides(override);
        var restored = store.loadOverrides();
        assertEquals(override, restored);
        assertEquals(List.of("cobleserver:trainer"), select(compile(alphaCatalog(), restored),
            BattleMusicConfig.BattleType.WILD, Set.of()).playlist().tracks());
        var invalid = MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion": 1, "battle": {"alpha": "missing:playlist"}}
            """));
        assertTrue(compile(alphaCatalog(), invalid).inactiveOverrides().containsKey("battle.alpha"));
        assertThrows(CatalogValidationException.class, () -> compile(
            alphaCatalog().replace("\"alpha\": \"cobleserver:battle_pvp\"", "\"alpha\": \"missing:playlist\""),
            MusicMappingOverrides.empty()));
    }

    private static BattlePlaylistResolver.Selection select(CompiledMusicConfiguration compiled,
        BattleMusicConfig.BattleType type, Set<String> species) {
        return new BattlePlaylistResolver(compiled.snapshot().battle()).select(new BattleMusicContext(
            type, species, Set.of(BattleMusicContext.Label.valueOf("ALPHA"))));
    }

    private static String alphaCatalog() {
        return MusicCatalogParserTest.baseCatalogJson().replace("\"wild\": \"cobleserver:battle_wild\"",
            "\"alpha\": \"cobleserver:battle_pvp\", \"wild\": \"cobleserver:battle_wild\"");
    }

    private static CompiledMusicConfiguration compile(String json, MusicMappingOverrides overrides) {
        return MusicCatalogCompiler.compile("cobleserver:official",
            List.of(MusicCatalogParser.parse(new StringReader(json))),
            MusicCatalogSettings.defaults("cobleserver:official"), overrides);
    }
}
