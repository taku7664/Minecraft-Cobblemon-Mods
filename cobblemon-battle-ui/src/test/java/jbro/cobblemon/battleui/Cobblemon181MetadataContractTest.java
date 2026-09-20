package jbro.cobblemon.battleui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class Cobblemon181MetadataContractTest {
    @Test
    void requiresTheValidatedCobblemonAndLoaderVersions() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            var metadata = JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            var dependencies = metadata.getAsJsonObject("depends");

            assertEquals(">=0.19.5", dependencies.get("fabricloader").getAsString());
            assertEquals(">=1.8.1 <1.9.0", dependencies.get("cobblemon").getAsString());
        }
    }
}
