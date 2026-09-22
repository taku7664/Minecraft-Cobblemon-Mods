package jbro.cobblemon.battleui.extended.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Tracks occupants by stable battle slot (PNX) and reports only changed slots. */
public final class ActiveSlotTracker<T> {
    private Map<String, T> previous = Map.of();

    public List<SlotChange<T>> update(Map<String, T> current) {
        Set<String> slots = new LinkedHashSet<>(previous.keySet());
        slots.addAll(current.keySet());

        List<SlotChange<T>> changes = new ArrayList<>();
        for (String slot : slots) {
            T outgoing = previous.get(slot);
            T incoming = current.get(slot);
            if (!Objects.equals(outgoing, incoming)) {
                changes.add(new SlotChange<>(slot, outgoing, incoming));
            }
        }

        previous = new LinkedHashMap<>(current);
        return changes;
    }

    public void clear() {
        previous = Map.of();
    }

    public record SlotChange<T>(String slot, T outgoing, T incoming) {
    }
}
