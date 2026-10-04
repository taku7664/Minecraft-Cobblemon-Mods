package jbro.minecraft.roundingblock.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundingBlockConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsEveryUserFacingSetting() {
        var warnings = new ArrayList<String>();
        RoundingBlockConfig config = RoundingBlockConfig.parse("""
            {
              "enabled": false,
              "quality": {"radius": 0.125, "segments": 6},
              "cache": {
                "fullBlockPlans": 512,
                "slabPlans": 384,
                "complexShapePlans": 768,
                "fluidContactPlans": 640,
                "weightedModelVariants": 64
              },
              "debug": {"diagnosticLogging": false}
            }
            """, warnings::add);

        assertFalse(config.enabled());
        assertEquals(0.125, config.quality().radius());
        assertEquals(6, config.quality().segments());
        assertEquals(512, config.cache().fullBlockPlans());
        assertEquals(384, config.cache().slabPlans());
        assertEquals(768, config.cache().complexShapePlans());
        assertEquals(640, config.cache().fluidContactPlans());
        assertEquals(64, config.cache().weightedModelVariants());
        assertFalse(config.debug().diagnosticLogging());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidFieldsFallBackIndividually() {
        var warnings = new ArrayList<String>();
        RoundingBlockConfig config = RoundingBlockConfig.parse("""
            {
              "enabled": "yes",
              "quality": {"radius": 0.25, "segments": 0},
              "cache": {
                "fullBlockPlans": 4097,
                "slabPlans": 64,
                "complexShapePlans": -1,
                "fluidContactPlans": 0,
                "weightedModelVariants": 257
              },
              "debug": {"diagnosticLogging": "yes"}
            }
            """, warnings::add);

        RoundingBlockConfig defaults = RoundingBlockConfig.defaults();
        assertTrue(config.enabled());
        assertEquals(defaults.quality().radius(), config.quality().radius());
        assertEquals(defaults.quality().segments(), config.quality().segments());
        assertEquals(defaults.cache().fullBlockPlans(), config.cache().fullBlockPlans());
        assertEquals(64, config.cache().slabPlans());
        assertEquals(defaults.cache().complexShapePlans(), config.cache().complexShapePlans());
        assertEquals(defaults.cache().fluidContactPlans(), config.cache().fluidContactPlans());
        assertEquals(defaults.cache().weightedModelVariants(), config.cache().weightedModelVariants());
        assertEquals(defaults.debug().diagnosticLogging(), config.debug().diagnosticLogging());
        assertEquals(8, warnings.size());
    }

    @Test
    void numericStringsAndFractionalCacheCountsAreRejected() {
        var warnings = new ArrayList<String>();
        RoundingBlockConfig config = RoundingBlockConfig.parse("""
            {
              "quality": {"radius": "0.125", "segments": 2.5},
              "cache": {"slabPlans": 64.5}
            }
            """, warnings::add);

        RoundingBlockConfig defaults = RoundingBlockConfig.defaults();
        assertEquals(defaults.quality(), config.quality());
        assertEquals(defaults.cache().slabPlans(), config.cache().slabPlans());
        assertEquals(3, warnings.size());
    }

    @Test
    void missingFileIsCreatedWithDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("rounding-block.json");
        var warnings = new ArrayList<String>();

        RoundingBlockConfig loaded = RoundingBlockConfig.load(path, warnings::add);

        assertEquals(RoundingBlockConfig.defaults(), loaded);
        assertTrue(Files.isRegularFile(path));
        assertTrue(Files.readString(path).contains("\"segments\": 3"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void malformedFileIsPreservedAndDefaultsAreUsed() throws Exception {
        Path path = temporaryDirectory.resolve("rounding-block.json");
        Files.writeString(path, "{broken");
        var warnings = new ArrayList<String>();

        RoundingBlockConfig loaded = RoundingBlockConfig.load(path, warnings::add);

        assertEquals(RoundingBlockConfig.defaults(), loaded);
        assertEquals("{broken", Files.readString(path));
        assertEquals(1, warnings.size());
    }

    @Test
    void commandStyleUpdatesRoundTripThroughAtomicSave() throws Exception {
        Path path = temporaryDirectory.resolve("rounding-block.json");
        RoundingBlockConfig updated = RoundingBlockConfig.defaults()
            .withEnabled(false)
            .withQuality(new RoundingBlockConfig.Quality(0.125, 7))
            .withCache(new RoundingBlockConfig.Cache(512, 384, 768, 640, 64))
            .withDebug(new RoundingBlockConfig.Debug(false));

        updated.save(path);
        RoundingBlockConfig loaded = RoundingBlockConfig.load(path, message -> {
            throw new AssertionError(message);
        });

        assertEquals(updated, loaded);
        assertTrue(Files.readString(path).contains("\"enabled\": false"));
    }

    @Test
    void everyCommandValueUpdatesOnlyItsMatchingJsonField() {
        RoundingBlockConfig defaults = RoundingBlockConfig.defaults();

        assertEquals(false, defaults.withEnabled(false).enabled());
        assertEquals(0.125, defaults.withRadius(0.125).quality().radius());
        assertEquals(7, defaults.withSegments(7).quality().segments());
        assertEquals(512, defaults.withFullBlockPlans(512).cache().fullBlockPlans());
        assertEquals(384, defaults.withSlabPlans(384).cache().slabPlans());
        assertEquals(768, defaults.withComplexShapePlans(768).cache().complexShapePlans());
        assertEquals(640, defaults.withFluidContactPlans(640).cache().fluidContactPlans());
        assertEquals(64, defaults.withWeightedModelVariants(64).cache().weightedModelVariants());
        assertFalse(defaults.withDiagnosticLogging(false).debug().diagnosticLogging());

        assertEquals(defaults.cache(), defaults.withSegments(7).cache());
        assertEquals(defaults.quality(), defaults.withFullBlockPlans(512).quality());
        assertEquals(defaults.debug(), defaults.withRadius(0.125).debug());
    }
}
