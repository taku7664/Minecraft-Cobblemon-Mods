package jbro.cobblemon.simplemyroom.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimpleMyRoomConfigTest {
    @Test
    void defaultsAreServerFriendlyAndBackwardCompatible() {
        SimpleMyRoomConfig config = SimpleMyRoomConfig.defaults();

        assertEquals(List.of("room", "myroom"), config.commands.roots);
        assertEquals(0, config.commands.enterCooldownSeconds);
        assertEquals(2, config.commands.adminPermissionLevel);
        assertTrue(config.roomDefaults.publicAccess);
        assertTrue(config.access.trustedPlayersCanModify);
        assertTrue(config.access.trustedPlayersCanVisitPrivateRooms);
        assertTrue(config.access.ejectUnauthorizedPhysicalEntrants);
        assertTrue(config.protection.preventUnauthorizedBlockBreak);
        assertTrue(config.keepActive.enabled);
        assertFalse(config.keepActive.defaultEnabled);
        assertEquals(20, config.keepActive.maxActiveRooms);
        assertEquals(64, config.keepActive.maxChunksPerRoom);
        assertTrue(config.customSpawn.enabled);
        assertTrue(config.customSpawn.allowReset);
        assertTrue(config.customSpawn.requireSafePosition);
        assertEquals(4, config.customSpawn.horizontalSearchRadius);
        assertEquals(4, config.customSpawn.verticalSearchRange);
        assertTrue(config.customSpawn.requireSolidFloor);
        assertTrue(config.returnBehavior.findSafeReturnPosition);
        assertTrue(config.roomPreparation.enabled);
        assertEquals(4096, config.roomPreparation.blocksPerTick);
        assertTrue(config.visitorNotifications.enabled);
        assertTrue(config.visitorNotifications.defaultEnabled);
        assertEquals(100, config.visitorNotifications.maxVisitorsShown);
        assertTrue(config.validate().isEmpty());
    }

    @Test
    void configRoundTripsAsPlainJson() {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(SimpleMyRoomConfig.defaults());
        SimpleMyRoomConfig restored = gson.fromJson(json, SimpleMyRoomConfig.class);
        restored.normalize();

        assertEquals(SimpleMyRoomConfig.defaults().commands.roots, restored.commands.roots);
        assertEquals("minecraft:grass_block", restored.layout.platformBlock);
        assertTrue(json.contains("\"preventUnauthorizedBlockBreak\""));
        assertTrue(json.contains("\"keepActive\""));
        assertTrue(json.contains("\"customSpawn\""));
        assertTrue(json.contains("\"visitorNotifications\""));
        assertTrue(json.contains("\"roomPreparation\""));
        assertFalse(json.contains("defaultAllowedDimensions"));
        assertFalse(json.contains("enableDimensionAdminCommands"));
        assertFalse(json.contains("clearPointWhenSourceDimensionIsNotAllowed"));
        assertFalse(json.contains("preventUnauthorizedHarvest"));
    }

    @Test
    void invalidLayoutsCommandsAndLimitsAreReportedTogether() {
        SimpleMyRoomConfig config = SimpleMyRoomConfig.defaults();
        config.layout.size = 100;
        config.layout.spacing = 99;
        config.commands.roots = List.of("Room", "bad root", "room");
        config.commands.enterCooldownSeconds = -1;
        config.protection.denialMessageCooldownMillis = -10;
        config.keepActive.maxActiveRooms = 0;
        config.keepActive.maxChunksPerRoom = 0;
        config.visitorNotifications.maxVisitorsShown = 0;
        config.customSpawn.horizontalSearchRadius = -1;
        config.returnBehavior.safeSearchVerticalRange = 100;
        config.roomPreparation.blocksPerTick = 0;

        List<String> errors = config.validate();

        assertTrue(errors.stream().anyMatch(message -> message.contains("layout.spacing")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("commands.roots")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("enterCooldownSeconds")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("denialMessageCooldownMillis")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("keepActive.maxActiveRooms")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("keepActive.maxChunksPerRoom")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("visitorNotifications.maxVisitorsShown")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("customSpawn.horizontalSearchRadius")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("returnBehavior.safeSearchVerticalRange")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("roomPreparation.blocksPerTick")));
    }
}
