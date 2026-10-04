/*
 * Decompiled with CFR 0.152.
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleActionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BattleActionEvent {
    private final List<UUID> entityUuids;
    private final BattleActionKind kind;
    private final long timestampMs;
    private final long durationMs;

    public BattleActionEvent(UUID entityUuid, BattleActionKind kind, long timestampMs) {
        this(entityUuid, kind, timestampMs, 0L);
    }

    public BattleActionEvent(UUID entityUuid, BattleActionKind kind, long timestampMs, long durationMs) {
        this(entityUuid == null ? List.of() : List.of(entityUuid), kind, timestampMs, durationMs);
    }

    public BattleActionEvent(List<UUID> entityUuids, BattleActionKind kind, long timestampMs, long durationMs) {
        this.entityUuids = BattleActionEvent.compact(entityUuids);
        this.kind = kind;
        this.timestampMs = timestampMs;
        this.durationMs = durationMs;
    }

    public UUID entityUuid() {
        return this.entityUuids.isEmpty() ? null : this.entityUuids.get(0);
    }

    public List<UUID> entityUuids() {
        return this.entityUuids;
    }

    public BattleActionKind kind() {
        return this.kind;
    }

    public long timestampMs() {
        return this.timestampMs;
    }

    public long durationMs() {
        return this.durationMs;
    }

    private static List<UUID> compact(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        ArrayList<UUID> compacted = new ArrayList<UUID>();
        for (UUID uuid : uuids) {
            if (uuid == null || compacted.contains(uuid)) continue;
            compacted.add(uuid);
        }
        return List.copyOf(compacted);
    }
}
