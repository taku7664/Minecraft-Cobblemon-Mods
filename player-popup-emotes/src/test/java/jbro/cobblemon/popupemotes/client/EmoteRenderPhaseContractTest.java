package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EmoteRenderPhaseContractTest {
    @Test
    void rendersAfterAllWorldFramebufferWrites() throws Exception {
        Path relative = Path.of("src/main/java/jbro/cobblemon/popupemotes/client/PlayerPopupEmotesClient.java");
        Path source = Files.exists(relative) ? relative : Path.of("player-popup-emotes").resolve(relative);
        String clientInitializer = Files.readString(source);

        assertTrue(clientInitializer.contains("WorldRenderEvents.LAST.register(EmoteRenderer::render)"));
        assertFalse(clientInitializer.contains("WorldRenderEvents.AFTER_ENTITIES.register(EmoteRenderer::render)"));
    }
}
