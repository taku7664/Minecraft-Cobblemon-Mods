package jbro.cobblemon.mcc.client.render;

import jbro.cobblemon.mcc.internal.mixin.client.RenderTextureStateAccessor;
import jbro.cobblemon.mcc.internal.mixin.client.RenderTypeCompositeAccessor;
import jbro.cobblemon.mcc.internal.mixin.client.RenderTypeCompositeStateAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class RenderTypeTextureBridge {
    private RenderTypeTextureBridge() {
    }

    public static ResourceLocation textureOf(RenderType renderType) {
        if (!(renderType instanceof RenderType.CompositeRenderType composite)) {
            return null;
        }
        RenderType.CompositeState state = ((RenderTypeCompositeAccessor) (Object) composite).mccState();
        RenderStateShard.EmptyTextureStateShard textureState =
            ((RenderTypeCompositeStateAccessor) (Object) state).mccTextureState();
        return ((RenderTextureStateAccessor) (Object) textureState).mccCutoutTexture().orElse(null);
    }
}
