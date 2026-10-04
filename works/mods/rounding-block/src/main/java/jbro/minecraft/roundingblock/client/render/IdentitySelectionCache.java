package jbro.minecraft.roundingblock.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded cache keyed by the identity and order of objects in several lists. */
final class IdentitySelectionCache<T, V> {
    private final int capacity;
    private volatile List<Entry<V>> entries = List.of();

    IdentitySelectionCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    V find(List<? extends T>[] groups) {
        Objects.requireNonNull(groups, "groups");
        for (Entry<V> entry : entries) {
            if (entry.matches(groups)) {
                return entry.value();
            }
        }
        return null;
    }

    synchronized V putIfAbsent(List<? extends T>[] groups, V value) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(value, "value");
        V existing = find(groups);
        if (existing != null) {
            return existing;
        }
        List<Entry<V>> updated = new ArrayList<>(Math.min(capacity, entries.size() + 1));
        int firstKept = entries.size() >= capacity ? entries.size() - capacity + 1 : 0;
        updated.addAll(entries.subList(firstKept, entries.size()));
        updated.add(Entry.snapshot(groups, value));
        entries = List.copyOf(updated);
        return value;
    }

    int size() {
        return entries.size();
    }

    private record Entry<V>(Object[][] groups, V value) {
        private static <T, V> Entry<V> snapshot(List<? extends T>[] groups, V value) {
            Object[][] snapshot = new Object[groups.length][];
            for (int group = 0; group < groups.length; group++) {
                snapshot[group] = groups[group].toArray();
            }
            return new Entry<>(snapshot, value);
        }

        private <T> boolean matches(List<? extends T>[] candidate) {
            if (groups.length != candidate.length) {
                return false;
            }
            for (int group = 0; group < groups.length; group++) {
                Object[] expected = groups[group];
                List<? extends T> actual = candidate[group];
                if (expected.length != actual.size()) {
                    return false;
                }
                for (int index = 0; index < expected.length; index++) {
                    if (expected[index] != actual.get(index)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
