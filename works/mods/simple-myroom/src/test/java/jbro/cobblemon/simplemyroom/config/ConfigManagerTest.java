package jbro.cobblemon.simplemyroom.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addsNewDefaultKeysWithoutReplacingExistingValues() throws Exception {
        Path config = temporaryDirectory.resolve("simple-myroom.json");
        Files.writeString(config, """
            {
              "commands": {
                "enterCooldownSeconds": 7,
                "enableDimensionAdminCommands": true
              },
              "returnBehavior": {
                "defaultAllowedDimensions": ["minecraft:overworld"],
                "clearPointWhenSourceDimensionIsNotAllowed": true
              },
              "customUserKey": "keep-me"
            }
            """);
        Path messages = temporaryDirectory.resolve("simple-myroom-messages.json");
        Files.writeString(messages, """
            {
              "roomNotFound": "custom",
              "dimensions": "legacy"
            }
            """);

        ConfigManager manager = new ConfigManager(temporaryDirectory);
        assertTrue(manager.load().successful());

        String enriched = Files.readString(config);
        assertEquals(7, manager.config().commands.enterCooldownSeconds);
        assertTrue(enriched.contains("\"enterCooldownSeconds\": 7"));
        assertTrue(enriched.contains("\"customUserKey\": \"keep-me\""));
        assertTrue(enriched.contains("\"customSpawn\""));
        assertTrue(enriched.contains("\"visitorNotifications\""));
        assertTrue(enriched.contains("\"roomPreparation\""));
        assertTrue(!enriched.contains("enableDimensionAdminCommands"));
        assertTrue(!enriched.contains("defaultAllowedDimensions"));
        assertTrue(!enriched.contains("clearPointWhenSourceDimensionIsNotAllowed"));
        String enrichedMessages = Files.readString(messages);
        assertTrue(enrichedMessages.contains("\"roomNotFound\": \"custom\""));
        assertTrue(!enrichedMessages.contains("\"dimensions\""));
    }
}
