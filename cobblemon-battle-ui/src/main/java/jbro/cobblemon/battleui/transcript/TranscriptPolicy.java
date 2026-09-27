package jbro.cobblemon.battleui.transcript;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/** Locale-independent grouping of the stored, ordered public battle messages. */
public final class TranscriptPolicy {
    public enum Kind { TURN, ACTION, RESULT, SYSTEM, SPEAKER }
    public record Event<T, S>(int turn, String key, S speaker, T value) {}
    public record Group<T, S>(int turn, Kind kind, S speaker, List<T> values) {}
    private static final String PREFIX = "cobblemon.battle.";
    private static final Set<String> RESULTS = Set.of("superEffective", "superEffective_spread",
            "resisted", "resisted_spread", "immune", "missed", "crit", "crit_spread",
            "fail", "ohko", "hit_count", "hit_count_singular", "notarget", "fainted", "damage");

    public static Kind kind(String key) {
        if (key != null && key.startsWith("cobblemon.status.") && key.endsWith(".apply")) return Kind.RESULT;
        if (key == null || !key.startsWith(PREFIX)) return Kind.SYSTEM;
        String name = key.substring(PREFIX.length());
        if (name.equals("turn")) return Kind.TURN;
        if (name.equals("used_move") || name.equals("used_move_on")) return Kind.ACTION;
        // Residual damage, field/weather, items and unknown extensions deliberately break
        // causal grouping. They must never borrow the previous attacker's portrait.
        if (RESULTS.contains(name) || name.startsWith("status.")
                || name.startsWith("boost.") || name.startsWith("unboost.")) return Kind.RESULT;
        if (name.startsWith("cant.") || name.startsWith("prepare.")
                || name.startsWith("switch.") || name.startsWith("withdraw.")
                || name.equals("dragged_out")) return Kind.SPEAKER;
        return Kind.SYSTEM;
    }

    public static <T, S> List<Group<T, S>> group(List<Event<T, S>> events) {
        List<Group<T, S>> groups = new ArrayList<>();
        for (Event<T, S> event : events) {
            Kind kind = kind(event.key());
            Group<T, S> previous = groups.isEmpty() ? null : groups.getLast();
            if (kind == Kind.RESULT && previous != null && previous.kind() == Kind.ACTION
                    && previous.speaker() != null && previous.turn() == event.turn()) {
                List<T> values = new ArrayList<>(previous.values());
                values.add(event.value());
                groups.set(groups.size() - 1, new Group<>(previous.turn(), previous.kind(), previous.speaker(), List.copyOf(values)));
            } else {
                S speaker = kind == Kind.ACTION || kind == Kind.SPEAKER ? event.speaker() : null;
                groups.add(new Group<>(event.turn(), kind, speaker, List.of(event.value())));
            }
        }
        return List.copyOf(groups);
    }
}
