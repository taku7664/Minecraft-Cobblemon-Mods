package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.field.FieldMusicContext;
import jbro.cobblemon.bettermusic.field.FieldPlaylistResolver;
import org.junit.jupiter.api.Test;

final class FieldDayNightMappingTest {
    @Test
    void gameClockHasExplicitWeatherIndependentBoundariesAndWraps() {
        for (long tick : new long[]{0, 12999, 23000, 23999, 24000, -1, Long.MAX_VALUE}) {
            assertEquals(FieldMusicContext.TimeOfDay.DAY, FieldMusicContext.TimeOfDay.fromWorldTime(tick), "tick " + tick);
        }
        for (long tick : new long[]{13000, 22999, 37000, -10001}) {
            assertEquals(FieldMusicContext.TimeOfDay.NIGHT, FieldMusicContext.TimeOfDay.fromWorldTime(tick), "tick " + tick);
        }
    }

    @Test
    void nightMappingWinsAndMissingDayFallsBackToUntimedDimension() {
        var catalog = timedCatalog();
        var compiled = compile(catalog, MusicMappingOverrides.empty());
        var resolver = new FieldPlaylistResolver(compiled.snapshot().field());
        assertEquals(List.of("cobleserver:trainer"), resolver.select(plaza(FieldMusicContext.TimeOfDay.NIGHT)).playlist().tracks());
        assertEquals("field.dimension.night:jbro_policy:plaza", resolver.select(plaza(FieldMusicContext.TimeOfDay.NIGHT)).id());
        assertEquals(List.of("cobleserver:wild"), resolver.select(plaza(FieldMusicContext.TimeOfDay.DAY)).playlist().tracks());
        assertEquals("field.dimension:jbro_policy:plaza", resolver.select(plaza(FieldMusicContext.TimeOfDay.DAY)).id());
    }

    @Test
    void oldCatalogsHaveNoTimedMappingsAndWorkAtBothTimes() {
        var catalog = MusicCatalogParser.parse(new StringReader(MusicCatalogParserTest.baseCatalogJson()));
        var compiled = compile(catalog, MusicMappingOverrides.empty());
        assertTrue(compiled.snapshot().field().dayDimensions().isEmpty());
        assertTrue(compiled.snapshot().field().nightDimensions().isEmpty());
        var resolver = new FieldPlaylistResolver(compiled.snapshot().field());
        assertEquals(resolver.select(plaza(FieldMusicContext.TimeOfDay.DAY)), resolver.select(plaza(FieldMusicContext.TimeOfDay.NIGHT)));
    }

    @Test
    void validPersonalTimedOverridesWinAndMissingReferencesRemainInactive() {
        var overrides = MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion":1,"field":{
              "dayDimensions":{"jbro_policy:plaza":"cobleserver:battle_pvp"},
              "nightDimensions":{"jbro_policy:plaza":"example:removed"}
            }}
            """));
        var compiled = compile(timedCatalog(), overrides);
        var resolver = new FieldPlaylistResolver(compiled.snapshot().field());
        assertEquals(List.of("cobleserver:pvp"), resolver.select(plaza(FieldMusicContext.TimeOfDay.DAY)).playlist().tracks());
        assertEquals(List.of("cobleserver:trainer"), resolver.select(plaza(FieldMusicContext.TimeOfDay.NIGHT)).playlist().tracks());
        assertEquals(1, compiled.inactiveOverrides().size());
    }

    @Test
    void unavailableTimedBaseReferenceRejectsTheCatalogRatherThanMaterializingNull() {
        var root = JsonParser.parseString(MusicCatalogParserTest.baseCatalogJson()).getAsJsonObject();
        var night = new com.google.gson.JsonObject();
        night.addProperty("jbro_policy:plaza", "example:missing");
        root.getAsJsonObject("mappings").getAsJsonObject("field").add("nightDimensions", night);
        var catalog = MusicCatalogParser.parse(new StringReader(root.toString()));
        assertThrows(CatalogValidationException.class, () -> compile(catalog, MusicMappingOverrides.empty()));
    }

    @Test
    void timedOverrideMapsRoundTripAndPackDefaultLabelsAreDiscarded(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var store = new MusicCatalogConfigStore(directory);
        var overrides = MusicMappingOverrides.empty().withField(new MusicMappingOverrides.Field(Optional.empty(),
            Map.of(), Map.of(), Map.of(), Optional.empty(),
            Map.of("jbro_policy:plaza", "cobleserver:battle_wild"),
            Map.of("jbro_policy:plaza", "cobleserver:battle_trainer")));
        store.saveOverrides(overrides);
        assertEquals(overrides, store.loadOverrides());
        var parsed = MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion":1,"field":{
              "dayDimensions":{"jbro_policy:plaza":"Resource-pack default (cobleserver:battle_wild)"},
              "nightDimensions":{"jbro_policy:plaza":"리소스팩 기본값 (cobleserver:battle_trainer)"}
            }}
            """));
        assertTrue(parsed.field().dayDimensions().isEmpty());
        assertTrue(parsed.field().nightDimensions().isEmpty());
    }

    @Test
    void dayDimensionMappingOverridesTheUntimedFieldMapping() {
        var root = JsonParser.parseString(MusicCatalogParserTest.baseCatalogJson()).getAsJsonObject();
        var field = root.getAsJsonObject("mappings").getAsJsonObject("field");
        var day = new com.google.gson.JsonObject();
        day.addProperty("jbro_policy:plaza", "cobleserver:battle_wild");
        field.add("dayDimensions", day);
        var catalog = MusicCatalogParser.parse(new StringReader(root.toString()));
        var compiled = MusicCatalogCompiler.compile("cobleserver:official", List.of(catalog),
            MusicCatalogSettings.defaults("cobleserver:official"), MusicMappingOverrides.empty());
        var selection = new FieldPlaylistResolver(compiled.snapshot().field()).select(
            new FieldMusicContext("jbro_policy:plaza", "minecraft:plains", Set.of(), false));
        assertEquals(List.of("cobleserver:wild"), selection.playlist().tracks());
    }

    private static MusicCatalog timedCatalog() {
        var root = JsonParser.parseString(MusicCatalogParserTest.baseCatalogJson()).getAsJsonObject();
        var field = root.getAsJsonObject("mappings").getAsJsonObject("field");
        field.getAsJsonObject("dimensions").addProperty("jbro_policy:plaza", "cobleserver:battle_wild");
        var night = new com.google.gson.JsonObject();
        night.addProperty("jbro_policy:plaza", "cobleserver:battle_trainer");
        field.add("nightDimensions", night);
        return MusicCatalogParser.parse(new StringReader(root.toString()));
    }

    private static CompiledMusicConfiguration compile(MusicCatalog catalog, MusicMappingOverrides overrides) {
        return MusicCatalogCompiler.compile("cobleserver:official", List.of(catalog),
            MusicCatalogSettings.defaults("cobleserver:official"), overrides);
    }

    private static FieldMusicContext plaza(FieldMusicContext.TimeOfDay time) {
        return new FieldMusicContext("jbro_policy:plaza", "minecraft:plains", Set.of(), false, time);
    }
}
