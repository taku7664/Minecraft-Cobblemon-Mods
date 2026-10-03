package jbro.cobblemon.bettermusic.field;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An ordered reference to an existing mapping, not a second copy of its playlist. */
public record FieldMusicRule(Kind kind, String key) {
    public FieldMusicRule {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        if (kind == Kind.UNDERGROUND ? !key.isEmpty() : key.isBlank()) {
            throw new IllegalArgumentException("Invalid field rule key");
        }
    }

    public static FieldMusicRule parse(String selector) {
        Objects.requireNonNull(selector, "selector");
        if (selector.equals("underground")) {
            return new FieldMusicRule(Kind.UNDERGROUND, "");
        }
        if (selector.startsWith("biome:")) {
            return new FieldMusicRule(Kind.BIOME, selector.substring(6));
        }
        if (selector.startsWith("path:")) {
            return new FieldMusicRule(Kind.PATH, selector.substring(5));
        }
        throw new IllegalArgumentException("Expected biome:<id or #tag>, path:<contains>, or underground");
    }

    public String selector() {
        return switch (kind) {
            case BIOME -> "biome:" + key;
            case PATH -> "path:" + key;
            case UNDERGROUND -> "underground";
        };
    }

    public boolean matches(FieldMusicContext context) {
        return switch (kind) {
            case BIOME -> key.startsWith("#")
                ? context.biomeTags().contains(key.substring(1)) : context.biomeId().equals(key);
            case PATH -> context.biomePath().contains(key);
            case UNDERGROUND -> context.underground();
        };
    }

    public static List<FieldMusicRule> validate(List<FieldMusicRule> rules, Map<String, ?> biomes,
        Map<String, ?> paths, boolean hasUnderground) {
        var copy = List.copyOf(rules);
        var seen = new java.util.HashSet<FieldMusicRule>();
        for (var rule : copy) {
            if (!seen.add(rule)) {
                throw new IllegalArgumentException("Duplicate field rule: " + rule.selector());
            }
            boolean configured = switch (rule.kind()) {
                case BIOME -> biomes.containsKey(rule.key());
                case PATH -> paths.containsKey(rule.key());
                case UNDERGROUND -> hasUnderground;
            };
            if (!configured) {
                throw new IllegalArgumentException("Field rule references an unconfigured mapping: " + rule.selector());
            }
        }
        return copy;
    }

    public enum Kind { BIOME, PATH, UNDERGROUND }
}
