package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jbro.minecraft.roundingblock.client.settings.RoundingBlockConfig;
import org.junit.jupiter.api.Test;

class RoundedBlockRuntimeTest {
    @Test
    void disabledBakeDoesNotEnterTheRoundingPipeline() {
        RoundedBlockModel.applyConfig(RoundingBlockConfig.defaults().withEnabled(false));
        RoundedBlockModel.beginModelBake();

        assertFalse(RoundedBlockModel.isEnabledForCurrentBake());
    }

    @Test
    void eachReloadGetsAnIsolatedModelBakeGeneration() {
        RoundedBlockModel.applyConfig(RoundingBlockConfig.defaults());
        RoundedBlockModel.beginModelBake();
        Object firstGeneration = RoundedBlockModel.currentBakeGenerationForTest();

        RoundedBlockModel.beginModelBake();
        Object secondGeneration = RoundedBlockModel.currentBakeGenerationForTest();

        assertTrue(RoundedBlockModel.isEnabledForCurrentBake());
        assertNotSame(firstGeneration, secondGeneration);
    }
}
