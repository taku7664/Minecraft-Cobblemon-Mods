package jbro.cobblemon.bettermusic.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BattleMusicContentProvidersTest {
    @Test
    void resolvesTheFirstRegisteredProviderAndRejectsDuplicateProviderIds() {
        var providers = BattleMusicContentProviders.create();
        UUID battleId = UUID.randomUUID();

        assertEquals(
            BattleMusicContentProviders.RegistrationStatus.REGISTERED,
            providers.register("example:first", ignored -> Optional.of("example:tower"))
        );
        assertEquals(
            BattleMusicContentProviders.RegistrationStatus.REGISTERED,
            providers.register("example:second", ignored -> Optional.of("example:factory"))
        );
        assertEquals(Optional.of("example:tower"), providers.resolve(battleId));
        assertEquals(
            BattleMusicContentProviders.RegistrationStatus.DUPLICATE_PROVIDER_ID,
            providers.register("example:first", ignored -> Optional.empty())
        );
        assertThrows(IllegalArgumentException.class, () ->
            providers.register("Not Namespaced", ignored -> Optional.empty())
        );
    }

    @Test
    void skipsBrokenProvidersAndContinuesWithTheNextRegisteredProvider() {
        var providers = BattleMusicContentProviders.create();
        UUID battleId = UUID.randomUUID();

        providers.register("example:throws", ignored -> {
            throw new IllegalStateException("broken optional integration");
        });
        providers.register("example:null_result", ignored -> null);
        providers.register("example:invalid_id", ignored -> Optional.of("Not Namespaced"));
        providers.register("example:working", ignored -> Optional.of("example:tower"));

        assertEquals(Optional.of("example:tower"), providers.resolve(battleId));
    }
}
