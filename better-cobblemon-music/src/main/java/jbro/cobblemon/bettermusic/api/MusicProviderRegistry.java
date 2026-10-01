package jbro.cobblemon.bettermusic.api;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Providers in registration order; a broken provider is reported once and skipped. */
final class MusicProviderRegistry<P> {
    static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Logger LOGGER = LoggerFactory.getLogger("better_cobblemon_music");

    private final String kind;
    private final Map<String, P> providers = new LinkedHashMap<>();
    private final Set<String> reportedFailures = new HashSet<>();

    MusicProviderRegistry(String kind) {
        this.kind = kind;
    }

    synchronized boolean register(String providerId, P provider) {
        requireNamespacedId(providerId, "providerId");
        Objects.requireNonNull(provider, "provider");
        if (providers.containsKey(providerId)) {
            return false;
        }
        providers.put(providerId, provider);
        return true;
    }

    /** The first provider's valid, non-empty keys; providers run outside the lock on a stable snapshot. */
    List<String> firstKeys(Function<P, List<String>> keys) {
        for (Map.Entry<String, P> entry : snapshot()) {
            try {
                List<String> candidate = keys.apply(entry.getValue());
                if (candidate == null || candidate.stream().anyMatch(key -> !isNamespacedId(key))) {
                    reportFailureOnce(entry.getKey(), "returned null or an invalid key", null);
                    continue;
                }
                if (!candidate.isEmpty()) {
                    return List.copyOf(new LinkedHashSet<>(candidate));
                }
            } catch (RuntimeException | LinkageError failure) {
                reportFailureOnce(entry.getKey(), "failed while resolving music keys", failure);
            }
        }
        return List.of();
    }

    /** Every provider's valid known keys. */
    Set<String> knownKeys(Function<P, Set<String>> keys) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, P> entry : snapshot()) {
            try {
                Set<String> known = keys.apply(entry.getValue());
                if (known != null) {
                    known.stream().filter(MusicProviderRegistry::isNamespacedId).forEach(result::add);
                }
            } catch (RuntimeException | LinkageError failure) {
                reportFailureOnce(entry.getKey(), "failed while listing music keys", failure);
            }
        }
        return Set.copyOf(result);
    }

    private synchronized List<Map.Entry<String, P>> snapshot() {
        return providers.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
    }

    private void reportFailureOnce(String providerId, String message, Throwable failure) {
        synchronized (this) {
            if (!reportedFailures.add(providerId)) {
                return;
            }
        }
        if (failure == null) {
            LOGGER.warn("{} music provider '{}' {}", kind, providerId, message);
        } else {
            LOGGER.warn("{} music provider '{}' {}: {}", kind, providerId, message, failure.toString());
            LOGGER.debug("{} music provider '{}' failure details", kind, providerId, failure);
        }
    }

    static boolean isNamespacedId(String value) {
        return value != null && NAMESPACED_ID.matcher(value).matches();
    }

    static void requireNamespacedId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!NAMESPACED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase namespaced ID");
        }
    }
}
