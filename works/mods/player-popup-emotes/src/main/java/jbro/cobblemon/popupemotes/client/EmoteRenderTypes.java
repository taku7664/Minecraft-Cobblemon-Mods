package jbro.cobblemon.popupemotes.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

final class EmoteRenderTypes {
    private static final Map<ResourceLocation, RenderType> NO_DEPTH_TYPES = new HashMap<>();

    private EmoteRenderTypes() {
    }

    static RenderType noDepth(ResourceLocation texture) {
        return NO_DEPTH_TYPES.computeIfAbsent(texture, EmoteRenderTypes::createNoDepth);
    }

    private static RenderType createNoDepth(ResourceLocation texture) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setOverlayState(RenderStateShard.OVERLAY)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false);
        return RenderType.create(
            "player_popup_emote_no_depth",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            1536,
            true,
            true,
            state
        );
    }
}
