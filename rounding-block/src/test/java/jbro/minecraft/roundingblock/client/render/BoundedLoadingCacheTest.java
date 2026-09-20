package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedLoadingCacheTest {
    @Test
    void evictsOldEntriesInsteadOfPermanentlyFreezingTheFirstKeys() {
        BoundedLoadingCache<Integer, Integer> cache = new BoundedLoadingCache<>(2);
        AtomicInteger loads = new AtomicInteger();

        assertEquals(10, cache.get(1, key -> loads.incrementAndGet() * 10));
        assertEquals(20, cache.get(2, key -> loads.incrementAndGet() * 10));
        assertEquals(30, cache.get(3, key -> loads.incrementAndGet() * 10));
        assertEquals(30, cache.get(3, key -> loads.incrementAndGet() * 10));
        assertEquals(40, cache.get(1, key -> loads.incrementAndGet() * 10));
        assertEquals(2, cache.size());
        assertEquals(4, loads.get());
    }

    @Test
    void concurrentMissesGenerateOneValue() throws Exception {
        BoundedLoadingCache<Integer, Integer> cache = new BoundedLoadingCache<>(2);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cache.get(7, key -> {
                loads.incrementAndGet();
                loaderEntered.countDown();
                try {
                    if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("loader release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 70;
            }));
            if (!loaderEntered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("loader did not start");
            }
            var second = executor.submit(() -> cache.get(7, key -> {
                loads.incrementAndGet();
                return 700;
            }));
            releaseLoader.countDown();

            assertEquals(70, first.get(5, TimeUnit.SECONDS));
            assertEquals(70, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
        }
    }
}
