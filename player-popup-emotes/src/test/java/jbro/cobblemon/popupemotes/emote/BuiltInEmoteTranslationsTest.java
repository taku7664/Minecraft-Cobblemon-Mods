package jbro.cobblemon.popupemotes.emote;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class BuiltInEmoteTranslationsTest {
    @Test
    void everyBuiltInEmoteHasEnglishAndKoreanNames() throws Exception {
        for (String language : new String[] {"en_us", "ko_kr"}) {
            JsonObject translations = load(language);
            for (BuiltInEmote emote : BuiltInEmotes.all()) {
                assertTrue(
                    translations.has(emote.translationKey()),
                    () -> language + " is missing " + emote.translationKey()
                );
                assertFalse(
                    translations.get(emote.translationKey()).getAsString().isBlank(),
                    () -> language + " is missing " + emote.translationKey()
                );
            }
        }
    }

    private JsonObject load(String language) throws Exception {
        String path = "assets/player_popup_emotes/lang/" + language + ".json";
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path + " must exist");
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
