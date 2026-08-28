package jbro.cobblemon.betterbattlepresentation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

final class RenderStateSnapshot {
    private final boolean depthTest;
    private final boolean blend;
    private final boolean cull;
    private final boolean depthMask;
    private final int depthFunction;
    private final int blendSourceRgb;
    private final int blendDestinationRgb;
    private final int blendSourceAlpha;
    private final int blendDestinationAlpha;
    private final float[] shaderColor;

    private RenderStateSnapshot(
        boolean depthTest,
        boolean blend,
        boolean cull,
        boolean depthMask,
        int depthFunction,
        int blendSourceRgb,
        int blendDestinationRgb,
        int blendSourceAlpha,
        int blendDestinationAlpha,
        float[] shaderColor
    ) {
        this.depthTest = depthTest;
        this.blend = blend;
        this.cull = cull;
        this.depthMask = depthMask;
        this.depthFunction = depthFunction;
        this.blendSourceRgb = blendSourceRgb;
        this.blendDestinationRgb = blendDestinationRgb;
        this.blendSourceAlpha = blendSourceAlpha;
        this.blendDestinationAlpha = blendDestinationAlpha;
        this.shaderColor = shaderColor;
    }

    static RenderStateSnapshot capture() {
        return new RenderStateSnapshot(
            GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            GL11.glIsEnabled(GL11.GL_BLEND),
            GL11.glIsEnabled(GL11.GL_CULL_FACE),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
            GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
            GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
            RenderSystem.getShaderColor().clone()
        );
    }

    void restore() {
        RenderSystem.depthFunc(depthFunction);
        RenderSystem.blendFuncSeparate(
            blendSourceRgb,
            blendDestinationRgb,
            blendSourceAlpha,
            blendDestinationAlpha
        );
        if (depthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        if (blend) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        if (cull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        RenderSystem.depthMask(depthMask);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
    }
}
