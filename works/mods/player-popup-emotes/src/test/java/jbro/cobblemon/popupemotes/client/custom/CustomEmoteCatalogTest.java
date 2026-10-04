package jbro.cobblemon.popupemotes.client.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CustomEmoteCatalogTest {
    @Test
    void splitsCustomEmotesIntoNineSlotPagesWithoutReorderingThem() {
        var entries = new ArrayList<CustomEmoteDefinition>();
        for (int index = 0; index < 10; index++) {
            entries.add(new CustomEmoteDefinition("emote " + index, "https://example.com/" + index + ".gif"));
        }

        var catalog = new CustomEmoteCatalog(entries);

        assertEquals(2, catalog.pageCount());
        assertEquals(9, catalog.page(0).size());
        assertEquals("emote 0", catalog.page(0).getFirst().name());
        assertEquals(1, catalog.page(1).size());
        assertEquals("emote 9", catalog.page(1).getFirst().name());
    }

    @Test
    void capsTheCatalogAtFiveNineSlotPages() {
        var entries = new ArrayList<CustomEmoteDefinition>();
        for (int index = 0; index < 46; index++) {
            entries.add(new CustomEmoteDefinition("emote " + index, "https://example.com/" + index + ".png"));
        }

        assertThrows(IllegalArgumentException.class, () -> new CustomEmoteCatalog(entries));
    }

    @Test
    void configJsonRoundTripsTheVisibleValues() {
        var expected = List.of(
            new CustomEmoteDefinition("인사", "https://example.com/hello.gif"),
            new CustomEmoteDefinition("놀람", "https://example.com/wow.png")
        );

        String json = CustomEmoteConfigCodec.encode(expected);

        assertEquals(expected, CustomEmoteConfigCodec.decode(json));
    }
}
