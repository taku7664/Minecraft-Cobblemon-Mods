package jbro.minecraft.roundingblock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RoundingBlockModuleContractTest {
    @Test
    void metadataIsClientOnlyAndDoesNotRequireShaderMods() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
            assertTrue(stream != null, "fabric.mod.json must be packaged");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"environment\": \"client\""));
            assertTrue(json.contains("\"fabric-api\": \"*\""));
            assertTrue(json.contains("\"modmenu\""), "Mod Menu must discover the config screen entrypoint");
            assertTrue(
                json.contains("jbro.minecraft.roundingblock.client.settings.RoundingBlockModMenu"),
                "Mod Menu entrypoint must name the Rounding-Block config screen factory"
            );
            assertTrue(json.contains("\"cloth-config\": \"*\""), "Cloth Config must be declared as optional");
            assertFalse(json.toLowerCase().contains("iris"));
            assertFalse(json.toLowerCase().contains("sodium"));
        }
    }
}
