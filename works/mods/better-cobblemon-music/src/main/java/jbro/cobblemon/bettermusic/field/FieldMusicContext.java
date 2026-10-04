package jbro.cobblemon.bettermusic.field;

import java.util.Objects;
import java.util.Set;

public record FieldMusicContext(
    String dimensionId,
    String biomeId,
    Set<String> biomeTags,
    boolean underground,
    TimeOfDay timeOfDay
) {
    public FieldMusicContext {
        dimensionId = requireText(dimensionId, "dimensionId");
        biomeId = requireText(biomeId, "biomeId");
        biomeTags = Set.copyOf(Objects.requireNonNull(biomeTags, "biomeTags"));
        Objects.requireNonNull(timeOfDay, "timeOfDay");
    }

    public FieldMusicContext(String dimensionId, String biomeId, Set<String> biomeTags, boolean underground) {
        this(dimensionId, biomeId, biomeTags, underground, TimeOfDay.DAY);
    }

    /** Music policy based on the dimension's game clock, independent of weather. */
    public enum TimeOfDay {
        DAY, NIGHT;

        public static TimeOfDay fromWorldTime(long worldTime) {
            long time = Math.floorMod(worldTime, 24000L);
            return time >= 13000L && time < 23000L ? NIGHT : DAY;
        }
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
