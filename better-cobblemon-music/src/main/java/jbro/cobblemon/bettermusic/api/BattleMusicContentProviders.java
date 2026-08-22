package jbro.cobblemon.bettermusic.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BattleMusicContentProviders {
    private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final BattleMusicContentProviders GLOBAL = new BattleMusicContentProviders();
    private static final Logger LOGGER = LoggerFactory.getLogger("better_cobblemon_music");

    private final Map<String, BattleMusicContentProvider> providers = new LinkedHashMap<>();
    private final Set<String> reportedFailures = new HashSet<>();

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

    public Optional<String> resolve(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        for (ProviderRegistration registration : snapshot()) {
            try {
                Optional<String> candidate = registration.provider().contentId(battleId);
                if (candidate != null && candidate.isPresent() && isNamespacedId(candidate.orElseThrow())) {
                    return candidate;
                }
                if (candidate == null || (candidate.isPresent() && !isNamespacedId(candidate.orElseThrow()))) {
                    reportFailureOnce(registration.providerId(), "returned null or an invalid content ID", null);
                }
            } catch (RuntimeException | LinkageError failure) {
                reportFailureOnce(registration.providerId(), "failed while resolving battle content", failure);
            }
        }
        return Optional.empty();
    }

    private synchronized List<ProviderRegistration> snapshot() {
        List<ProviderRegistration> snapshot = new ArrayList<>(providers.size());
        providers.forEach((providerId, provider) ->
            snapshot.add(new ProviderRegistration(providerId, provider))
        );
        return List.copyOf(snapshot);
    }

    private void reportFailureOnce(String providerId, String message, Throwable failure) {
        synchronized (this) {
            if (!reportedFailures.add(providerId)) {
                return;
            }
        }
        if (failure == null) {
            LOGGER.warn("Battle music content provider '{}' {}", providerId, message);
        } else {
            LOGGER.warn(
                "Battle music content provider '{}' {}: {}",
                providerId,
                message,
                failure.toString()
            );
            LOGGER.debug("Battle music content provider '{}' failure details", providerId, failure);
        }
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

    private record ProviderRegistration(String providerId, BattleMusicContentProvider provider) {
    }
}
