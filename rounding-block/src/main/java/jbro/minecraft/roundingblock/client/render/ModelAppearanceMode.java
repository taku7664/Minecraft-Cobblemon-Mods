package jbro.minecraft.roundingblock.client.render;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;

public enum ModelAppearanceMode {
    STATIC,
    DYNAMIC,
    UNSUPPORTED;

    public static ModelAppearanceMode forModel(BakedModel model) {
        if (model instanceof SimpleBakedModel) {
            return STATIC;
        }
        if (model instanceof WeightedBakedModel) {
            return DYNAMIC;
        }
        return UNSUPPORTED;
    }
}
