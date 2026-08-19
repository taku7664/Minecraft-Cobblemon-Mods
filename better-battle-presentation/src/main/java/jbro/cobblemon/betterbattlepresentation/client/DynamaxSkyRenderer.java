package jbro.cobblemon.betterbattlepresentation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import jbro.cobblemon.betterbattlepresentation.BetterBattlePresentation;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

public final class DynamaxSkyRenderer {
    private static boolean warned;

    private DynamaxSkyRenderer() {
    }

    public static void render(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        float strength = DynamaxAtmosphereClientState.strength();
        if (strength <= 0.0F) {
            return;
        }
        ShaderInstance shader = DynamaxSkyShader.active();
        if (shader == null) {
            return;
        }

        try {
            setFloat(shader, "EffectStrength", strength);
            setFloat(
                shader,
                DynamaxSkyAnimationClock.UNIFORM_NAME,
                DynamaxSkyAnimationClock.seconds(System.nanoTime())
            );
            var inverseViewProjection = new Matrix4f(projectionMatrix).mul(modelViewMatrix).invert();
            var inverseUniform = shader.getUniform("InverseViewProjection");
            if (inverseUniform != null) {
                inverseUniform.set(inverseViewProjection);
            }
            var worldUpUniform = shader.getUniform("WorldUp");
            if (worldUpUniform != null) {
                worldUpUniform.set(0.0F, 1.0F, 0.0F);
            }

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            drawFullscreenQuad();
        } catch (RuntimeException exception) {
            warnOnce(exception);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    private static void setFloat(ShaderInstance shader, String name, float value) {
        var uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void drawFullscreenQuad() {
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
        builder.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void warnOnce(RuntimeException exception) {
        if (warned) {
            return;
        }
        warned = true;
        BetterBattlePresentation.LOGGER.error(
            "Failed to composite the Dynamax sky; skipping the effect",
            exception
        );
    }
}
