package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.util.random.WeightedEntry;
import org.junit.jupiter.api.Test;

class ModelAppearanceModeTest {
    @Test
    void simpleCubeCanReuseAppearanceAcrossBlocks() {
        SimpleBakedModel model = simpleModel();

        assertEquals(ModelAppearanceMode.STATIC, ModelAppearanceMode.forModel(model));
    }

    @Test
    void weightedCubeMustResolveItsSeededVariantPerBlock() {
        WeightedBakedModel model = new WeightedBakedModel(List.of(WeightedEntry.wrap(simpleModel(), 1)));

        assertEquals(ModelAppearanceMode.DYNAMIC, ModelAppearanceMode.forModel(model));
    }

    private static SimpleBakedModel simpleModel() {
        return new SimpleBakedModel(
            List.of(),
            Map.of(),
            true,
            true,
            true,
            null,
            ItemTransforms.NO_TRANSFORMS,
            ItemOverrides.EMPTY
        );
    }
}
