package jbro.cobblemon.morebattlecontent.client.render;

import jbro.cobblemon.morebattlecontent.internal.mixin.client.RenderTextureStateAccessor;
import jbro.cobblemon.morebattlecontent.internal.mixin.client.RenderTypeCompositeAccessor;
import jbro.cobblemon.morebattlecontent.internal.mixin.client.RenderTypeCompositeStateAccessor;
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
        RenderType.CompositeState state = ((RenderTypeCompositeAccessor) (Object) composite).mbcState();
        RenderStateShard.EmptyTextureStateShard textureState =
            ((RenderTypeCompositeStateAccessor) (Object) state).mbcTextureState();
        return ((RenderTextureStateAccessor) (Object) textureState).mbcCutoutTexture().orElse(null);
    }
}
