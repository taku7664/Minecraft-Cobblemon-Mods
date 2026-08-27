package jbro.cobblemon.betterbattlepresentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

final class BetterBattlePresentationModuleContractTest {
    @Test
    void metadataDefinesAnIndependentMegaShowdownPresentationMod() throws Exception {
        var resources = getClass().getClassLoader().getResources("fabric.mod.json");
        JsonObject metadata = Collections.list(resources).stream()
            .map(url -> {
                try (var reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not read " + url, exception);
                }
            })
            .filter(candidate -> candidate.has("id"))
            .filter(candidate -> "cobblemon_better_battle_presentation".equals(candidate.get("id").getAsString()))
            .findFirst()
            .orElse(null);
        assertNotNull(metadata, "the module fabric.mod.json must exist");

        assertEquals("cobblemon_better_battle_presentation", metadata.get("id").getAsString());
        assertEquals("Cobblemon: Better Battle Presentation", metadata.get("name").getAsString());
        assertEquals("*", metadata.get("environment").getAsString());

        var dependencies = metadata.getAsJsonObject("depends");
        assertEquals(">=1.9.3+1.7.3+1.21.1", dependencies.get("mega_showdown").getAsString());
        assertFalse(dependencies.has("cobblemon_more_battle_content"));
        assertFalse(dependencies.has("cobblemon_more_battle_content_better_ai"));

        var serialized = metadata.toString();
        assertTrue(serialized.contains("BetterBattlePresentation"));
        assertTrue(serialized.contains("BetterBattlePresentationClient"));
        assertFalse(serialized.contains("\"adapter\":\"kotlin\""));
    }
}
