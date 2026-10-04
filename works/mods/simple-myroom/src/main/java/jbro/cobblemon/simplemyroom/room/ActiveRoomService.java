package jbro.cobblemon.simplemyroom.room;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

public final class ActiveRoomService {
    private static final int ENTITY_TICKING_RADIUS = 2;
    private static final TicketType<UUID> ACTIVE_ROOM_TICKET = TicketType.create(
        "simple_myroom_active_room", Comparator.<UUID>naturalOrder());

    private final Map<UUID, Set<ChunkPos>> appliedChunks = new HashMap<>();
    private MinecraftServer server;

    public void start(MinecraftServer server) {
        this.server = server;
        if (!SimpleMyRoom.config().keepActive.restoreOnServerStart) {
            if (SimpleMyRoom.config().keepActive.logRestoration) {
                SimpleMyRoom.LOGGER.info("Skipped keep-active ticket restoration because restoreOnServerStart is disabled.");
            }
            return;
        }
        reconcile();
    }

    public void stop() {
        removeAllTickets();
        server = null;
    }

    public ChangeResult change(ServerPlayer player, boolean enabled) {
        boolean admin = RoomAccess.hasAdminPermission(player);
        if (!SimpleMyRoom.config().keepActive.allowOwnerToggle && !admin) return ChangeResult.TOGGLE_NOT_ALLOWED;
        if (enabled && !SimpleMyRoom.config().keepActive.enabled) return ChangeResult.GLOBALLY_DISABLED;
        RoomStorage storage = RoomStorage.get(player.getServer());
        RoomRecord room = storage.findRoom(player.getUUID()).orElse(null);
        if (room == null) return ChangeResult.ROOM_REQUIRED;
        if (enabled && !room.initialized()) return ChangeResult.ROOM_NOT_INITIALIZED;
        if (enabled == room.keepActive()) {
            if (enabled && !appliedChunks.containsKey(room.ownerId())) {
                return applyAndReport(room) ? ChangeResult.ENABLED : ChangeResult.CHUNK_LIMIT_EXCEEDED;
            }
            return enabled ? ChangeResult.ALREADY_ENABLED : ChangeResult.ALREADY_DISABLED;
        }
        if (enabled && storage.keepActiveRoomCount() >= SimpleMyRoom.config().keepActive.maxActiveRooms
            && !(admin && SimpleMyRoom.config().keepActive.adminsBypassActiveRoomLimit)) {
            return ChangeResult.ACTIVE_ROOM_LIMIT_REACHED;
        }
        if (enabled) {
            if (!applyAndReport(room)) return ChangeResult.CHUNK_LIMIT_EXCEEDED;
            storage.setKeepActive(room.ownerId(), true);
            logState(room, true);
            return ChangeResult.ENABLED;
        }
        removeTickets(room.ownerId());
        storage.setKeepActive(room.ownerId(), false);
        logState(room, false);
        return ChangeResult.DISABLED;
    }

    public void onRoomInitialized(RoomRecord room) {
        if (!room.keepActive() || !SimpleMyRoom.config().keepActive.enabled) return;
        RoomStorage storage = RoomStorage.get(server);
        if (storage.keepActiveRoomCount() > SimpleMyRoom.config().keepActive.maxActiveRooms || !applyAndReport(room)) {
            storage.setKeepActive(room.ownerId(), false);
            SimpleMyRoom.LOGGER.warn("Could not activate the default keep-active setting for room {} ({}).", room.index(), room.ownerName());
        }
    }

    public void reconcile() {
        removeAllTickets();
        if (server == null || !SimpleMyRoom.config().keepActive.enabled) return;
        int restoredRooms = 0;
        int restoredChunks = 0;
        RoomStorage storage = RoomStorage.get(server);
        var selected = ActiveRoomRestorePlan.select(storage.rooms(), SimpleMyRoom.config().keepActive.maxActiveRooms);
        long configuredRooms = storage.rooms().stream().filter(room -> room.initialized() && room.keepActive()).count();
        for (RoomRecord room : selected) {
            if (applyAndReport(room)) {
                restoredRooms++;
                restoredChunks += appliedChunks.get(room.ownerId()).size();
            }
        }
        if (configuredRooms > selected.size()) {
            SimpleMyRoom.LOGGER.warn(
                "Skipped keep-active restoration for {} rooms because maxActiveRooms is {}.",
                configuredRooms - selected.size(), SimpleMyRoom.config().keepActive.maxActiveRooms
            );
        }
        if (SimpleMyRoom.config().keepActive.logRestoration) {
            SimpleMyRoom.LOGGER.info("Restored keep-active tickets for {} rooms across {} entity-ticking chunks.", restoredRooms, restoredChunks);
        }
    }

    public boolean isApplied(UUID ownerId) {
        return appliedChunks.containsKey(ownerId);
    }

    public int chunkCount(RoomRecord room) {
        try {
            return plannedChunks(room).size();
        } catch (IllegalArgumentException exception) {
            return 0;
        }
    }

    private boolean applyAndReport(RoomRecord room) {
        if (server == null) return false;
        ServerLevel level = server.getLevel(RoomDimensions.KEY);
        if (level == null) return false;
        Set<ChunkPos> chunks;
        try {
            chunks = plannedChunks(room);
        } catch (IllegalArgumentException exception) {
            SimpleMyRoom.LOGGER.error("Cannot keep room {} active: {}", room.index(), exception.getMessage());
            return false;
        }
        removeTickets(room.ownerId());
        for (ChunkPos chunk : chunks) {
            level.getChunkSource().addRegionTicket(ACTIVE_ROOM_TICKET, chunk, ENTITY_TICKING_RADIUS, room.ownerId());
        }
        appliedChunks.put(room.ownerId(), chunks);
        return true;
    }

    private Set<ChunkPos> plannedChunks(RoomRecord room) {
        RoomStorage storage = RoomStorage.get(server);
        return ActiveRoomChunkPlan.chunksFor(
            storage.effectiveLayout().areaFor(room.index()),
            SimpleMyRoom.config().keepActive.maxChunksPerRoom
        );
    }

    private void removeTickets(UUID ownerId) {
        Set<ChunkPos> chunks = appliedChunks.remove(ownerId);
        if (chunks == null || server == null) return;
        ServerLevel level = server.getLevel(RoomDimensions.KEY);
        if (level == null) return;
        for (ChunkPos chunk : chunks) {
            level.getChunkSource().removeRegionTicket(ACTIVE_ROOM_TICKET, chunk, ENTITY_TICKING_RADIUS, ownerId);
        }
    }

    private void removeAllTickets() {
        for (UUID ownerId : Set.copyOf(appliedChunks.keySet())) removeTickets(ownerId);
    }

    private void logState(RoomRecord room, boolean enabled) {
        if (SimpleMyRoom.config().keepActive.logStateChanges) {
            SimpleMyRoom.LOGGER.info("Keep-active {} for room {} owned by {}.", enabled ? "enabled" : "disabled", room.index(), room.ownerName());
        }
    }

    public enum ChangeResult {
        ENABLED,
        DISABLED,
        ALREADY_ENABLED,
        ALREADY_DISABLED,
        GLOBALLY_DISABLED,
        TOGGLE_NOT_ALLOWED,
        ROOM_REQUIRED,
        ROOM_NOT_INITIALIZED,
        ACTIVE_ROOM_LIMIT_REACHED,
        CHUNK_LIMIT_EXCEEDED
    }
}
