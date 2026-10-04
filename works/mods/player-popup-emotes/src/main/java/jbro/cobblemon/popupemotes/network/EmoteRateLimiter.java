package jbro.cobblemon.popupemotes.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EmoteRateLimiter {
    public static final long DEFAULT_INTERVAL_NANOS = 500_000_000L;

    private final long intervalNanos;
    private final Map<UUID, Long> nextAllowedAt = new HashMap<>();

    public EmoteRateLimiter() {
        this(DEFAULT_INTERVAL_NANOS);
    }

    EmoteRateLimiter(long intervalNanos) {
        if (intervalNanos < 0L) {
            throw new IllegalArgumentException("intervalNanos must not be negative");
        }
        this.intervalNanos = intervalNanos;
    }

    public boolean tryAcquire(UUID playerId, long nowNanos) {
        long next = nextAllowedAt.getOrDefault(playerId, Long.MIN_VALUE);
        if (nowNanos < next) {
            return false;
        }
        nextAllowedAt.put(playerId, saturatedAdd(nowNanos, intervalNanos));
        return true;
    }

    public void remove(UUID playerId) {
        nextAllowedAt.remove(playerId);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
