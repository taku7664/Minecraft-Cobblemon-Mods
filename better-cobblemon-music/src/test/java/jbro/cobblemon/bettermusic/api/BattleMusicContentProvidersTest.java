package jbro.cobblemon.bettermusic.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    void resolvesContentKeysMostSpecificFirstAndListsEveryKnownKey() {
        var providers = BattleMusicContentProviders.create();
        UUID battleId = UUID.randomUUID();
        providers.register("example:plain", ignored -> Optional.empty());
        providers.register("example:staged", new BattleMusicContentProvider() {
            @Override
            public Optional<String> contentId(UUID ignored) {
                return Optional.of("example:league");
            }

            @Override
            public List<String> contentKeys(UUID ignored) {
                return List.of("example:league/champion", "example:league");
            }

            @Override
            public Set<String> knownContentKeys() {
                return Set.of("example:league/champion", "Not Namespaced");
            }
        });

        assertEquals(List.of("example:league/champion", "example:league"), providers.resolveKeys(battleId));
        assertEquals(Optional.of("example:league"), providers.resolve(battleId));
        assertEquals(Set.of("example:league/champion"), providers.knownKeys());
    }

    @Test
    void screenProvidersAnswerOnlyWhileTheirScreenIsOpen() {
        var providers = ScreenMusicProviders.create();
        var open = new java.util.concurrent.atomic.AtomicBoolean();
        providers.register("example:closed", List::of);
        providers.register("example:broken", () -> {
            throw new IllegalStateException("broken optional integration");
        });
        providers.register("example:hub", () -> open.get() ? List.of("example:hub/shop", "example:hub") : List.of());

        assertEquals(List.of(), providers.resolveKeys());
        open.set(true);
        assertEquals(List.of("example:hub/shop", "example:hub"), providers.resolveKeys());
        assertEquals(
            BattleMusicContentProviders.RegistrationStatus.DUPLICATE_PROVIDER_ID,
            providers.register("example:hub", List::of)
        );
    }

    @Test
    void skipsBrokenProvidersAndContinuesWithTheNextRegisteredProvider() {
        var providers = BattleMusicContentProviders.create();
        UUID battleId = UUID.randomUUID();

        providers.register("example:throws", ignored -> {
            throw new IllegalStateException("broken optional integration");
        });
        providers.register("example:linkage_error", ignored -> {
            throw new NoSuchMethodError("binary-incompatible optional integration");
        });
        providers.register("example:null_result", ignored -> null);
        providers.register("example:invalid_id", ignored -> Optional.of("Not Namespaced"));
        providers.register("example:working", ignored -> Optional.of("example:tower"));

        assertEquals(Optional.of("example:tower"), providers.resolve(battleId));
    }

    @Test
    void providerCallbacksRunOutsideTheRegistryLockAndUseAStableSnapshot() {
        var providers = BattleMusicContentProviders.create();
        UUID battleId = UUID.randomUUID();
        providers.register("example:registers_late", ignored -> {
            providers.register("example:late", lateIgnored -> Optional.of("example:late"));
            return Optional.empty();
        });
        providers.register("example:working", ignored -> Optional.of("example:working"));

        assertEquals(Optional.of("example:working"), providers.resolve(battleId));
        assertEquals(Optional.of("example:working"), providers.resolve(battleId));
    }
}
