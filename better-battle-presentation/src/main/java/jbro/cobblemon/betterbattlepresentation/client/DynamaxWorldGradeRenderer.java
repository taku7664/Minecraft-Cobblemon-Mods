package jbro.cobblemon.betterbattlepresentation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import jbro.cobblemon.betterbattlepresentation.BetterBattlePresentation;

public final class DynamaxWorldGradeRenderer {
    private static boolean warned;

    private DynamaxWorldGradeRenderer() {
    }

    public static void render() {
        float strength = DynamaxAtmosphereClientState.strength();
        var shader = DynamaxWorldGradeShader.active();
        if (strength <= 0.0F || shader == null) {
            return;
        }

        RenderStateSnapshot renderState = RenderStateSnapshot.capture();
        try {
            var strengthUniform = shader.getUniform("EffectStrength");
            if (strengthUniform != null) {
                strengthUniform.set(strength);
            }

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            var builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX
            );
            builder.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 0.0F);
            builder.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 0.0F);
            builder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
            builder.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } catch (RuntimeException exception) {
            warnOnce(exception);
        } finally {
            renderState.restore();
        }
    }

    private static void warnOnce(RuntimeException exception) {
        if (warned) {
            return;
        }
        warned = true;
        BetterBattlePresentation.LOGGER.error(
            "Failed to apply the Dynamax world grade; skipping the effect",
            exception
        );
    }
}
