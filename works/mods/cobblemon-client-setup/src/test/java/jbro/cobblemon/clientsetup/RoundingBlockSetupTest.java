package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RoundingBlockSetupTest {
    private static final Set<String> MODS = Set.of("rounding_block");
    @TempDir Path configDirectory;

    private Path configFile() { return configDirectory.resolve("rounding-block.json"); }
    private JsonObject config() throws Exception {
        return JsonParser.parseString(Files.readString(configFile())).getAsJsonObject();
    }

    @Test void missingConfigIsCreatedDisabled() throws Exception {
        assertEquals(ClientSetup.Result.APPLIED, RoundingBlockSetup.apply(configDirectory, MODS));
        assertFalse(config().get("enabled").getAsBoolean());
    }

    @Test void enabledConfigIsDisabledAndOtherSettingsKept() throws Exception {
        Files.writeString(configFile(), "{\"enabled\": true, \"quality\": {\"radius\": 0.15, \"segments\": 5}}");
        assertEquals(ClientSetup.Result.APPLIED, RoundingBlockSetup.apply(configDirectory, MODS));
        assertFalse(config().get("enabled").getAsBoolean());
        assertEquals(0.15, config().getAsJsonObject("quality").get("radius").getAsDouble());
        assertEquals(5, config().getAsJsonObject("quality").get("segments").getAsInt());
    }

    @Test void laterLaunchKeepsPlayerChoice() throws Exception {
        RoundingBlockSetup.apply(configDirectory, MODS);
        Files.writeString(configFile(), "{\"enabled\": true}");
        assertEquals(ClientSetup.Result.ALREADY_APPLIED, RoundingBlockSetup.apply(configDirectory, MODS));
        assertTrue(config().get("enabled").getAsBoolean());
    }

    @Test void absentModLeavesNothingBehind() throws Exception {
        assertEquals(ClientSetup.Result.MOD_ABSENT, RoundingBlockSetup.apply(configDirectory, Set.of()));
        assertFalse(Files.exists(configFile()));
        assertFalse(Files.exists(configDirectory.resolve("cobblemon-client-setup")));
    }

    @Test void malformedConfigIsPreservedAndRetried() throws Exception {
        Files.writeString(configFile(), "{not json");
        assertThrows(java.io.IOException.class, () -> RoundingBlockSetup.apply(configDirectory, MODS));
        assertEquals("{not json", Files.readString(configFile()));
        Files.writeString(configFile(), "{}");
        assertEquals(ClientSetup.Result.APPLIED, RoundingBlockSetup.apply(configDirectory, MODS));
    }
}
