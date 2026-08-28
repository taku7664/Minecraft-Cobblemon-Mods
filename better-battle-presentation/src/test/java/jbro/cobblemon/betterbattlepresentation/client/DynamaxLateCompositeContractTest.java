package jbro.cobblemon.betterbattlepresentation.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DynamaxLateCompositeContractTest {
    @Test
    void dynamaxAtmosphereRunsAfterIrisAndMbcInOneOrderedWorldTail() throws Exception {
        String mixin = source(
            "src/main/java/jbro/cobblemon/betterbattlepresentation/mixin/client/LevelRendererMixin.java"
        );

        assertTrue(mixin.contains("@Mixin(value = LevelRenderer.class, priority = 800)"));
        assertFalse(mixin.contains("method = \"renderSky\""));
        assertTrue(mixin.contains("method = \"renderLevel\", at = @At(\"RETURN\")"));
        assertTrue(
            mixin.indexOf("DynamaxSkyRenderer.render(modelViewMatrix, projectionMatrix)")
                < mixin.indexOf("DynamaxWorldGradeRenderer.render()")
        );
    }

    @Test
    void lateSkyOnlyDrawsAtFarDepthAndRestoresIncomingRenderState() throws Exception {
        String renderer = source(
            "src/main/java/jbro/cobblemon/betterbattlepresentation/client/DynamaxSkyRenderer.java"
        );
        String renderState = source(
            "src/main/java/jbro/cobblemon/betterbattlepresentation/client/RenderStateSnapshot.java"
        );

        assertTrue(renderer.contains("RenderSystem.enableDepthTest()"));
        assertTrue(renderer.contains("GL11.GL_LEQUAL"));
        assertTrue(renderer.contains("SKY_DEPTH"));
        assertTrue(renderer.contains("RenderStateSnapshot.capture()"));
        assertTrue(renderState.contains("glIsEnabled(GL11.GL_DEPTH_TEST)"));
        assertTrue(renderState.contains("glGetBoolean(GL11.GL_DEPTH_WRITEMASK)"));
        assertTrue(renderState.contains("RenderSystem.depthFunc(depthFunction)"));
        assertTrue(renderState.contains("RenderSystem.blendFuncSeparate("));
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
