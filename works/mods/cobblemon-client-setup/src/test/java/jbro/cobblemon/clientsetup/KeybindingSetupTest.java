package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KeybindingSetupTest {
    private static final Set<String> MODS = Set.of("cobbled_level_control", "talkingheads", "voicechat", "zoomify", "craftingtweaks");
    private static final List<String> KEYS = List.of(
        "key_key.cobbled_level_control.toggle_hud", "key_talkingheads.keybinding.modToggle",
        "key_key.hide_icons", "key_key.mute_microphone", "key_zoomify.key.zoom.secondary",
        "key_key.craftingtweaks.compress_stack", "key_key.craftingtweaks.refill_last_stack");
    @TempDir Path gameDirectory;

    private Path configDirectory() { return gameDirectory.resolve("config"); }
    private Path optionsFile() { return gameDirectory.resolve("options.txt"); }
    private Path craftingFile() { return configDirectory().resolve("craftingtweaks-common.toml"); }
    private Path stateFile() { return configDirectory().resolve("cobblemon-client-setup/applied-defaults.properties"); }
    private void crafting(String content) throws IOException {
        Files.createDirectories(configDirectory());
        Files.writeString(craftingFile(), content);
    }

    @Test void clearsOnlyApprovedKeysAndKeepsOtherOptionsByteForByte() throws Exception {
        String other = "\uFEFFversion:3955\r\nkey_key.accessories.open:key.keyboard.h\r\n"
            + "key_key.more_cobblemon_contents.battle_info:key.keyboard.tab\r\n"
            + "key_zoomify.key.zoom:key.keyboard.left.alt\r\nresourcePacks:[\"vanilla\"]\r\n";
        String original = other;
        for (String key : KEYS) original += key + ":key.keyboard.h\r\n";
        Files.writeString(optionsFile(), original);
        assertEquals(5, KeybindingSetup.apply(gameDirectory, configDirectory(), MODS));
        String expected = other;
        for (String key : KEYS) expected += key + ":key.keyboard.unknown\r\n";
        assertEquals(expected, Files.readString(optionsFile()));
        assertEquals("BUTTONS", new TomlParser().parse(Files.readString(craftingFile())).get("client.mode"));
        assertEquals(ClientSetup.ApplyMode.ONCE, KeybindingSetup.mode(configDirectory()));
    }

    @Test void laterLaunchPreservesPersonalBindingsAndCraftingMode() throws Exception {
        KeybindingSetup.apply(gameDirectory, configDirectory(), MODS);
        String personal = "key_key.hide_icons:key.keyboard.j\nkey_zoomify.key.zoom.secondary:key.keyboard.p\n";
        Files.writeString(optionsFile(), personal);
        crafting("[client]\nmode = \"HOTKEYS\" # my choice\n");
        byte[] state = Files.readAllBytes(stateFile());
        assertEquals(0, KeybindingSetup.apply(gameDirectory, configDirectory(), MODS));
        assertEquals(personal, Files.readString(optionsFile()));
        assertTrue(Files.readString(craftingFile()).contains("HOTKEYS"));
        assertArrayEquals(state, Files.readAllBytes(stateFile()));
    }

    @Test void newlyAddedBadgeKeyClearsOnceWithoutChangingPolicyBKeyOrOldCompletedKeys() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "keybindings-voicechat-v1=true\nkeybindings-voicechat-microphone-v1=true\n");
        Files.writeString(optionsFile(), "key_key.jbro_policy.quick_waypoint:key.keyboard.b\n"
            + "key_key.pokebadges.open_badgebox:key.keyboard.b\n"
            + "key_key.hide_icons:key.keyboard.j\n");

        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("pokebadges", "voicechat")));
        assertEquals("key_key.jbro_policy.quick_waypoint:key.keyboard.b\n"
            + "key_key.pokebadges.open_badgebox:key.keyboard.unknown\n"
            + "key_key.hide_icons:key.keyboard.j\n", Files.readString(optionsFile()));
        assertTrue(Files.readString(stateFile()).contains("keybindings-pokebadges-v1=true"));

        Files.writeString(optionsFile(), "key_key.pokebadges.open_badgebox:key.keyboard.m\n");
        assertEquals(0, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("pokebadges")));
        assertEquals("key_key.pokebadges.open_badgebox:key.keyboard.m\n", Files.readString(optionsFile()));
    }

    @Test void newVoiceMuteAndPvpRoomBindingsApplyDespiteOldVoiceCompletion() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "keybindings-voicechat-v1=true\n");
        Files.writeString(optionsFile(), "key_key.hide_icons:key.keyboard.j\n"
            + "key_key.mute_microphone:key.keyboard.m\n"
            + "key_key.more_cobblemon_contents.pvp.room_hud.open:key.keyboard.o\n");

        assertEquals(2, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat", "more_cobblemon_contents_pvp")));
        assertEquals("key_key.hide_icons:key.keyboard.j\n"
            + "key_key.mute_microphone:key.keyboard.unknown\n"
            + "key_key.more_cobblemon_contents.pvp.room_hud.open:key.keyboard.j\n", Files.readString(optionsFile()));
        String state = Files.readString(stateFile());
        assertTrue(state.contains("keybindings-voicechat-microphone-v1=true"));
        assertTrue(state.contains("keybindings-more_cobblemon_contents_pvp-room-open-v2=true"));
        assertEquals(0, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat", "more_cobblemon_contents_pvp")));
    }

    @Test void revisedPresetsApplyOnceDespitePreviousCompletionAndPreserveOtherKeys() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "keybindings-zoomify-v1=true\nkeybindings-more_cobblemon_contents_pvp-room-open-v1=true\n");
        String untouched = "key_key.more_cobblemon_contents.pvp.room_hud.toggle:key.keyboard.h\n"
            + "key_key.cobblemon_ui.select_action:key.keyboard.z\n"
            + "key_key.cobblemon_ui.cancel_action:key.keyboard.x\n"
            + "key_keybind.sophisticatedbackpacks.open_backpack:key.keyboard.b\n";
        Files.writeString(optionsFile(), untouched
            + "key_key.jbro_policy.quick_waypoint:key.keyboard.b\n"
            + "key_key.catchrate.show_comparison:key.keyboard.g\n"
            + "key_key.cobblemon_ui.decrease_font:key.keyboard.left.bracket\n"
            + "key_key.cobblemon_ui.increase_font:key.keyboard.right.bracket\n"
            + "key_key.cobblemon.ridingfreelook:key.keyboard.left.alt\n"
            + "key_key.more_cobblemon_contents.pvp.room_hud.open:key.keyboard.tab\n"
            + "key_zoomify.key.zoom:key.keyboard.c\n"
            + "key_keybind.sophisticatedbackpacks.toggle_upgrade_1:key.keyboard.z\n"
            + "key_keybind.sophisticatedbackpacks.toggle_upgrade_2:key.keyboard.x\n");
        Set<String> mods = Set.of("jbro_policy", "catchrate-display", "cobblemon_ui", "cobblemon",
            "more_cobblemon_contents_pvp", "zoomify", "sophisticatedbackpacks");
        assertEquals(7, KeybindingSetup.apply(gameDirectory, configDirectory(), mods));
        String updated = Files.readString(optionsFile());
        assertTrue(updated.startsWith(untouched));
        for (String key : List.of("key_key.jbro_policy.quick_waypoint", "key_key.catchrate.show_comparison",
            "key_key.cobblemon.ridingfreelook", "key_keybind.sophisticatedbackpacks.toggle_upgrade_1",
            "key_keybind.sophisticatedbackpacks.toggle_upgrade_2")) {
            assertTrue(updated.contains(key + ":key.keyboard.unknown\n"));
        }
        assertTrue(updated.contains("key_key.cobblemon_ui.decrease_font:key.keyboard.equal\n"));
        assertTrue(updated.contains("key_key.cobblemon_ui.increase_font:key.keyboard.minus\n"));
        assertTrue(updated.contains("key_key.more_cobblemon_contents.pvp.room_hud.open:key.keyboard.j\n"));
        assertTrue(updated.contains("key_zoomify.key.zoom:key.keyboard.left.alt\n"));
        Files.writeString(optionsFile(), untouched);
        assertEquals(0, KeybindingSetup.apply(gameDirectory, configDirectory(), mods));
        assertEquals(untouched, Files.readString(optionsFile()));
    }

    @Test void absentModsAreSkippedAndLaterInstallationHasItsOwnOnceRecord() throws Exception {
        assertEquals(0, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of()));
        assertFalse(Files.exists(optionsFile()));
        assertFalse(Files.exists(stateFile()));
        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat")));
        assertEquals("version:3955\nkey_key.hide_icons:key.keyboard.unknown\nkey_key.mute_microphone:key.keyboard.unknown\n", Files.readString(optionsFile()));
        Files.writeString(optionsFile(), "key_key.hide_icons:key.keyboard.j\n");
        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat", "zoomify")));
        assertEquals("key_key.hide_icons:key.keyboard.j\nkey_zoomify.key.zoom.secondary:key.keyboard.unknown\nkey_zoomify.key.zoom:key.keyboard.left.alt\n", Files.readString(optionsFile()));
        assertFalse(Files.exists(craftingFile()));
    }

    @Test void freshProfileSeedsBindingsBeforeMinecraftLoadsOptions() throws Exception {
        KeybindingSetup.apply(gameDirectory, configDirectory(), MODS);
        String options = Files.readString(optionsFile());
        assertEquals(9, options.lines().count());
        assertTrue(options.startsWith("version:3955\n"));
        for (String key : KEYS) assertTrue(options.contains(key + ":key.keyboard.unknown\n"));
    }

    @Test void duplicatesAreAllClearedWithoutMatchingLongerKeys() throws Exception {
        Files.writeString(optionsFile(), "key_key.hide_icons:key.keyboard.h\nkey_key.hide_icons.extra:key.keyboard.h\nkey_key.hide_icons:key.keyboard.j");
        KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat"));
        assertEquals("key_key.hide_icons:key.keyboard.unknown\nkey_key.hide_icons.extra:key.keyboard.h\nkey_key.hide_icons:key.keyboard.unknown\nkey_key.mute_microphone:key.keyboard.unknown\n", Files.readString(optionsFile()));
    }

    @Test void appendingMissingKeysRespectsExistingNewlinesAndMissingFinalNewline() throws Exception {
        Files.writeString(optionsFile(), "fullscreen:false\r\nkey_key.other:key.keyboard.k");
        KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat"));
        assertEquals("fullscreen:false\r\nkey_key.other:key.keyboard.k\r\nkey_key.hide_icons:key.keyboard.unknown\r\nkey_key.mute_microphone:key.keyboard.unknown\r\n", Files.readString(optionsFile()));
    }

    @Test void craftingButtonsRetainOtherValuesAndComments() throws Exception {
        crafting("# retain comment\n[client]\nmode = \"DEFAULT\"\nrightClickCraftsStack = false\n[common]\ncompressRequiresCraftingGrid = false\n");
        KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("craftingtweaks"));
        var parsed = new TomlParser().parse(Files.readString(craftingFile()));
        assertEquals("BUTTONS", parsed.get("client.mode"));
        assertEquals(Boolean.FALSE, parsed.get("client.rightClickCraftsStack"));
        assertEquals(Boolean.FALSE, parsed.get("common.compressRequiresCraftingGrid"));
        assertTrue(Files.readString(craftingFile()).contains("retain comment"));
    }

    @Test void malformedCraftingConfigLeavesOptionsAndCompletionUntouched() throws Exception {
        String original = "key_key.craftingtweaks.compress_stack:key.keyboard.k\n";
        Files.writeString(optionsFile(), original);
        crafting("[client\nmode = \"DEFAULT\"\n");
        assertThrows(IOException.class, () -> KeybindingSetup.apply(gameDirectory, configDirectory(), MODS));
        assertEquals(original, Files.readString(optionsFile()));
        assertFalse(Files.exists(stateFile()));
        crafting("[client]\nmode = \"DEFAULT\"\n");
        assertEquals(5, KeybindingSetup.apply(gameDirectory, configDirectory(), MODS));
    }

    @Test void failedOptionsWriteIsNotRecordedAsComplete() throws Exception {
        Files.createDirectories(optionsFile());
        assertThrows(IOException.class, () -> KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat")));
        assertFalse(Files.exists(stateFile()));
        Files.delete(optionsFile());
        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat")));
    }

    @Test void alwaysModeClearsManualRebindingsAgain() throws Exception {
        KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat"));
        KeybindingSetup.saveMode(configDirectory(), ClientSetup.ApplyMode.ALWAYS);
        Files.writeString(optionsFile(), "key_key.hide_icons:key.keyboard.j\n");
        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat")));
        assertEquals("key_key.hide_icons:key.keyboard.unknown\nkey_key.mute_microphone:key.keyboard.unknown\n", Files.readString(optionsFile()));
    }

    @Test void clcAlwaysAndKeybindingOnceDoNotOverwriteEachOthersState() throws Exception {
        ClientSetup.saveMode(configDirectory(), ClientSetup.ApplyMode.ONCE);
        ClientSetup.apply(configDirectory(), true);
        KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("cobbled_level_control"));
        String state = Files.readString(stateFile());
        assertTrue(state.contains("clc-hud-v1=true"));
        assertTrue(state.contains("keybindings-cobbled_level_control-v1=true"));
        ClientSetup.saveMode(configDirectory(), ClientSetup.ApplyMode.ALWAYS);
        Path clc = configDirectory().resolve("cobbled_level_control/client.toml");
        Files.writeString(clc, "[client.hud]\nenabled = true\n");
        Files.writeString(optionsFile(), "key_key.cobbled_level_control.toggle_hud:key.keyboard.j\n");
        ClientSetup.apply(configDirectory(), true);
        KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("cobbled_level_control"));
        assertFalse(ClientSetup.hudEnabled(configDirectory()));
        assertTrue(Files.readString(optionsFile()).contains("key.keyboard.j"));
    }

    @Test void invalidModePreservesOptionsAndDoesNotCreateCompletion() throws Exception {
        Path settings = configDirectory().resolve("cobblemon-client-setup/client.toml");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "[keybindings]\nmode = \"SOMETIMES\"\n");
        Files.writeString(optionsFile(), "key_key.hide_icons:key.keyboard.h\n");
        assertThrows(IOException.class, () -> KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat")));
        assertTrue(Files.readString(optionsFile()).contains("key.keyboard.h"));
        assertFalse(Files.exists(stateFile()));
    }

    @Test void unreadableCompletionStateDoesNotClearPersonalKeys() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "broken=" + "\\" + "uQQQQ\n");
        Files.writeString(optionsFile(), "key_key.hide_icons:key.keyboard.h\n");
        assertThrows(IOException.class, () -> KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("voicechat")));
        assertEquals("key_key.hide_icons:key.keyboard.h\n", Files.readString(optionsFile()));
    }

    @Test void alreadyClearedFilesAreNotReformatted() throws Exception {
        String options = "key_key.craftingtweaks.compress_stack:key.keyboard.unknown\r\nkey_key.craftingtweaks.refill_last_stack:key.keyboard.unknown\r\n";
        String crafting = "# retain exact spacing\r\n[client]\r\n\tmode   = \"BUTTONS\"\r\n";
        Files.writeString(optionsFile(), options);
        crafting(crafting);
        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("craftingtweaks")));
        assertEquals(options, Files.readString(optionsFile()));
        assertEquals(crafting, Files.readString(craftingFile()));
        assertTrue(Files.readString(stateFile()).contains("keybindings-craftingtweaks-v1=true"));
    }

    @Test void settingsScreenSavesBothModesWithoutLosingOtherSettings() throws Exception {
        Path settings = configDirectory().resolve("cobblemon-client-setup/client.toml");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "# keep me\n[other]\nvalue = 42\n");
        ClientSetup.saveModes(configDirectory(), ClientSetup.ApplyMode.ONCE, ClientSetup.ApplyMode.ALWAYS);
        assertEquals(ClientSetup.ApplyMode.ONCE, ClientSetup.mode(configDirectory()));
        assertEquals(ClientSetup.ApplyMode.ALWAYS, KeybindingSetup.mode(configDirectory()));
        assertEquals(42, (int) new TomlParser().parse(Files.readString(settings)).get("other.value"));
        assertTrue(Files.readString(settings).contains("keep me"));
    }

    @Test void irisShaderReloadKeyClearsOnceAndKeepsOtherIrisKeys() throws Exception {
        Files.writeString(optionsFile(), "key_iris.keybind.reload:key.keyboard.r
key_iris.keybind.toggleShaders:key.keyboard.k
");
        assertEquals(1, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("iris")));
        assertEquals("key_iris.keybind.reload:key.keyboard.unknown
key_iris.keybind.toggleShaders:key.keyboard.k
",
            Files.readString(optionsFile()));
        Files.writeString(optionsFile(), "key_iris.keybind.reload:key.keyboard.r
");
        assertEquals(0, KeybindingSetup.apply(gameDirectory, configDirectory(), Set.of("iris")));
        assertEquals("key_iris.keybind.reload:key.keyboard.r
", Files.readString(optionsFile()));
    }
}
