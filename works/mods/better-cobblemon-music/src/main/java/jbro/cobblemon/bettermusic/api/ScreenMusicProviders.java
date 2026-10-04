package jbro.cobblemon.bettermusic.api;

import java.util.List;
import java.util.Set;

/** Where mods register a {@link ScreenMusicProvider}. The first provider with an open screen wins. */
public final class ScreenMusicProviders {
    private static final ScreenMusicProviders GLOBAL = new ScreenMusicProviders();

    private final MusicProviderRegistry<ScreenMusicProvider> registry = new MusicProviderRegistry<>("Screen");

    private ScreenMusicProviders() {
    }

    public static ScreenMusicProviders global() {
        return GLOBAL;
    }

    public static ScreenMusicProviders create() {
        return new ScreenMusicProviders();
    }

    public BattleMusicContentProviders.RegistrationStatus register(String providerId, ScreenMusicProvider provider) {
        return registry.register(providerId, provider)
            ? BattleMusicContentProviders.RegistrationStatus.REGISTERED
            : BattleMusicContentProviders.RegistrationStatus.DUPLICATE_PROVIDER_ID;
    }

    /** The open screen's keys, most specific first, or an empty list when no provider's screen is open. */
    public List<String> resolveKeys() {
        return registry.firstKeys(ScreenMusicProvider::screenKeys);
    }

    /** Every screen key the providers can return. */
    public Set<String> knownKeys() {
        return registry.knownKeys(ScreenMusicProvider::knownScreenKeys);
    }
}
