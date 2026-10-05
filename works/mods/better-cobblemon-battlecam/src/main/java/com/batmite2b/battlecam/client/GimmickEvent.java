package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.GimmickKind;
import java.util.UUID;

public record GimmickEvent(UUID entityUuid, GimmickKind kind, long timestampMs) {
}
