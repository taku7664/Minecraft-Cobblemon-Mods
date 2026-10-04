package jbro.minecraft.roundingblock.client.render;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Small lock-free-on-hit cache that keeps accepting recent neighborhood
 * patterns after reaching its bound and coalesces concurrent misses.
 */
final class BoundedLoadingCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Entry<V>> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Token<K>> insertionOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();

    BoundedLoadingCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Entry<V> cached = values.get(key);
        if (cached != null) {
            return cached.value();
        }

        CompletableFuture<V> created = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            cached = values.get(key);
            if (cached != null) {
                created.complete(cached.value());
                return cached.value();
            }

            V loaded = Objects.requireNonNull(loader.apply(key), "loader returned null");
            long loadedSequence = sequence.incrementAndGet();
            Entry<V> inserted = new Entry<>(loaded, loadedSequence);
            Entry<V> raced = values.putIfAbsent(key, inserted);
            V result;
            if (raced == null) {
                insertionOrder.add(new Token<>(key, loadedSequence));
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
            inFlight.remove(key, created);
        }
    }

    int size() {
        return values.size();
    }

    private void evictOverflow() {
        while (values.size() > capacity) {
            Token<K> oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            Entry<V> entry = values.get(oldest.key());
            if (entry != null && entry.sequence() == oldest.sequence()) {
                values.remove(oldest.key(), entry);
            }
        }
    }

    private record Entry<V>(V value, long sequence) {
    }

    private record Token<K>(K key, long sequence) {
    }
}
