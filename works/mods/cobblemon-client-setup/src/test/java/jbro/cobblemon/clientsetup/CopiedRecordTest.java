package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The PPakemon 1.0.0 modpack shipped the author's completion record; players must still get every rule. */
final class CopiedRecordTest {
    // The record as exported in the pack: every rule marked done, no profile id.
    private static final String SHIPPED = "#Applied once; later launches preserve user settings\n"
        + "fresh-install=false\nkeybindings-iris-v1=true\nkeybindings-zoomify-primary-v1=true\n"
        + "options-defaults-v1=true\nrounding-block-disabled-v1=true\nshader-defaults-v2=true\n"
        + "xaero-world-reset-2026-10-11=true\n";
    @TempDir Path game;

    private Path config() { return game.resolve("config"); }
    private Path state() { return config().resolve("cobblemon-client-setup/applied-defaults.properties"); }
    private void ship() throws Exception {
        Files.createDirectories(state().getParent());
        Files.writeString(state(), SHIPPED);
    }

    @Test void copiedRecordDoesNotStopTheIrisReloadUnbind() throws Exception {
        ship();
        Files.writeString(game.resolve("options.txt"), "version:3955\nkey_key.cobblemon.throwpartypokemon:key.keyboard.r\n");
        assertEquals(1, KeybindingSetup.apply(game, config(), Set.of("iris")));
        assertTrue(Files.readString(game.resolve("options.txt")).contains("key_iris.keybind.reload:key.keyboard.unknown\n"));
        assertEquals(SetupFiles.profileId(config()), SetupFiles.readRawState(config()).getProperty(SetupFiles.PROFILE));
        Files.writeString(game.resolve("options.txt"), "key_iris.keybind.reload:key.keyboard.p\n");
        assertEquals(0, KeybindingSetup.apply(game, config(), Set.of("iris")));
    }

    @Test void copiedRecordStillTurnsRoundingBlockOff() throws Exception {
        ship();
        assertEquals(ClientSetup.Result.APPLIED, RoundingBlockSetup.apply(config(), Set.of("rounding_block")));
        assertTrue(Files.readString(config().resolve("rounding-block.json")).contains("\"enabled\": false"));
    }

    @Test void neverLaunchedPackInstallIsAFreshInstall() throws Exception {
        ship();
        assertEquals(1, OptionsSetup.apply(game, config(), Set.of()));
        assertTrue(Files.readString(game.resolve("options.txt")).contains("lang:ko_kr\n"));
    }

    @Test void copiedResetRecordKeepsTheNewWorldsMaps() throws Exception {
        ship();
        Path map = game.resolve("xaero/minimap/Multiplayer_210.207.108.196");
        Files.createDirectories(map);
        assertEquals(0, XaeroWorldReset.apply(game, config()));
        assertTrue(Files.isDirectory(map));
        assertEquals("true", SetupFiles.readState(config()).getProperty(XaeroWorldReset.RULE));
    }

    @Test void ownRecordIsKept() throws Exception {
        assertEquals(1, KeybindingSetup.apply(game, config(), Set.of("iris")));
        assertEquals(0, KeybindingSetup.apply(game, config(), Set.of("iris")));
    }
}
