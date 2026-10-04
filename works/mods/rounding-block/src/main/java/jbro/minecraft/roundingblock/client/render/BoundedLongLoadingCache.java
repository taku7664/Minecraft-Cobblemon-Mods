package jbro.minecraft.roundingblock.client.render;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * Concurrent bounded cache whose hit path does not box primitive neighborhood
 * keys. A thread-local lookup key is never inserted into either map.
 */
final class BoundedLongLoadingCache<V> {
    private final int capacity;
    private final ConcurrentHashMap<LongKey, Entry<V>> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LongKey, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Token> insertionOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadLocal<LookupKey> lookupKeys = ThreadLocal.withInitial(LookupKey::new);

    BoundedLongLoadingCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    V get(long key, LongFunction<? extends V> loader) {
        Objects.requireNonNull(loader, "loader");
        LookupKey lookup = lookupKeys.get().set(key);
        Entry<V> cached = values.get(lookup);
        if (cached != null) {
            return cached.value();
        }

        StoredKey stored = new StoredKey(key);
        CompletableFuture<V> created = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(stored, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            cached = values.get(stored);
            if (cached != null) {
                created.complete(cached.value());
                return cached.value();
            }

            V loaded = Objects.requireNonNull(loader.apply(key), "loader returned null");
            long loadedSequence = sequence.incrementAndGet();
            Entry<V> inserted = new Entry<>(loaded, loadedSequence);
            Entry<V> raced = values.putIfAbsent(stored, inserted);
            V result;
            if (raced == null) {
                insertionOrder.add(new Token(stored, loadedSequence));
                evictOverflow();
                result = loaded;
            } else {
                result = raced.value();
            }
            created.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(stored, created);
        }
    }

    int size() {
        return values.size();
    }

    private void evictOverflow() {
        while (values.size() > capacity) {
            Token oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            Entry<V> entry = values.get(oldest.key());
            if (entry != null && entry.sequence() == oldest.sequence()) {
                values.remove(oldest.key(), entry);
            }
        }
    }

    private interface LongKey {
        long value();
    }

    private static final class StoredKey implements LongKey {
        private final long value;

        private StoredKey(long value) {
            this.value = value;
        }

        @Override
        public long value() {
            return value;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LongKey key && value == key.value();
        }
    }

    private static final class LookupKey implements LongKey {
        private long value;

        private LookupKey set(long value) {
            this.value = value;
            return this;
        }

        @Override
        public long value() {
            return value;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LongKey key && value == key.value();
        }
    }

    private record Entry<V>(V value, long sequence) {
    }

    private record Token(StoredKey key, long sequence) {
    }
}
