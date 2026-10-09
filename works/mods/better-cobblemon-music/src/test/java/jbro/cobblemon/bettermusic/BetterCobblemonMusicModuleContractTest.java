package jbro.cobblemon.bettermusic;

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

final class BetterCobblemonMusicModuleContractTest {
    @Test
    void metadataDefinesAnIndependentClientMusicModWithTheNewIdentity() throws Exception {
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
            .filter(candidate -> "better_cobblemon_music".equals(candidate.get("id").getAsString()))
            .findFirst()
            .orElse(null);

        assertNotNull(metadata, "the module fabric.mod.json must exist");
        String description = metadata.get("description").getAsString();
        assertTrue(description.contains("music for Cobblemon"));
        assertTrue(description.contains("resource pack"));
        assertEquals("Better Cobblemon Music", metadata.get("name").getAsString());
        assertEquals("client", metadata.get("environment").getAsString());
        assertEquals(
            "jbro.cobblemon.bettermusic.BetterCobblemonMusicClient",
            metadata.getAsJsonObject("entrypoints").getAsJsonArray("client").get(0).getAsString()
        );
        assertEquals(
            "jbro.cobblemon.bettermusic.client.BetterMusicModMenu",
            metadata.getAsJsonObject("entrypoints").getAsJsonArray("modmenu").get(0).getAsString()
        );
        assertEquals(
            "better_cobblemon_music.mixins.json",
            metadata.getAsJsonArray("mixins").get(0).getAsString()
        );

        var dependencies = metadata.getAsJsonObject("depends");
        assertEquals(">=1.8.1 <1.9.0", dependencies.get("cobblemon").getAsString());
        assertFalse(dependencies.has("more_cobblemon_contents"));
        assertFalse(dependencies.has("cobblemon_better_battle_presentation"));
        assertFalse(dependencies.has("mega_showdown"));
        assertFalse(dependencies.has("rctmod"));
        assertEquals(
            "*",
            metadata.getAsJsonObject("suggests").get("more_cobblemon_contents").getAsString()
        );
        assertEquals("*", metadata.getAsJsonObject("suggests").get("modmenu").getAsString());
        assertEquals("*", metadata.getAsJsonObject("suggests").get("cloth-config").getAsString());

        var serialized = metadata.toString();
        assertFalse(serialized.contains("CobbleServer"));
        assertFalse(serialized.contains("cobleserver_music"));
        assertFalse(serialized.contains("kr.parkjh"));

        assertNotNull(Class.forName("jbro.cobblemon.bettermusic.BetterCobblemonMusicClient"));
        assertNotNull(Class.forName(
            "jbro.cobblemon.bettermusic.integration.mcc.MoreCobblemonContentsIntegration"
        ));
    }
}
