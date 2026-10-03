package jbro.cobblemon.clientdefaults;

import static org.junit.jupiter.api.Assertions.*;

import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientDefaultsTest {
    @TempDir Path configDirectory;

    private Path clientFile() {
        return configDirectory.resolve("cobbled_level_control/client.toml");
    }

    private Path stateFile() {
        return configDirectory.resolve("cobblemon-client-defaults/applied-defaults.properties");
    }

    private Path settingsFile() {
        return configDirectory.resolve("cobblemon-client-defaults/client.toml");
    }

    private void client(String content) throws IOException {
        Files.createDirectories(clientFile().getParent());
        Files.writeString(clientFile(), content);
    }

    @Test void firstLaunchDefaultsToEveryLaunchAndSeedsDisabledHud() throws Exception {
        assertEquals(ClientDefaults.Result.APPLIED, ClientDefaults.apply(configDirectory, true));
        assertEquals(Boolean.FALSE, new TomlParser().parse(Files.readString(clientFile())).get("client.hud.enabled"));
        assertEquals(ClientDefaults.ApplyMode.ALWAYS, ClientDefaults.mode(configDirectory));
        assertTrue(Files.readString(settingsFile()).contains("ALWAYS"));
        assertFalse(Files.exists(stateFile()));
    }

    @Test void existingConfigRetainsOtherValuesCommentsAndServerFile() throws Exception {
        client("""
            # Keep this comment
            [client.hud]
            enabled = true
            mode = "DETAILED"
            showWarningLine = false
            offsetX = 42
            [client.other]
            enabled = true
            """);
        Path server = clientFile().resolveSibling("server.toml");
        Files.writeString(server, "[leveling]\nrestrictLeveling = true\n");
        ClientDefaults.apply(configDirectory, true);
        var parsed = new TomlParser().parse(Files.readString(clientFile()));
        assertEquals(Boolean.FALSE, parsed.get("client.hud.enabled"));
        assertEquals("DETAILED", parsed.get("client.hud.mode"));
        assertEquals(Boolean.FALSE, parsed.get("client.hud.showWarningLine"));
        assertEquals(42, (int) parsed.get("client.hud.offsetX"));
        assertEquals(Boolean.TRUE, parsed.get("client.other.enabled"));
        assertTrue(Files.readString(clientFile()).contains("Keep this comment"));
        assertEquals("[leveling]\nrestrictLeveling = true\n", Files.readString(server));
    }

    @Test void laterLaunchPreservesManualEnableAndDoesNotRewriteFiles() throws Exception {
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        ClientDefaults.apply(configDirectory, true);
        String manual = "[client.hud]\nenabled = true # my choice\n";
        client(manual);
        byte[] state = Files.readAllBytes(stateFile());
        assertEquals(ClientDefaults.Result.ALREADY_APPLIED, ClientDefaults.apply(configDirectory, true));
        assertEquals(manual, Files.readString(clientFile()));
        assertArrayEquals(state, Files.readAllBytes(stateFile()));
    }

    @Test void absentModIsSkippedAndCanBeInstalledLater() throws Exception {
        assertEquals(ClientDefaults.Result.MOD_ABSENT, ClientDefaults.apply(configDirectory, false));
        assertFalse(Files.exists(clientFile()));
        assertFalse(Files.exists(stateFile()));
        assertFalse(Files.exists(settingsFile()));
        assertEquals(ClientDefaults.Result.APPLIED, ClientDefaults.apply(configDirectory, true));
    }

    @Test void malformedTomlIsUntouchedAndNotMarkedSuccessful() throws Exception {
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        String malformed = "[client.hud\nenabled = true\n";
        client(malformed);
        assertThrows(IOException.class, () -> ClientDefaults.apply(configDirectory, true));
        assertEquals(malformed, Files.readString(clientFile()));
        assertFalse(Files.exists(stateFile()));
        client("[client.hud]\nenabled = true\n");
        assertEquals(ClientDefaults.Result.APPLIED, ClientDefaults.apply(configDirectory, true));
    }

    @Test void missingHudTableIsCreatedWithoutLosingOtherSections() throws Exception {
        client("[client.other]\nenabled = true\n");
        ClientDefaults.apply(configDirectory, true);
        assertFalse(ClientDefaults.hudEnabled(configDirectory));
        assertEquals(Boolean.TRUE, new TomlParser().parse(Files.readString(clientFile())).get("client.other.enabled"));
    }

    @Test void removedConfigAfterFirstApplyIsNotRecreated() throws Exception {
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        ClientDefaults.apply(configDirectory, true);
        Files.delete(clientFile());
        assertEquals(ClientDefaults.Result.ALREADY_APPLIED, ClientDefaults.apply(configDirectory, true));
        assertFalse(Files.exists(clientFile()));
    }

    @Test void settingsScreenChoiceIsPreservedOnNextLaunch() throws Exception {
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        ClientDefaults.apply(configDirectory, true);
        client("[client.hud]\nenabled = true\n");
        assertEquals(ClientDefaults.ApplyMode.ONCE, ClientDefaults.mode(configDirectory));
        assertEquals(ClientDefaults.Result.ALREADY_APPLIED, ClientDefaults.apply(configDirectory, true));
        assertTrue(ClientDefaults.hudEnabled(configDirectory));
    }

    @Test void nonBooleanHudSettingIsRejectedWithoutOverwritingIt() throws Exception {
        String invalid = "[client.hud]\nenabled = \"yes\"\n";
        client(invalid);
        assertThrows(IOException.class, () -> ClientDefaults.apply(configDirectory, true));
        assertEquals(invalid, Files.readString(clientFile()));
        assertFalse(Files.exists(stateFile()));
    }

    @Test void failedFileWriteDoesNotLeaveCompletionMarker() throws Exception {
        Files.createDirectories(clientFile());
        assertThrows(IOException.class, () -> ClientDefaults.apply(configDirectory, true));
        assertFalse(Files.exists(stateFile()));
    }

    @Test void alreadyDisabledConfigIsNotReformatted() throws Exception {
        String original = "# original spacing\r\n[client.hud]\r\n\tenabled   = false\r\n";
        client(original);
        assertEquals(ClientDefaults.Result.APPLIED, ClientDefaults.apply(configDirectory, true));
        assertEquals(original, Files.readString(clientFile()));
        assertTrue(Files.exists(settingsFile()));
    }

    @Test void unreadableStateDoesNotModifyClientConfig() throws Exception {
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        String original = "[client.hud]\nenabled = true\n";
        client(original);
        Files.createDirectories(stateFile().getParent());
        String malformedState = "clc-hud-v1=" + "\\" + "uQQQQ\n";
        Files.writeString(stateFile(), malformedState);
        assertThrows(IOException.class, () -> ClientDefaults.apply(configDirectory, true));
        assertEquals(original, Files.readString(clientFile()));
        assertEquals(malformedState, Files.readString(stateFile()));
    }

    @Test void everyLaunchDisablesManuallyReenabledHud() throws Exception {
        ClientDefaults.apply(configDirectory, true);
        client("[client.hud]\nenabled = true\nmode = \"DETAILED\"\n");
        assertEquals(ClientDefaults.Result.APPLIED, ClientDefaults.apply(configDirectory, true));
        assertFalse(ClientDefaults.hudEnabled(configDirectory));
        assertEquals("DETAILED", new TomlParser().parse(Files.readString(clientFile())).get("client.hud.mode"));
    }

    @Test void everyLaunchIgnoresEarlierOnceCompletionMarker() throws Exception {
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        ClientDefaults.apply(configDirectory, true);
        assertTrue(Files.readString(stateFile()).contains("clc-hud-v1=true"));
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ALWAYS);
        client("[client.hud]\nenabled = true\n");
        assertEquals(ClientDefaults.Result.APPLIED, ClientDefaults.apply(configDirectory, true));
        assertFalse(ClientDefaults.hudEnabled(configDirectory));
    }

    @Test void everyLaunchRecreatesDeletedConfig() throws Exception {
        ClientDefaults.apply(configDirectory, true);
        Files.delete(clientFile());
        ClientDefaults.apply(configDirectory, true);
        assertTrue(Files.exists(clientFile()));
        assertFalse(ClientDefaults.hudEnabled(configDirectory));
    }

    @Test void everyLaunchDoesNotDependOnOnceCompletionState() throws Exception {
        Files.createDirectories(stateFile());
        client("[client.hud]\nenabled = true\n");
        ClientDefaults.apply(configDirectory, true);
        assertFalse(ClientDefaults.hudEnabled(configDirectory));
    }

    @Test void invalidModeIsNotSilentlyResetAndDoesNotModifyClc() throws Exception {
        client("[client.hud]\nenabled = true\n");
        Files.createDirectories(settingsFile().getParent());
        String invalid = "[clc_hud]\nmode = \"SOMETIMES\"\n";
        Files.writeString(settingsFile(), invalid);
        assertThrows(IOException.class, () -> ClientDefaults.apply(configDirectory, true));
        assertTrue(ClientDefaults.hudEnabled(configDirectory));
        assertEquals(invalid, Files.readString(settingsFile()));
    }

    @Test void changingModePreservesOtherRulesAndTheirComments() throws Exception {
        Files.createDirectories(settingsFile().getParent());
        Files.writeString(settingsFile(), "# keep other rules\n[other_rule]\nmode = \"ONCE\"\n");
        ClientDefaults.saveMode(configDirectory, ClientDefaults.ApplyMode.ONCE);
        assertEquals("ONCE", new TomlParser().parse(Files.readString(settingsFile())).get("other_rule.mode"));
        assertTrue(Files.readString(settingsFile()).contains("keep other rules"));
    }
}
