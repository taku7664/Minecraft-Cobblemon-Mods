package jbro.cobblemon.simplemyroom.room;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.config.SimpleMyRoomMessages;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class VisitorNotificationService {
    private Map<UUID, VisitorPresenceTracker.Presence> previous = Map.of();
    private final UnauthorizedEntryHandler unauthorizedEntryHandler;

    public VisitorNotificationService(UnauthorizedEntryHandler unauthorizedEntryHandler) {
        this.unauthorizedEntryHandler = unauthorizedEntryHandler;
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> previous = snapshot(server));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> previous = Map.of());
    }

    public List<ServerPlayer> visitorsIn(MinecraftServer server, RoomRecord room) {
        return RoomOccupancy.visitorsIn(
            server, room, SimpleMyRoom.config().visitorNotifications.includeOwnerInVisitorsList
        );
    }

    private void tick(MinecraftServer server) {
        Map<UUID, VisitorPresenceTracker.Presence> current = new HashMap<>(snapshot(server));
        for (VisitorPresenceTracker.Change change : VisitorPresenceTracker.diff(previous, current)) {
            boolean notificationsEnabled = SimpleMyRoom.config().visitorNotifications.enabled;
            if (change.from() != null && notificationsEnabled && SimpleMyRoom.config().visitorNotifications.notifyOnExit) {
                notifyOwner(server, change.playerId(), change.from(), false);
            }
            if (change.to() != null) {
                if (ejectUnauthorized(server, change.playerId(), change.to())) {
                    current.remove(change.playerId());
                    continue;
                }
                if (notificationsEnabled && SimpleMyRoom.config().visitorNotifications.notifyOnEnter) {
                    notifyOwner(server, change.playerId(), change.to(), true);
                }
            }
        }
        previous = current;
    }

    private Map<UUID, VisitorPresenceTracker.Presence> snapshot(MinecraftServer server) {
        RoomLayout layout = RoomStorage.get(server).effectiveLayout();
        Map<UUID, VisitorPresenceTracker.Presence> current = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            long roomIndex = roomIndex(player, layout);
            if (roomIndex >= 0) {
                current.put(player.getUUID(), new VisitorPresenceTracker.Presence(
                    roomIndex, player.getGameProfile().getName()
                ));
            }
        }
        return current;
    }

    private long roomIndex(ServerPlayer player, RoomLayout layout) {
        return RoomOccupancy.roomIndex(player, layout);
    }

    private boolean ejectUnauthorized(MinecraftServer server, UUID playerId, VisitorPresenceTracker.Presence presence) {
        RoomRecord room = RoomStorage.get(server).findRoom(presence.roomIndex()).orElse(null);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (room == null || player == null || !PhysicalEntryPolicy.shouldEject(
            SimpleMyRoom.config().access.ejectUnauthorizedPhysicalEntrants,
            RoomAccess.isVisitAdmin(player),
            RoomAccess.canOccupy(room, player)
        )) return false;
        return unauthorizedEntryHandler.eject(player, room);
    }

    private void notifyOwner(
        MinecraftServer server,
        UUID visitorId,
        VisitorPresenceTracker.Presence presence,
        boolean entered
    ) {
        RoomRecord room = RoomStorage.get(server).findRoom(presence.roomIndex()).orElse(null);
        if (room == null || visitorId.equals(room.ownerId()) || !room.notifyVisitors()) return;
        if (!SimpleMyRoom.config().visitorNotifications.includeTrustedPlayers && room.isTrusted(presence.playerName())) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(room.ownerId());
        if (owner == null) return;
        String template = entered ? SimpleMyRoom.messages().visitorEntered : SimpleMyRoom.messages().visitorExited;
        owner.sendSystemMessage(Component.literal(SimpleMyRoomMessages.format(template, Map.of("player", presence.playerName()))));
    }

    @FunctionalInterface
    public interface UnauthorizedEntryHandler {
        boolean eject(ServerPlayer player, RoomRecord room);
    }
}
