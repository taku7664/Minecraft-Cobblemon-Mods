/*
 * Decompiled with CFR 0.152.
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.GimmickEvent;
import com.batmite2b.battlecam.client.GimmickKind;
import java.util.UUID;

public final class GimmickScene {
    private final GimmickKind kind;
    private UUID entityUuid;
    private final long startedAtMs;
    private final long visualDurationMs;
    private final long maxDurationMs;
    private long crySeenAtMs = 0L;

    private GimmickScene(GimmickKind kind, UUID entityUuid, long startedAtMs, long visualDurationMs, long maxDurationMs) {
        this.kind = kind;
        this.entityUuid = entityUuid;
        this.startedAtMs = startedAtMs;
        this.visualDurationMs = visualDurationMs;
        this.maxDurationMs = maxDurationMs;
    }

    public static GimmickScene start(GimmickEvent event) {
        long visualDuration = switch (event.kind()) {
            default -> throw new MatchException(null, null);
            case GimmickKind.MEGA -> 6800L;
            case GimmickKind.ULTRA_BURST -> 4200L;
            case GimmickKind.TERA -> 4600L;
            case GimmickKind.DYNAMAX -> 5200L;
            case GimmickKind.FORM_CHANGE -> 2400L;
        };
        long maxDuration = event.kind() == GimmickKind.MEGA ? 11000L : visualDuration;
        return new GimmickScene(event.kind(), event.entityUuid(), System.currentTimeMillis(), visualDuration, maxDuration);
    }

    public GimmickKind kind() {
        return this.kind;
    }

    public UUID entityUuid() {
        return this.entityUuid;
    }

    public long startedAtMs() {
        return this.startedAtMs;
    }

    public boolean canAbsorb(GimmickEvent event, long nowMs) {
        if (event == null) {
            return false;
        }
        if (event.kind() != this.kind) {
            return false;
        }
        if (event.entityUuid() == null) {
            return false;
        }
        return this.entityUuid == null || event.entityUuid().equals(this.entityUuid);
    }

    public void absorb(GimmickEvent event) {
        if (event == null || event.entityUuid() == null) {
            return;
        }
        this.entityUuid = event.entityUuid();
    }

    public void markCrySeen(UUID entityUuid, UUID pokemonUuid, long nowMs) {
        if (this.kind != GimmickKind.MEGA || this.crySeenAtMs > 0L) {
            return;
        }
        if (this.entityUuid == null) {
            this.entityUuid = entityUuid != null ? entityUuid : pokemonUuid;
            this.crySeenAtMs = nowMs;
            return;
        }
        if (this.entityUuid.equals(entityUuid) || this.entityUuid.equals(pokemonUuid)) {
            this.crySeenAtMs = nowMs;
        }
    }

    public float progress(long nowMs) {
        if (this.visualDurationMs <= 0L) {
            return 1.0f;
        }
        float t = (float)(nowMs - this.startedAtMs) / (float)this.visualDurationMs;
        if (t < 0.0f) {
            return 0.0f;
        }
        if (t > 1.0f) {
            return 1.0f;
        }
        return t;
    }

    public float postCryProgress(long nowMs) {
        if (this.kind != GimmickKind.MEGA || this.crySeenAtMs <= 0L) {
            return 0.0f;
        }
        float t = (float)(nowMs - this.crySeenAtMs) / 2400.0f;
        if (t < 0.0f) {
            return 0.0f;
        }
        if (t > 1.0f) {
            return 1.0f;
        }
        return t;
    }

    public boolean isFinished(long nowMs) {
        if (this.kind == GimmickKind.MEGA && this.crySeenAtMs > 0L) {
            return nowMs - this.crySeenAtMs >= 2400L && nowMs - this.startedAtMs >= 3600L;
        }
        return nowMs - this.startedAtMs >= this.maxDurationMs;
    }
}
