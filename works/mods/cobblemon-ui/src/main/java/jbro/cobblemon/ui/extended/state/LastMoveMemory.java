package jbro.cobblemon.ui.extended.state;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The move each Pokemon last chose, so its move menu opens on that move next turn: by the move's id, since the menu
 * can list moves in another order (a gimmick swaps in its own tiles) and a disabled move cannot be landed on.
 */
public final class LastMoveMemory {
    /** More than any party, across a few battles; the oldest is dropped first. */
    static final int CAPACITY = 64;

    private static final Map<UUID, String> MOVES = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
            return size() > CAPACITY;
        }
    };

    private LastMoveMemory() {
    }

    public static void record(UUID pokemon, String moveId) {
        if (pokemon == null || moveId == null || moveId.isEmpty()) {
            return;
        }
        MOVES.put(pokemon, moveId);
    }

    /** Where in [moveIds] that Pokemon's last move is, among the moves it can choose now; -1 when it is not there. */
    public static int indexFor(UUID pokemon, List<String> moveIds, Predicate<Integer> selectable) {
        String last = pokemon == null ? null : MOVES.get(pokemon);
        if (last == null) {
            return -1;
        }
        for (int index = 0; index < moveIds.size(); index++) {
            if (last.equals(moveIds.get(index)) && selectable.test(index)) {
                return index;
            }
        }
        return -1;
    }

    static void clear() {
        MOVES.clear();
    }
}
