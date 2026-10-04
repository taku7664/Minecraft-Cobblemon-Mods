package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CatalogSpeciesNormalizationTest {
    @Test
    void bareNamesAndFormsMatchTheRuntimeCobblemonNamespace() {
        var rules = MusicCatalogParser.pokemon(JsonParser.parseString("""
            [{"species": ["lugia", "giratina#origin", "custom:lugia#shadow"],
              "only": ["wild"], "playlist": "cobleserver:theme"}]
            """).getAsJsonArray(), "$.mappings.battle.pokemon");
        assertEquals(Set.of("cobblemon:lugia", "cobblemon:giratina#origin", "custom:lugia#shadow"),
            rules.getFirst().species());
    }

    @Test
    void userOverridesUseTheSameSpeciesNormalization() {
        var overrides = MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion": 1, "battle": {"pokemon": [
              {"species": ["lugia"], "playlist": "cobleserver:theme"}
            ]}}
            """));
        assertEquals(Set.of("cobblemon:lugia"), overrides.battle().pokemon().getFirst().species());
    }

    @Test
    void equivalentNamesAreRejectedAsDuplicateSelectors() {
        assertThrows(CatalogValidationException.class, () -> MusicCatalogParser.pokemon(
            JsonParser.parseString("""
                [{"species": ["lugia", "cobblemon:lugia"], "playlist": "cobleserver:theme"}]
                """).getAsJsonArray(), "$.mappings.battle.pokemon"));
    }
}
