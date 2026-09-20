package jbro.cobblemon.popupemotes.emote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class BuiltInEmotesTest {
    @Test
    void exposesNineOrderedUniqueEmotesUsingMinecraftTextures() {
        var emotes = BuiltInEmotes.all();

        assertEquals(9, emotes.size());
        assertEquals(9, new HashSet<>(emotes.stream().map(BuiltInEmote::id).toList()).size());
        assertEquals("hello", emotes.get(8).id());
        assertTrue(emotes.stream().allMatch(emote -> "minecraft".equals(emote.texture().getNamespace())));
        assertTrue(emotes.stream().allMatch(emote -> emote.texture().getPath().startsWith("textures/item/")));
    }

    @Test
    void onlyRegisteredIdsAreAccepted() {
        assertTrue(BuiltInEmotes.contains("heart"));
        assertFalse(BuiltInEmotes.contains("https://127.0.0.1/private.png"));
        assertFalse(BuiltInEmotes.contains("unknown"));
    }
}
