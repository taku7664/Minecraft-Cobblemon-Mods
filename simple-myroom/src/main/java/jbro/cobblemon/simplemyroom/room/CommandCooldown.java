package jbro.cobblemon.simplemyroom.room;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CommandCooldown {
    private final Map<UUID, Long> availableAt = new HashMap<>();

    public Result tryAcquire(UUID playerId, long nowMillis, long durationMillis) {
        long readyAt = availableAt.getOrDefault(playerId, 0L);
        if (nowMillis < readyAt) {
            return new Result(false, readyAt - nowMillis);
        }
        availableAt.put(playerId, Math.addExact(nowMillis, Math.max(0L, durationMillis)));
        return new Result(true, 0L);
    }

    public record Result(boolean allowed, long remainingMillis) {
    }
}
