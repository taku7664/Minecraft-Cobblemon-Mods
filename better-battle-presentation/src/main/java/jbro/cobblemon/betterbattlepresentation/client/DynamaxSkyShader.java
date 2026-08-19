package jbro.cobblemon.betterbattlepresentation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import jbro.cobblemon.betterbattlepresentation.BetterBattlePresentation;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

final class DynamaxSkyShader {
    private static final ResourceLocation SHADER_ID = ResourceLocation.fromNamespaceAndPath(
        BetterBattlePresentation.MOD_ID,
        "dynamax_sky"
    );

    private static volatile ShaderInstance shader;

    private DynamaxSkyShader() {
    }

    static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            try {
                context.register(SHADER_ID, DefaultVertexFormat.POSITION_TEX, loaded -> {
                    shader = loaded;
                    BetterBattlePresentation.LOGGER.info("Loaded Better Battle Presentation Dynamax sky shader");
                });
            } catch (IOException exception) {
                shader = null;
                BetterBattlePresentation.LOGGER.error(
                    "Failed to load the Dynamax sky shader; the sky effect is disabled",
                    exception
                );
            }
        });
    }

    static ShaderInstance active() {
        return shader;
    }
}
