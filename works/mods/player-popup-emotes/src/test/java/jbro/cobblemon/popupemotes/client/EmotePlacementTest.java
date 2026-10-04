package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EmotePlacementTest {
    @Test
    void emoteQuadStartsAboveTheVanillaNameTagPlane() {
        double playerHeight = 1.8;
        double nameTagPlane = playerHeight + 0.5;

        double emoteBottom = EmotePlacement.centerY(playerHeight, 0.0F) - 0.65 / 2.0;

        assertTrue(emoteBottom > nameTagPlane + 0.1);
    }
}
