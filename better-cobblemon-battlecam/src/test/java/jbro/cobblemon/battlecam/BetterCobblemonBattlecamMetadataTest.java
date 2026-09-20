package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class BetterCobblemonBattlecamMetadataTest {
    @Test
    void ownsANonConflictingIdAndRequiresTheValidatedRuntime() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            var metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var dependencies = metadata.getAsJsonObject("depends");

            assertEquals("better_cobblemon_battlecam", metadata.get("id").getAsString());
            assertEquals(">=0.19.5", dependencies.get("fabricloader").getAsString());
            assertEquals(">=1.8.1 <1.9.0", dependencies.get("cobblemon").getAsString());
        }
    }
}
