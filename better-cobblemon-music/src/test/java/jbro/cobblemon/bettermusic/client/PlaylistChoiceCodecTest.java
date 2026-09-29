package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlaylistChoiceCodecTest {
    @Test
    void renderedPackDefaultRoundTripsToTheEmptySentinel() {
        String display = "리소스팩 기본값 (cobleserver:track/field/plains/theme)";

        assertEquals("", PlaylistChoiceCodec.parseInput(display, display));
        assertEquals(
            "cobleserver:track/field/plains/theme",
            PlaylistChoiceCodec.parseInput(
                "cobleserver:track/field/plains/theme",
                display
            )
        );
    }
}
