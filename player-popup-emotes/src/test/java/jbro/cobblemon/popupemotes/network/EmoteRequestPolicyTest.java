package jbro.cobblemon.popupemotes.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EmoteRequestPolicyTest {
    @Test
    void acceptsKnownEmotesWithFiniteBoundedOffsets() {
        assertTrue(EmoteRequestPolicy.accepts("heart", 0.0F));
        assertTrue(EmoteRequestPolicy.accepts("sparkle", -1.0F));
        assertTrue(EmoteRequestPolicy.accepts("idea", 1.0F));
    }

    @Test
    void rejectsUnknownIdsAndUnsafeOffsets() {
        assertFalse(EmoteRequestPolicy.accepts("https://127.0.0.1/private.png", 0.0F));
        assertFalse(EmoteRequestPolicy.accepts("heart", Float.NaN));
        assertFalse(EmoteRequestPolicy.accepts("heart", Float.POSITIVE_INFINITY));
        assertFalse(EmoteRequestPolicy.accepts("heart", -1.01F));
        assertFalse(EmoteRequestPolicy.accepts("heart", 1.01F));
    }

    @Test
    void acceptsPublicHttpsEmotesButRejectsPrivateOrInsecureUrls() {
        assertTrue(EmoteRequestPolicy.accepts("https://cdn.example.com/hello.gif", 0.0F));
        assertFalse(EmoteRequestPolicy.accepts("https://imgur.com/a/DMZ0kSi", 0.0F));
        assertFalse(EmoteRequestPolicy.accepts("http://cdn.example.com/hello.gif", 0.0F));
        assertFalse(EmoteRequestPolicy.accepts("https://192.168.0.2/hello.gif", 0.0F));
    }
}
