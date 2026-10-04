package jbro.cobblemon.betterbattlepresentation.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class DynamaxSkyShaderResourcesTest {
    @Test
    void shaderDeclaresTheFadeAndWorldDirectionContract() throws Exception {
        String shaderJson = resource("assets/cobblemon_better_battle_presentation/shaders/core/dynamax_sky.json");
        String vertexShader = resource("assets/cobblemon_better_battle_presentation/shaders/core/dynamax_sky.vsh");
        String fragmentShader = resource("assets/cobblemon_better_battle_presentation/shaders/core/dynamax_sky.fsh");
        String mixins = resource("cobblemon_better_battle_presentation.mixins.json");

        assertTrue(shaderJson.contains("EffectStrength"));
        assertTrue(shaderJson.contains("EffectTimeSeconds"));
        assertFalse(shaderJson.contains("\"GameTime\""));
        assertTrue(shaderJson.contains("InverseViewProjection"));
        assertTrue(shaderJson.contains("WorldUp"));
        assertTrue(vertexShader.contains("gl_Position"));
        assertTrue(fragmentShader.contains("EffectStrength"));
        assertTrue(fragmentShader.contains("EffectTimeSeconds"));
        assertFalse(fragmentShader.contains("uniform float GameTime"));
        assertTrue(fragmentShader.contains("atan"));
        assertTrue(fragmentShader.contains("smoothstep"));
        assertTrue(fragmentShader.contains("stormOcclusion"));
        assertTrue(fragmentShader.contains("auroraCurtain"));
        assertTrue(fragmentShader.contains("horizonPresence"));
        assertTrue(fragmentShader.contains("tornEdge"));
        assertFalse(fragmentShader.contains("horizonMask"));
        assertTrue(mixins.contains("\"required\": true"));
        assertTrue(mixins.contains("client.LevelRendererMixin"));
        assertTrue(mixins.contains("client.DynamaxCloudColorMixin"));
    }

    @Test
    void auroraKeepsTheApprovedPaletteAndMovesAroundTheZenith() throws Exception {
        String fragmentShader = resource("assets/cobblemon_better_battle_presentation/shaders/core/dynamax_sky.fsh");
        String mixins = resource("cobblemon_better_battle_presentation.mixins.json");

        assertTrue(fragmentShader.contains("vec3 darkSky = vec3(0.010, 0.0015, 0.007)"));
        assertTrue(fragmentShader.contains("vec3 stormBurgundy = vec3(0.105, 0.004, 0.020)"));
        assertTrue(fragmentShader.contains("vec3 deepCrimson = vec3(0.42, 0.006, 0.075)"));
        assertTrue(fragmentShader.contains("vec3 hotMagenta = vec3(1.00, 0.018, 0.30)"));
        assertTrue(fragmentShader.contains("auroraOrbitAngle"));
        assertTrue(fragmentShader.contains("orbitAzimuth"));
        assertTrue(fragmentShader.contains("travelingWave"));
        assertTrue(fragmentShader.contains("counterWave"));
        assertTrue(fragmentShader.contains("curtainDisplacement"));
        assertTrue(fragmentShader.contains("orbitCycleTime = EffectTimeSeconds * 0.034906585"));
        assertTrue(fragmentShader.contains("auroraOrbitAngle = orbitCycleTime"));
        assertTrue(fragmentShader.contains("orbitRotation"));
        assertTrue(fragmentShader.contains("primarySpiralPhase"));
        assertTrue(fragmentShader.contains("secondarySpiralPhase"));
        assertTrue(fragmentShader.contains("spiralWave"));
        assertTrue(fragmentShader.contains("spiralDisplacement = spiralWave * 0.07"));
        assertTrue(fragmentShader.contains("travelingWave * 0.06"));
        assertTrue(fragmentShader.contains("counterWave * 0.03"));
        assertTrue(fragmentShader.contains("spiralRibbon"));
        assertFalse(mixins.contains("client.DynamaxLightTextureMixin"));
    }

    private String resource(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path + " must exist");
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
