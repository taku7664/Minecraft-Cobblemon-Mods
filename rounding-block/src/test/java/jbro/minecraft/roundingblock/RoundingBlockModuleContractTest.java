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
            assertFalse(json.toLowerCase().contains("iris"));
            assertFalse(json.toLowerCase().contains("sodium"));
        }
    }
}
