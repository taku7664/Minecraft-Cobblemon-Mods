package jbro.cobblemon.simplemyroom.room;

import java.util.Comparator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class RoomOccupancy {
    private RoomOccupancy() {
    }

    public static long roomIndex(ServerPlayer player, RoomLayout layout) {
        if (!RoomDimensions.isRoom(player.level())) return -1;
        return layout.indexAt((int) Math.floor(player.getX()), (int) Math.floor(player.getZ())).orElse(-1L);
    }

    public static List<ServerPlayer> visitorsIn(MinecraftServer server, RoomRecord room, boolean includeOwner) {
        RoomLayout layout = RoomStorage.get(server).effectiveLayout();
        return server.getPlayerList().getPlayers().stream()
            .filter(player -> includeOwner || !player.getUUID().equals(room.ownerId()))
            .filter(player -> roomIndex(player, layout) == room.index())
            .sorted(Comparator.comparing(player -> player.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }
}
