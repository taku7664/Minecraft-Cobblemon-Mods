package jbro.minecraft.roundingblock.client.settings;

import java.util.HashMap;
import java.util.Map;

/** Keeps unsaved text, including invalid input, separate from the active config. */
final class RoundingBlockConfigDraft {
    private final Map<String, String> values = new HashMap<>();

    RoundingBlockConfigDraft(RoundingBlockConfig config) {
        reset(config);
    }

    String get(String key) {
        return values.get(key);
    }

    void set(String key, String value) {
        values.put(key, value);
    }

    void reset(RoundingBlockConfig config) {
        values.put("enabled", Boolean.toString(config.enabled()));
        values.put("radius", Double.toString(config.quality().radius()));
        values.put("segments", Integer.toString(config.quality().segments()));
        values.put("fullBlockPlans", Integer.toString(config.cache().fullBlockPlans()));
        values.put("slabPlans", Integer.toString(config.cache().slabPlans()));
        values.put("complexShapePlans", Integer.toString(config.cache().complexShapePlans()));
        values.put("fluidContactPlans", Integer.toString(config.cache().fluidContactPlans()));
        values.put("weightedModelVariants", Integer.toString(config.cache().weightedModelVariants()));
        values.put("diagnosticLogging", Boolean.toString(config.debug().diagnosticLogging()));
    }

    RoundingBlockConfig build() {
        double radius = Double.parseDouble(values.get("radius"));
        if (!Double.isFinite(radius) || radius < RoundingBlockConfig.MIN_RADIUS || radius > RoundingBlockConfig.MAX_RADIUS) {
            throw new IllegalArgumentException("radius");
        }
        return new RoundingBlockConfig(Boolean.parseBoolean(values.get("enabled")),
            new RoundingBlockConfig.Quality(radius, integer("segments", RoundingBlockConfig.MIN_SEGMENTS, RoundingBlockConfig.MAX_SEGMENTS)),
            new RoundingBlockConfig.Cache(meshCount("fullBlockPlans"), meshCount("slabPlans"), meshCount("complexShapePlans"),
                meshCount("fluidContactPlans"), integer("weightedModelVariants", RoundingBlockConfig.MIN_APPEARANCE_CACHE, RoundingBlockConfig.MAX_APPEARANCE_CACHE)),
            new RoundingBlockConfig.Debug(Boolean.parseBoolean(values.get("diagnosticLogging"))));
    }

    private int meshCount(String key) {
        return integer(key, RoundingBlockConfig.MIN_MESH_CACHE, RoundingBlockConfig.MAX_MESH_CACHE);
    }

    private int integer(String key, int min, int max) {
        int value = Integer.parseInt(values.get(key));
        if (value < min || value > max) throw new IllegalArgumentException(key);
        return value;
    }

}
