package jbro.cobblemon.bettermusic.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BattleMusicContentProviders {
    private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final BattleMusicContentProviders GLOBAL = new BattleMusicContentProviders();

    private final Map<String, BattleMusicContentProvider> providers = new LinkedHashMap<>();

    private BattleMusicContentProviders() {
    }

    public static BattleMusicContentProviders global() {
        return GLOBAL;
    }

    public static BattleMusicContentProviders create() {
        return new BattleMusicContentProviders();
    }

    public synchronized RegistrationStatus register(String providerId, BattleMusicContentProvider provider) {
        requireNamespacedId(providerId, "providerId");
        Objects.requireNonNull(provider, "provider");
        if (providers.containsKey(providerId)) {
            return RegistrationStatus.DUPLICATE_PROVIDER_ID;
        }
        providers.put(providerId, provider);
        return RegistrationStatus.REGISTERED;
    }

    public synchronized Optional<String> resolve(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        for (BattleMusicContentProvider provider : providers.values()) {
            try {
                Optional<String> candidate = provider.contentId(battleId);
                if (candidate != null && candidate.isPresent() && isNamespacedId(candidate.orElseThrow())) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
                // Optional integrations must not take down the base music resolver.
            }
        }
        return Optional.empty();
    }

    private static boolean isNamespacedId(String value) {
        return value != null && NAMESPACED_ID.matcher(value).matches();
    }

    private static void requireNamespacedId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!NAMESPACED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase namespaced ID");
        }
    }

    public enum RegistrationStatus {
        REGISTERED,
        DUPLICATE_PROVIDER_ID
    }
}
