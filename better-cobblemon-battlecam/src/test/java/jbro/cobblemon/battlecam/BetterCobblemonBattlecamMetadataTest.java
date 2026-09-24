package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BetterCobblemonBattlecamMetadataTest {
    @Test
    void ownsANonConflictingIdAndRequiresTheValidatedRuntime() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            var metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var dependencies = metadata.getAsJsonObject("depends");

            assertEquals("better_cobblemon_battlecam", metadata.get("id").getAsString());
            assertEquals("assets/better_cobblemon_battlecam/icon.png", metadata.get("icon").getAsString());
            assertEquals(">=0.19.5", dependencies.get("fabricloader").getAsString());
            assertEquals(">=1.8.1 <1.9.0", dependencies.get("cobblemon").getAsString());
            assertEquals(
                "jbro.cobblemon.battlecam.client.BattlecamModMenu",
                metadata.getAsJsonObject("entrypoints").getAsJsonArray("modmenu").get(0).getAsString()
            );
            assertEquals("*", metadata.getAsJsonObject("suggests").get("modmenu").getAsString());
            assertEquals("battlecam.mixins.json", metadata.getAsJsonArray("mixins").get(0).getAsString());
        }
    }

    @Test
    void loadsTheOriginalBattleCamMixinSet() throws Exception {
        try (var stream = getClass().getResourceAsStream("/battlecam.mixins.json")) {
            var mixins = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonArray("client")
                .asList()
                .stream()
                .map(element -> element.getAsString())
                .toList();

            assertEquals(List.of(
                "BattleFaintHandlerMixin",
                "BattleHealthChangeHandlerMixin",
                "BattleMessageHandlerMixin",
                "CameraMixin",
                "EntityRenderDispatcherMixin",
                "GameRendererMixin",
                "HeldItemRendererMixin",
                "InGameHudMixin",
                "PlayPosableAnimationHandlerMixin"
            ), mixins);
        }
    }
}
