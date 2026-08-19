package jbro.cobblemon.bettermusic.field;

import java.util.Objects;
import java.util.Set;

public record FieldMusicContext(
    String dimensionId,
    String biomeId,
    Set<String> biomeTags,
    boolean underground
) {
    public FieldMusicContext {
        dimensionId = requireText(dimensionId, "dimensionId");
        biomeId = requireText(biomeId, "biomeId");
        biomeTags = Set.copyOf(Objects.requireNonNull(biomeTags, "biomeTags"));
    }

    String biomePath() {
        int separator = biomeId.indexOf(':');
        return separator >= 0 ? biomeId.substring(separator + 1) : biomeId;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
