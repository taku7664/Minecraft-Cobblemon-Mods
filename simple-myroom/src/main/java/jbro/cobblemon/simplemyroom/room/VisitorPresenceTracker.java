package jbro.cobblemon.simplemyroom.room;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VisitorPresenceTracker {
    private VisitorPresenceTracker() {
    }

    public static List<Change> diff(Map<UUID, Presence> previous, Map<UUID, Presence> current) {
        Set<UUID> players = new HashSet<>(previous.keySet());
        players.addAll(current.keySet());
        List<Change> changes = new ArrayList<>();
        for (UUID playerId : players) {
            Presence from = previous.get(playerId);
            Presence to = current.get(playerId);
            if (sameRoom(from, to)) continue;
            changes.add(new Change(playerId, from, to));
        }
        return List.copyOf(changes);
    }

    private static boolean sameRoom(Presence first, Presence second) {
        return first == null ? second == null : second != null && first.roomIndex == second.roomIndex;
    }

    public record Presence(long roomIndex, String playerName) {
        public Presence {
            if (roomIndex < 0 || playerName == null || playerName.isBlank()) {
                throw new IllegalArgumentException("Visitor presence is invalid.");
            }
        }
    }

    public record Change(UUID playerId, Presence from, Presence to) {
    }
}
