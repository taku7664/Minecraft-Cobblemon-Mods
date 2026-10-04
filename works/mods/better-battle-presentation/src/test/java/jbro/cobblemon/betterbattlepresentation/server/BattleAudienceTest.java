package jbro.cobblemon.betterbattlepresentation.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BattleAudienceTest {
    @Test
    void containsOnlyParticipantsAndRegisteredSpectatorsWithoutDuplicates() {
        var participant = UUID.randomUUID();
        var spectator = UUID.randomUUID();
        var nearbyPlayer = UUID.randomUUID();

        var audience = BattleAudience.resolve(
            List.of(participant),
            List.of(participant, spectator)
        );

        assertEquals(List.of(participant, spectator), audience.stream().toList());
        assertFalse(audience.contains(nearbyPlayer));
    }
}
