package jbro.cobblemon.popupemotes.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EmoteRateLimiterTest {
    @Test
    void limitsEachPlayerIndependentlyAndAllowsBoundaryTime() {
        var limiter = new EmoteRateLimiter(100L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(first, 1_000L));
        assertFalse(limiter.tryAcquire(first, 1_099L));
        assertTrue(limiter.tryAcquire(first, 1_100L));
        assertTrue(limiter.tryAcquire(second, 1_050L));
    }

    @Test
    void removalClearsAPlayersCooldown() {
        var limiter = new EmoteRateLimiter(100L);
        UUID player = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player, 1_000L));

        limiter.remove(player);

        assertTrue(limiter.tryAcquire(player, 1_001L));
    }
}
