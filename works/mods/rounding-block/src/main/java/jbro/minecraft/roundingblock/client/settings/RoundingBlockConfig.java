package jbro.minecraft.roundingblock.client.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable, validated client configuration loaded before render resources are created. */
public record RoundingBlockConfig(boolean enabled, Quality quality, Cache cache, Debug debug) {
    public static final double MIN_RADIUS = 0.015625;
    public static final double MAX_RADIUS = 0.21875;
    public static final int MIN_SEGMENTS = 1;
    public static final int MAX_SEGMENTS = 8;
    public static final int MIN_MESH_CACHE = 16;
    public static final int MAX_MESH_CACHE = 4096;
    public static final int MIN_APPEARANCE_CACHE = 1;
    public static final int MAX_APPEARANCE_CACHE = 256;

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final RoundingBlockConfig DEFAULTS = new RoundingBlockConfig(
        true,
        new Quality(3.0 / 32.0, 3),
        new Cache(256, 256, 256, 256, 32),
        new Debug(true)
    );

    public RoundingBlockConfig {
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(debug, "debug");
    }

    public static RoundingBlockConfig defaults() {
        return DEFAULTS;
    }

    public RoundingBlockConfig withEnabled(boolean value) {
        return new RoundingBlockConfig(value, quality, cache, debug);
    }

    public RoundingBlockConfig withQuality(Quality value) {
        return new RoundingBlockConfig(enabled, value, cache, debug);
    }

    public RoundingBlockConfig withCache(Cache value) {
        return new RoundingBlockConfig(enabled, quality, value, debug);
    }

    public RoundingBlockConfig withDebug(Debug value) {
        return new RoundingBlockConfig(enabled, quality, cache, value);
    }

    public RoundingBlockConfig withRadius(double value) {
        return withQuality(new Quality(value, quality.segments));
    }

    public RoundingBlockConfig withSegments(int value) {
        return withQuality(new Quality(quality.radius, value));
    }

    public RoundingBlockConfig withFullBlockPlans(int value) {
        return withCache(new Cache(
            value, cache.slabPlans, cache.complexShapePlans, cache.fluidContactPlans, cache.weightedModelVariants
        ));
    }

    public RoundingBlockConfig withSlabPlans(int value) {
        return withCache(new Cache(
            cache.fullBlockPlans, value, cache.complexShapePlans, cache.fluidContactPlans, cache.weightedModelVariants
        ));
    }

    public RoundingBlockConfig withComplexShapePlans(int value) {
        return withCache(new Cache(
            cache.fullBlockPlans, cache.slabPlans, value, cache.fluidContactPlans, cache.weightedModelVariants
        ));
    }

    public RoundingBlockConfig withFluidContactPlans(int value) {
        return withCache(new Cache(
            cache.fullBlockPlans, cache.slabPlans, cache.complexShapePlans, value, cache.weightedModelVariants
        ));
    }

    public RoundingBlockConfig withWeightedModelVariants(int value) {
        return withCache(new Cache(
            cache.fullBlockPlans, cache.slabPlans, cache.complexShapePlans, cache.fluidContactPlans, value
        ));
    }

