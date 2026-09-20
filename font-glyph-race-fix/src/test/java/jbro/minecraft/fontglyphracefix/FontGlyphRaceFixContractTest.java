package jbro.minecraft.fontglyphracefix.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

final class FontGlyphRaceFixContractTest {
    @Test
    void metadataLoadsOnlyOnTheClientAndRegistersTheRequiredMixinSet() throws Exception {
        String metadata;
        try (var stream = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
            assertNotNull(stream, "fabric.mod.json must exist");
            metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(metadata.contains("\"id\": \"font_glyph_race_fix\""));
        assertTrue(metadata.contains("\"environment\": \"client\""));
        assertTrue(metadata.contains("\"font_glyph_race_fix.client.mixins.json\""));

        String mixins;
        try (var stream = getClass().getClassLoader().getResourceAsStream("font_glyph_race_fix.client.mixins.json")) {
            assertNotNull(stream, "client mixin config must exist");
            mixins = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(mixins.contains("\"TrueTypeGlyphProviderMixin\""));
        assertTrue(mixins.contains("\"TrueTypeGlyphUploadMixin\""));
    }

    @Test
    void sharedFontLockSerializesConcurrentGlyphOperations() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(12)) {
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    FontGlyphLock.call(() -> {
                        int now = active.incrementAndGet();
                        maximum.accumulateAndGet(now, Math::max);
                        try {
                            LockSupport.parkNanos(10_000_000L);
                        } finally {
                            active.decrementAndGet();
                        }
                        return null;
                    });
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertEquals(1, maximum.get(), "FreeType operations must never overlap");
    }
}
