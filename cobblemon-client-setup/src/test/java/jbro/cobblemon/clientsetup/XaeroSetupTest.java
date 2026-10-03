package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class XaeroSetupTest {
    private static final Set<String> MAPS = Set.of("xaerominimap", "xaeroworldmap");
    @TempDir Path game;
    private Path config() { return game.resolve("config"); }
    private Path minimap() { return config().resolve("xaero/minimap/profiles/default.cfg"); }
    private Path worldmap() { return config().resolve("xaero/world-map/profiles/default.cfg"); }
    private Path radar() { return config().resolve("xaero/minimap/default_radar_categories_client.json"); }
    private Path options() { return game.resolve("options.txt"); }
    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void freshProfileGetsVersionedOptionsAndOnlyInstalledMapPresets() throws Exception {
        assertEquals(2, XaeroSetup.apply(game, config(), MAPS));
        assertEquals(ClientSetup.ApplyMode.ALWAYS, XaeroSetup.mode(config()));
        String keys = Files.readString(options());
        assertTrue(keys.startsWith("version:3955\n"));
        assertTrue(keys.contains("key_gui.xaero_open_map:key.keyboard.j\n"));
        assertTrue(keys.contains("key_gui.xaero_minimap_settings:key.keyboard.y\n"));
        assertTrue(keys.contains("key_gui.xaero_instant_waypoint:key.keyboard.unknown\n"));
        assertTrue(Files.readString(minimap()).contains("waypoints_in_world = true"));
        assertTrue(Files.readString(minimap()).contains("waypoints_on_minimap = true"));
        assertTrue(Files.readString(minimap()).contains("deathpoints = false"));
        assertTrue(Files.readString(worldmap()).contains("waypoints = true"));
        assertTrue(Files.readString(worldmap()).contains("render_waypoints = true"));
        assertTrue(Files.readString(worldmap()).contains("map_teleport_allowed = false"));
        assertEquals(2, JsonParser.parseString(Files.readString(radar())).getAsJsonObject()
            .getAsJsonObject("settingOverrides").get("icons").getAsInt());
        assertFalse(keys.contains("file/E19-Xaero-Icons-1.5.1.zip"));
    }

    @Test void alwaysRepairsPresetWhilePreservingOtherSettingsAndPackOrder() throws Exception {
        write(minimap(), "# personal\r\nprofile_name = My map\r\nwaypoints_in_world = true # keep\r\nzoom = 3\r\n");
        write(radar(), "{\"settingOverrides\":{\"icons\":0,\"names\":1},\"custom\":42}");
        write(game.resolve("resourcepacks/E19-Xaero-Icons-1.5.1.zip"), "installed");
        write(options(), "version:3955\r\nfullscreen:true\r\nkey_key.other:key.keyboard.k\r\n"
            + "key_key.journeymap.map_toggle_alt:key.keyboard.j\r\n"
            + "resourcePacks:[\"vanilla\",\"file/cc-journeymap-icons.zip\",\"file/other.zip\"]\r\n");
        XaeroSetup.apply(game, config(), MAPS);
        String first = Files.readString(options());
        assertTrue(first.startsWith("version:3955\r\nfullscreen:true\r\nkey_key.other:key.keyboard.k\r\n"));
        assertFalse(first.contains("journeymap"));
        assertTrue(first.contains("resourcePacks:[\"vanilla\",\"file/other.zip\",\"file/E19-Xaero-Icons-1.5.1.zip\"]\r\n"));
        assertTrue(Files.readString(minimap()).startsWith("# personal\r\nprofile_name = My map\r\nwaypoints_in_world = true # keep\r\nzoom = 3\r\n"));
        var json = JsonParser.parseString(Files.readString(radar())).getAsJsonObject();
        assertEquals(42, json.get("custom").getAsInt());
        assertEquals(1, json.getAsJsonObject("settingOverrides").get("names").getAsInt());
        write(minimap(), Files.readString(minimap()).replace("waypoints_in_world = true", "waypoints_in_world = false"));
        XaeroSetup.apply(game, config(), MAPS);
        assertTrue(Files.readString(minimap()).contains("waypoints_in_world = true"));
        assertEquals(first, Files.readString(options()));
    }

    @Test void onceModePreservesLaterChoicesAndAppliesNewlyInstalledWorldMap() throws Exception {
        XaeroSetup.saveMode(config(), ClientSetup.ApplyMode.ONCE);
        assertEquals(1, XaeroSetup.apply(game, config(), Set.of("xaerominimap")));
        assertFalse(Files.exists(worldmap()));
        write(minimap(), "waypoints_in_world = false\n");
        assertEquals(0, XaeroSetup.apply(game, config(), Set.of("xaerominimap")));
        assertEquals(1, XaeroSetup.apply(game, config(), MAPS));
        assertEquals("waypoints_in_world = false\n", Files.readString(minimap()));
        assertTrue(Files.readString(worldmap()).contains("waypoints = true"));
    }

    @Test void absentMapsLeaveEveryFileUntouched() throws Exception {
        assertEquals(0, XaeroSetup.apply(game, config(), Set.of()));
        assertFalse(Files.exists(config()));
        assertFalse(Files.exists(options()));
    }

    @Test void roundMinimapMovesToTopRightWithoutMovingOtherHudModules() throws Exception {
        Path hud = config().resolve("xaerohud.txt");
        String other = "# keep this\r\nmodule;id=example:other;x=11;y=22;fromRight=false;custom=keep;\r\n";
        write(hud, other + "module;id=xaerominimap:minimap;x=40;y=50;centered=true;fromRight=false;fromBottom=true;flippedHor=true;custom=keep;\r\n");
        write(minimap(), "minimap_shape = 0\n");
        XaeroSetup.apply(game, config(), MAPS);
        assertEquals(other + "module;id=xaerominimap:minimap;x=0;y=0;centered=false;fromRight=true;fromBottom=false;flippedHor=true;custom=keep;\r\n", Files.readString(hud));
        assertTrue(Files.readString(minimap()).contains("minimap_shape = 1\n"));
        var time = java.nio.file.attribute.FileTime.fromMillis(1000);
        Files.setLastModifiedTime(hud, time);
        XaeroSetup.apply(game, config(), MAPS);
        assertEquals(time, Files.getLastModifiedTime(hud));
    }

    @Test void missingLayoutGetsOneTopRightMinimapModule() throws Exception {
        XaeroSetup.apply(game, config(), Set.of("xaerominimap"));
        assertEquals("module;id=xaerominimap:minimap;x=0;y=0;centered=false;fromRight=true;fromBottom=false;flippedVer=false;flippedHor=false;\n",
            Files.readString(config().resolve("xaerohud.txt")));
    }

    @Test void startupDisablesTeleportInExistingXaeroWorldsWithoutChangingOtherRootSettings() throws Exception {
        Path first = game.resolve("xaero/minimap/Multiplayer_one/config.txt");
        Path second = game.resolve("xaero/minimap/Singleplayer_two/config.txt");
        write(first, "// preserved\r\nteleportationEnabled:true\r\nusingDefaultTeleportCommand:true\r\n");
        write(second, "teleportationEnabled:false\ncustom:keep\n");
        XaeroSetup.apply(game, config(), Set.of("xaerominimap"));
        assertEquals("// preserved\r\nteleportationEnabled:false\r\nusingDefaultTeleportCommand:true\r\n", Files.readString(first));
        assertEquals("teleportationEnabled:false\ncustom:keep\n", Files.readString(second));
    }

    @Test void onceModeReappliesNewWaypointPolicyAfterOldPresetWasMarkedDone() throws Exception {
        XaeroSetup.saveMode(config(), ClientSetup.ApplyMode.ONCE);
        Path state = config().resolve("cobblemon-client-setup/applied-defaults.properties");
        write(state, "xaero-xaerominimap-v1=true\nxaero-xaeroworldmap-v1=true\n");
        assertEquals(2, XaeroSetup.apply(game, config(), MAPS));
        assertTrue(Files.readString(minimap()).contains("waypoints_in_world = true"));
        assertTrue(Files.readString(worldmap()).contains("map_teleport_allowed = false"));
        assertTrue(Files.readString(state).contains("xaero-xaerominimap-v2=true"));
        assertTrue(Files.readString(state).contains("xaero-xaeroworldmap-v2=true"));
        assertEquals(0, XaeroSetup.apply(game, config(), MAPS));
    }

    @Test void malformedRadarLeavesOptionsProfilesAndCompletionUntouchedThenRetries() throws Exception {
        XaeroSetup.saveMode(config(), ClientSetup.ApplyMode.ONCE);
        write(radar(), "{broken");
        write(options(), "fullscreen:true\n");
        assertThrows(IOException.class, () -> XaeroSetup.apply(game, config(), MAPS));
        assertEquals("fullscreen:true\n", Files.readString(options()));
        assertFalse(Files.exists(minimap()));
        assertFalse(Files.exists(worldmap()));
        assertFalse(Files.exists(config().resolve("cobblemon-client-setup/applied-defaults.properties")));
        write(radar(), "{}");
        assertEquals(2, XaeroSetup.apply(game, config(), MAPS));
    }

    @Test void alreadyCorrectDefaultsAreNotRewritten() throws Exception {
        XaeroSetup.apply(game, config(), MAPS);
        var time = java.nio.file.attribute.FileTime.fromMillis(1000);
        for (Path file : new Path[] {minimap(), worldmap(), radar(), options()}) Files.setLastModifiedTime(file, time);
        XaeroSetup.apply(game, config(), MAPS);
        for (Path file : new Path[] {minimap(), worldmap(), radar(), options()}) assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test void worldMapOnlyDoesNotCreateMinimapFilesOrKeys() throws Exception {
        XaeroSetup.apply(game, config(), Set.of("xaeroworldmap"));
        assertTrue(Files.exists(worldmap()));
        assertFalse(Files.exists(minimap()));
        assertFalse(Files.exists(radar()));
        assertFalse(Files.readString(options()).contains("xaero_minimap_settings"));
    }

    @Test void invalidPackSelectionIsRejectedBeforeAnyProfileWrites() throws Exception {
        write(options(), "resourcePacks:[42]\n");
        assertThrows(IOException.class, () -> XaeroSetup.apply(game, config(), MAPS));
        assertEquals("resourcePacks:[42]\n", Files.readString(options()));
        assertFalse(Files.exists(minimap()));
        assertFalse(Files.exists(radar()));
    }

    @Test void failedOptionsWriteDoesNotCompleteOnceAndCanRetry() throws Exception {
        XaeroSetup.saveMode(config(), ClientSetup.ApplyMode.ONCE);
        Files.createDirectories(options());
        assertThrows(IOException.class, () -> XaeroSetup.apply(game, config(), MAPS));
        assertFalse(Files.exists(config().resolve("cobblemon-client-setup/applied-defaults.properties")));
        Files.delete(options());
        assertEquals(2, XaeroSetup.apply(game, config(), MAPS));
    }

    @Test void screenSavesAllModesWithoutDiscardingExistingRules() throws Exception {
        KeybindingSetup.apply(game, config(), Set.of("voicechat"));
        String before = Files.readString(config().resolve("cobblemon-client-setup/applied-defaults.properties"));
        ClientSetup.saveModes(config(), ClientSetup.ApplyMode.ONCE, ClientSetup.ApplyMode.ALWAYS, ClientSetup.ApplyMode.ONCE);
        assertEquals(ClientSetup.ApplyMode.ONCE, ClientSetup.mode(config()));
        assertEquals(ClientSetup.ApplyMode.ALWAYS, KeybindingSetup.mode(config()));
        assertEquals(ClientSetup.ApplyMode.ONCE, XaeroSetup.mode(config()));
        assertEquals(before, Files.readString(config().resolve("cobblemon-client-setup/applied-defaults.properties")));
    }
}
