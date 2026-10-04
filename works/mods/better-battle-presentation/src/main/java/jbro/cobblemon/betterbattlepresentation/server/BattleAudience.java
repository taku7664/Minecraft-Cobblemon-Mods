package jbro.cobblemon.betterbattlepresentation.server;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class BattleAudience {
    private BattleAudience() {
    }

    static Set<UUID> resolve(Collection<UUID> participantIds, Collection<UUID> spectatorIds) {
        Objects.requireNonNull(participantIds, "participantIds");
        Objects.requireNonNull(spectatorIds, "spectatorIds");

        var audience = new LinkedHashSet<UUID>(participantIds.size() + spectatorIds.size());
        audience.addAll(participantIds);
        audience.addAll(spectatorIds);
        return Collections.unmodifiableSet(audience);
    }
}
