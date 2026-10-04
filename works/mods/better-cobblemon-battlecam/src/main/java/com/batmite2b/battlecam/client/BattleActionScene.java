/*
 * Decompiled with CFR 0.152.
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleActionEvent;
import com.batmite2b.battlecam.client.BattleActionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BattleActionScene {
    private final BattleActionKind kind;
    private final ArrayList<UUID> entityUuids;
    private final long startedAtMs;
    private long durationMs;

    private BattleActionScene(BattleActionKind kind, List<UUID> entityUuids, long startedAtMs, long durationMs) {
        this.kind = kind;
        this.entityUuids = new ArrayList<UUID>(entityUuids);
        this.startedAtMs = startedAtMs;
        this.durationMs = durationMs;
    }

    public static BattleActionScene start(BattleActionEvent event) {
        long l;
        if (event.durationMs() > 0L) {
            l = event.durationMs();
        } else {
            switch (event.kind()) {
                default: {
                    throw new MatchException(null, null);
                }
                case MOVE: {
                    l = 2200L;
                    break;
                }
                case DAMAGE: {
                    l = 1900L;
                    break;
                }
                case FAINT: {
                    l = 2600L;
                    break;
                }
                case SWITCH: {
                    l = 2600L;
                }
            }
        }
        long duration = l;
        return new BattleActionScene(event.kind(), event.entityUuids(), System.currentTimeMillis(), duration);
    }

    public BattleActionKind kind() {
        return this.kind;
    }

    public UUID entityUuid() {
        return this.entityUuids.isEmpty() ? null : this.entityUuids.get(0);
    }

    public List<UUID> entityUuids() {
        return List.copyOf(this.entityUuids);
    }

    public boolean canExtend(BattleActionEvent event) {
        if (event == null || event.kind() != this.kind || event.entityUuid() == null) {
            return false;
        }
        if (this.kind == BattleActionKind.DAMAGE) {
            return true;
        }
        return event.entityUuid().equals(this.entityUuid());
    }

    public void extend(BattleActionEvent event, long nowMs) {
        for (UUID uuid : event.entityUuids()) {
            if (this.entityUuids.contains(uuid)) continue;
            this.entityUuids.add(uuid);
        }
        long requestedDuration = event.durationMs() > 0L ? event.durationMs() : this.durationMs;
        long requestedEnd = nowMs - this.startedAtMs + requestedDuration;
        this.durationMs = Math.max(this.durationMs, Math.min(requestedEnd, this.maxDurationMs()));
    }

    private long maxDurationMs() {
        return switch (this.kind) {
            default -> throw new MatchException(null, null);
            case BattleActionKind.MOVE -> 5200L;
            case BattleActionKind.DAMAGE -> 3400L;
            case BattleActionKind.FAINT -> 3600L;
            case BattleActionKind.SWITCH -> 3600L;
        };
    }

    public float progress(long nowMs) {
        if (this.durationMs <= 0L) {
            return 1.0f;
        }
        float t = (float)(nowMs - this.startedAtMs) / (float)this.durationMs;
        if (t < 0.0f) {
            return 0.0f;
        }
        if (t > 1.0f) {
            return 1.0f;
        }
        return t;
    }

    public boolean isFinished(long nowMs) {
        return nowMs - this.startedAtMs >= this.durationMs;
    }
}
