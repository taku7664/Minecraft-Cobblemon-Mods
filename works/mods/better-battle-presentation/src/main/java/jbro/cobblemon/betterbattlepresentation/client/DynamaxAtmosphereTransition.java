package jbro.cobblemon.betterbattlepresentation.client;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DynamaxAtmosphereTransition {
    static final long DEFAULT_DURATION_NANOS = 800_000_000L;

    private final long durationNanos;
    private final Set<UUID> activeBattleIds = new HashSet<>();
    private float transitionStartStrength;
    private float transitionTargetStrength;
    private long transitionStartedAt;

    DynamaxAtmosphereTransition() {
        this(DEFAULT_DURATION_NANOS);
    }

    DynamaxAtmosphereTransition(long durationNanos) {
        if (durationNanos <= 0L) {
            throw new IllegalArgumentException("durationNanos must be positive");
        }
        this.durationNanos = durationNanos;
    }

    synchronized void setActive(UUID battleId, boolean active, long nowNanos) {
        Objects.requireNonNull(battleId, "battleId");
        boolean membershipChanged = active ? activeBattleIds.add(battleId) : activeBattleIds.remove(battleId);
        if (!membershipChanged) {
            return;
        }

        float nextTarget = activeBattleIds.isEmpty() ? 0.0F : 1.0F;
        if (Float.compare(nextTarget, transitionTargetStrength) == 0) {
            return;
        }

        transitionStartStrength = strengthAt(nowNanos);
        transitionTargetStrength = nextTarget;
        transitionStartedAt = nowNanos;
    }

    synchronized float strength(long nowNanos) {
        return strengthAt(nowNanos);
    }

    synchronized boolean isActive() {
        return !activeBattleIds.isEmpty();
    }

    synchronized void clear() {
        activeBattleIds.clear();
        transitionStartStrength = 0.0F;
        transitionTargetStrength = 0.0F;
        transitionStartedAt = 0L;
    }

    private float strengthAt(long nowNanos) {
        long elapsed = Math.max(0L, nowNanos - transitionStartedAt);
        float linearProgress = Math.min(1.0F, (float) elapsed / (float) durationNanos);
        float easedProgress = linearProgress * linearProgress * (3.0F - 2.0F * linearProgress);
        float strength = transitionStartStrength
            + (transitionTargetStrength - transitionStartStrength) * easedProgress;
        return Math.clamp(strength, 0.0F, 1.0F);
    }
}
