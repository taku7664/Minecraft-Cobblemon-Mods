package jbro.cobblemon.popupemotes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

final class PlayerPopupEmotesModuleContractTest {
    @Test
    void metadataDefinesAnIndependentFabricClientAndServerMod() throws Exception {
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
            .filter(candidate -> "player_popup_emotes".equals(candidate.get("id").getAsString()))
            .findFirst()
            .orElse(null);

        assertNotNull(metadata, "the module fabric.mod.json must exist");
        assertEquals("Player Popup Emotes", metadata.get("name").getAsString());
        assertEquals("*", metadata.get("environment").getAsString());
        assertEquals(
            "jbro.cobblemon.popupemotes.PlayerPopupEmotes",
            metadata.getAsJsonObject("entrypoints").getAsJsonArray("main").get(0).getAsString()
        );
        assertEquals(
            "jbro.cobblemon.popupemotes.client.PlayerPopupEmotesClient",
            metadata.getAsJsonObject("entrypoints").getAsJsonArray("client").get(0).getAsString()
        );
        assertEquals(
            "jbro.cobblemon.popupemotes.client.PlayerPopupEmotesModMenu",
            metadata.getAsJsonObject("entrypoints").getAsJsonArray("modmenu").get(0).getAsString()
        );
        assertEquals("*", metadata.getAsJsonObject("suggests").get("modmenu").getAsString());

        var serialized = metadata.toString();
        assertFalse(serialized.contains("pop_up_emotes"));
        assertFalse(serialized.contains("neoforge"));

        assertNotNull(Class.forName("jbro.cobblemon.popupemotes.PlayerPopupEmotes"));
        assertNotNull(Class.forName("jbro.cobblemon.popupemotes.client.PlayerPopupEmotesClient"));
    }
}
