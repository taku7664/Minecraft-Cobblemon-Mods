package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomRecordTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void publicGuestsCanVisitButCannotModify() {
        RoomRecord room = RoomRecord.create(OWNER, "Owner", 0, true, Set.of(), Set.of());

        assertTrue(room.canVisit("Guest", true));
        assertFalse(room.canModify(UUID.randomUUID(), "Guest", true));
    }

    @Test
    void trustedPlayersCanVisitPrivateAndModifyWhenConfigured() {
        RoomRecord room = RoomRecord.create(OWNER, "Owner", 0, false, Set.of("friend"), Set.of());

        assertTrue(room.canVisit("Friend", true));
        assertTrue(room.canModify(UUID.randomUUID(), "FRIEND", true));
        assertFalse(room.canVisit("Stranger", true));
    }

    @Test
    void banAlwaysRemovesTrustAndWinsAccessChecks() {
        RoomRecord room = RoomRecord.create(OWNER, "Owner", 0, true, Set.of("guest"), Set.of())
            .withBannedVisitor("Guest");

        assertTrue(room.isBanned("GUEST"));
        assertFalse(room.isTrusted("guest"));
        assertFalse(room.canVisit("guest", true));
        assertFalse(room.canModify(UUID.randomUUID(), "guest", true));
    }

    @Test
    void ownerAlwaysRetainsAccess() {
        RoomRecord room = RoomRecord.create(OWNER, "Owner", 0, false, Set.of(), Set.of());

        assertTrue(room.canOccupy(OWNER, "RenamedOwner", true));
        assertTrue(room.canModify(OWNER, "RenamedOwner", true));
    }

    @Test
    void keepActiveIsOwnedByTheRoomAndCanBeToggled() {
        RoomRecord room = RoomRecord.create(OWNER, "Owner", 0, true, Set.of(), Set.of(), true);

        assertTrue(room.keepActive());
        assertFalse(room.withKeepActive(false).keepActive());
    }

    @Test
    void customSpawnAndVisitorNotificationSettingsSurviveOtherUpdates() {
        RoomSpawnPoint spawn = new RoomSpawnPoint(12.5, 65.0, 9.5, 90.0f, 12.0f);
        RoomRecord room = RoomRecord.create(OWNER, "Owner", 0, true, Set.of(), Set.of(), false, true)
            .withCustomSpawn(spawn)
            .withPublicAccess(false)
            .withKeepActive(true)
            .withVisitorNotifications(false);

        assertEquals(Optional.of(spawn), room.customSpawn());
        assertFalse(room.publicAccess());
        assertTrue(room.keepActive());
        assertFalse(room.notifyVisitors());
        assertEquals(Optional.of(spawn), room.withOwnerName("Renamed").customSpawn());
        assertTrue(room.withoutCustomSpawn().customSpawn().isEmpty());
    }
}
