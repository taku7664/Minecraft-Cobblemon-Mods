package jbro.cobblemon.bettermusic.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Where content mods register a {@link BattleMusicContentProvider}. The first provider that knows a battle wins. */
public final class BattleMusicContentProviders {
    private static final BattleMusicContentProviders GLOBAL = new BattleMusicContentProviders();

    private final MusicProviderRegistry<BattleMusicContentProvider> registry = new MusicProviderRegistry<>("Battle");

    private BattleMusicContentProviders() {
    }

    public static BattleMusicContentProviders global() {
        return GLOBAL;
    }

    public static BattleMusicContentProviders create() {
        return new BattleMusicContentProviders();
    }

    public RegistrationStatus register(String providerId, BattleMusicContentProvider provider) {
        return registry.register(providerId, provider)
            ? RegistrationStatus.REGISTERED
            : RegistrationStatus.DUPLICATE_PROVIDER_ID;
    }

    /** The battle's content keys, most specific first, from the first provider that knows it. */
    public List<String> resolveKeys(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        return registry.firstKeys(provider -> provider.contentKeys(battleId));
    }

    /** The battle's most general content key, from the first provider that knows it. */
    public Optional<String> resolve(UUID battleId) {
        List<String> keys = resolveKeys(battleId);
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.getLast());
    }

    /** Every content key the providers can return. */
    public Set<String> knownKeys() {
        return registry.knownKeys(BattleMusicContentProvider::knownContentKeys);
    }

    public enum RegistrationStatus {
        REGISTERED,
        DUPLICATE_PROVIDER_ID
    }
}
