package jbro.cobblemon.bettermusic.mbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class BetterCobblemonMusicMbcIntegrationContractTest {
    @Test
    void metadataDefinesAClientOnlyBridgeWithCompatibleDependencies() throws Exception {
        JsonObject metadata;
        try (var stream = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
            assertNotNull(stream);
            metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        assertEquals("better_cobblemon_music_mbc", metadata.get("id").getAsString());
        assertEquals("client", metadata.get("environment").getAsString());
        var dependencies = metadata.getAsJsonObject("depends");
        assertEquals(">=1.2.1 <2.0.0", dependencies.get("better_cobblemon_music").getAsString());
        assertEquals(">=1.2.1 <2.0.0", dependencies.get("cobblemon_more_battle_content").getAsString());
        assertNotNull(Class.forName("jbro.cobblemon.bettermusic.mbc.BetterCobblemonMusicMbcIntegrationClient"));
    }
}