    public RoundingBlockConfig withDiagnosticLogging(boolean value) {
        return withDebug(new Debug(value));
    }

    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Config path has no parent: " + path);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "rounding-block-", ".tmp");
        try {
            Files.writeString(
                temporary,
                PRETTY_GSON.toJson(this) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static RoundingBlockConfig load(Path path, Consumer<String> warningSink) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(warningSink, "warningSink");
        if (Files.notExists(path)) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                    path,
                    PRETTY_GSON.toJson(DEFAULTS) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
                );
                return DEFAULTS;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another initializer won the create race; read its file below.
            } catch (IOException exception) {
                warningSink.accept("Could not create config; using defaults: " + exception.getMessage());
                return DEFAULTS;
            }
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8), warningSink);
        } catch (IOException exception) {
            warningSink.accept("Could not read config; using defaults: " + exception.getMessage());
            return DEFAULTS;
        }
    }

    static RoundingBlockConfig parse(String source, Consumer<String> warningSink) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(warningSink, "warningSink");
        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(source);
            if (!parsed.isJsonObject()) {
                warningSink.accept("Config root must be a JSON object; using defaults");
                return DEFAULTS;
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            warningSink.accept("Malformed config; preserving file and using defaults: " + exception.getMessage());
            return DEFAULTS;
        }

        JsonObject quality = object(root, "quality", warningSink);
        JsonObject cache = object(root, "cache", warningSink);
        JsonObject debug = object(root, "debug", warningSink);
        return new RoundingBlockConfig(
            bool(root, "enabled", DEFAULTS.enabled, warningSink),
            new Quality(
                decimal(quality, "radius", DEFAULTS.quality.radius, MIN_RADIUS, MAX_RADIUS, warningSink),
                integer(quality, "segments", DEFAULTS.quality.segments, MIN_SEGMENTS, MAX_SEGMENTS, warningSink)
            ),
            new Cache(
                integer(cache, "fullBlockPlans", DEFAULTS.cache.fullBlockPlans, MIN_MESH_CACHE, MAX_MESH_CACHE, warningSink),
                integer(cache, "slabPlans", DEFAULTS.cache.slabPlans, MIN_MESH_CACHE, MAX_MESH_CACHE, warningSink),
                integer(cache, "complexShapePlans", DEFAULTS.cache.complexShapePlans, MIN_MESH_CACHE, MAX_MESH_CACHE, warningSink),
                integer(cache, "fluidContactPlans", DEFAULTS.cache.fluidContactPlans, MIN_MESH_CACHE, MAX_MESH_CACHE, warningSink),
                integer(
                    cache,
                    "weightedModelVariants",
                    DEFAULTS.cache.weightedModelVariants,
                    MIN_APPEARANCE_CACHE,
                    MAX_APPEARANCE_CACHE,
                    warningSink
                )
            ),
            new Debug(bool(debug, "diagnosticLogging", DEFAULTS.debug.diagnosticLogging, warningSink))
        );
    }

    private static JsonObject object(JsonObject parent, String name, Consumer<String> warningSink) {
        JsonElement value = parent.get(name);
        if (value == null) {
            return new JsonObject();
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        warningSink.accept(name + " must be a JSON object; using defaults for that section");
        return new JsonObject();
    }

    private static double decimal(
        JsonObject object,
        String name,
        double fallback,
        double minimum,
        double maximum,
        Consumer<String> warningSink
    ) {
        JsonElement value = object.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("not a number");
            }
            double parsed = value.getAsDouble();
            if (Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
        }
        warningSink.accept(name + " must be between " + minimum + " and " + maximum + "; using " + fallback);
        return fallback;
    }

    private static int integer(
        JsonObject object,
        String name,
        int fallback,
        int minimum,
        int maximum,
        Consumer<String> warningSink
    ) {
        JsonElement value = object.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("not a number");
            }
            java.math.BigDecimal parsed = value.getAsBigDecimal().stripTrailingZeros();
            if (parsed.scale() <= 0
                && parsed.compareTo(java.math.BigDecimal.valueOf(minimum)) >= 0
                && parsed.compareTo(java.math.BigDecimal.valueOf(maximum)) <= 0) {
                return parsed.intValueExact();
            }
        } catch (RuntimeException ignored) {
        }
        warningSink.accept(name + " must be an integer between " + minimum + " and " + maximum + "; using " + fallback);
        return fallback;
    }

    private static boolean bool(
        JsonObject object,
        String name,
        boolean fallback,
        Consumer<String> warningSink
    ) {
        JsonElement value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        warningSink.accept(name + " must be true or false; using " + fallback);
        return fallback;
    }

    public record Quality(double radius, int segments) {
    }

    public record Cache(
        int fullBlockPlans,
        int slabPlans,
        int complexShapePlans,
        int fluidContactPlans,
        int weightedModelVariants
    ) {
    }

    public record Debug(boolean diagnosticLogging) {
    }
}
