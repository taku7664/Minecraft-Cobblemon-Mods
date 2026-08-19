package jbro.cobblemon.betterbattlepresentation.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class DynamaxWorldGradeShaderResourcesTest {
    @Test
    void gradeDarkensTheRenderedWorldWithoutTouchingTheHud() throws Exception {
        String shaderJson = resource(
            "assets/cobblemon_better_battle_presentation/shaders/core/dynamax_world_grade.json"
        );
        String vertexShader = resource(
            "assets/cobblemon_better_battle_presentation/shaders/core/dynamax_world_grade.vsh"
        );
        String fragmentShader = resource(
            "assets/cobblemon_better_battle_presentation/shaders/core/dynamax_world_grade.fsh"
        );

        assertTrue(shaderJson.contains("EffectStrength"));
        assertTrue(vertexShader.contains("gl_Position = vec4(Position, 1.0)"));
        assertTrue(fragmentShader.contains("vec3(0.22, 0.01, 0.02)"));
        assertTrue(fragmentShader.contains("EffectStrength * 0.24"));
    }

    private String resource(String path) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path + " must exist");
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
